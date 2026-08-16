/**
 * 会话自愈模块（v0.6.8）
 *
 * 场景：Web 端（dsh web）与桌面伴侣共享 ~/.dsh/sessions，两个 runtime 同时写同一会话
 * 会交错 append 导致 seq 重叠/乱序（历史 BUG-2，session-1dcd46fb 即因此损坏）。
 * 本模块按 dsh-session-persistence-jsonl 的读取语义（scanZstdFrames → 逐帧解压 →
 * header 单行 → seq 连续）校验会话文件，发现损坏时自动重建（重编号 + 重新压缩），
 * 原文件先备份再原子替换。
 *
 * 不引入外部依赖：zstd 用 node:zlib 内置（Node 22.19+）。
 */
import { readFile, writeFile, rename, copyFile, stat, readdir } from 'node:fs/promises'
import { constants, zstdCompressSync, zstdDecompressSync } from 'node:zlib'
import { homedir } from 'node:os'
import { join } from 'node:path'

const ZSTD_MAGIC = 4247762216 // 0xFD2FB528 LE
const CHECKSUM_OPTIONS = { params: { [constants.ZSTD_c_checksumFlag]: 1 } }

interface Frame { start: number; end: number }

/** 复刻 dsh-session-persistence-jsonl 的 scanZstdFrames：扫描完整帧边界。 */
function scanZstdFrames(buffer: Buffer): { frames: Frame[]; tornStart?: number } {
  const frames: Frame[] = []
  let offset = 0
  while (offset < buffer.length) {
    const start = offset
    if (buffer.length - offset < 4) return { frames, tornStart: start }
    if (buffer.readUInt32LE(offset) !== ZSTD_MAGIC) {
      throw new Error(`corrupt: invalid frame magic at byte ${offset}`)
    }
    offset += 4
    if (offset === buffer.length) return { frames, tornStart: start }
    const descriptor = buffer.readUInt8(offset)
    offset += 1
    if ((descriptor & 24) !== 0) throw new Error(`corrupt: reserved frame-header bit at byte ${offset - 1}`)
    const contentSizeFlag = descriptor >>> 6
    const singleSegment = (descriptor & 32) !== 0
    const checksum = (descriptor & 4) !== 0
    const dictionaryFlag = descriptor & 3
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag
    const contentSizeBytes = contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag
    const remainingHeaderBytes = (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes
    if (buffer.length - offset < remainingHeaderBytes) return { frames, tornStart: start }
    offset += remainingHeaderBytes
    for (;;) {
      if (buffer.length - offset < 3) return { frames, tornStart: start }
      const blockHeader = buffer.readUIntLE(offset, 3)
      offset += 3
      const lastBlock = (blockHeader & 1) !== 0
      const blockType = (blockHeader >>> 1) & 3
      const blockSize = blockHeader >>> 3
      if (blockType === 3) throw new Error(`corrupt: reserved block type at byte ${offset - 3}`)
      const payloadBytes = blockType === 1 ? 1 : blockSize
      if (buffer.length - offset < payloadBytes) return { frames, tornStart: start }
      offset += payloadBytes
      if (lastBlock) break
    }
    if (checksum) {
      if (buffer.length - offset < 4) return { frames, tornStart: start }
      offset += 4
    }
    frames.push({ start, end: offset })
  }
  return { frames }
}

/** 逐帧解压拼接（zstdDecompressSync 校验帧内 checksum，损坏会 throw）。 */
function decodeAllFrames(buffer: Buffer, frames: Frame[]): Buffer {
  const parts: Buffer[] = []
  for (const frame of frames) {
    parts.push(zstdDecompressSync(buffer.subarray(frame.start, frame.end)))
  }
  return Buffer.concat(parts)
}

/** 校验明文 seq 连续（复刻 consumeEventLine），有 gap 抛错。返回事件总数。 */
function validateSeq(text: string): number {
  const lines = text.split('\n').filter(Boolean)
  let running = 0
  for (const [index, line] of lines.entries()) {
    const o = JSON.parse(line) as Record<string, unknown>
    if (o.seq0 != null) {
      const data = o.data as { dt?: unknown[]; texts?: unknown[] } | undefined
      let len = 1
      if (Array.isArray(data?.dt)) len = data.dt.length
      else if (Array.isArray(data?.texts)) len = data.texts.length
      if (o.seq0 !== running) throw new Error(`seq gap at line ${index + 2} (expected ${running}, got ${o.seq0})`)
      running += len + 1
    } else if (typeof o.seq === 'number') {
      if (o.seq !== running) throw new Error(`seq gap at line ${index + 2} (expected ${running}, got ${o.seq})`)
      running += 1
    }
  }
  return running
}

/** 重编号：按行序列重写 seq / seq0（顶层 seq 行占 1 个编号；chunks 行按 dt/texts.length+1）。 */
function renumberEvents(text: string): { header: string; rest: string; fixed: number } {
  const lines = text.split('\n').filter(Boolean)
  let next = 0
  let fixed = 0
  const rebuilt = lines.map((line) => {
    try {
      const o = JSON.parse(line) as Record<string, unknown>
      if (o.seq0 != null) {
        const data = o.data as { dt?: unknown[]; texts?: unknown[] } | undefined
        let len = 1
        if (Array.isArray(data?.dt)) len = data.dt.length
        else if (Array.isArray(data?.texts)) len = data.texts.length
        if (o.seq0 !== next) fixed++
        o.seq0 = next
        next += len + 1
      } else if (typeof o.seq === 'number') {
        if (o.seq !== next) fixed++
        o.seq = next
        next += 1
      }
      return JSON.stringify(o)
    } catch {
      return line // 解析失败的行原样保留（不应出现）
    }
  })
  return { header: `${rebuilt[0]}\n`, rest: `${rebuilt.slice(1).join('\n')}\n`, fixed }
}

export interface RepairResult {
  ok: boolean
  repaired: boolean
  message: string
}

/**
 * 定位会话文件（~/.dsh/sessions/<workspace>/session-<id>/session.jsonl.zstd）。
 * 直接扫目录而非用 persistence API（文件损坏时 listSnapshots 可能一并失败）。
 * 目录名 = sessionId（id 本身带 session- 前缀），兼容缺前缀的形式。
 */
export async function locateSessionFile(sessionId: string): Promise<string | undefined> {
  const root = join(homedir(), '.dsh', 'sessions')
  let workspaces: string[]
  try {
    const entries = await readdir(root, { withFileTypes: true })
    workspaces = entries.filter(entry => entry.isDirectory()).map(entry => entry.name)
  } catch {
    return undefined
  }
  const targets = [sessionId, `session-${sessionId}`]
  for (const workspace of workspaces) {
    for (const target of targets) {
      const candidate = join(root, workspace, target, 'session.jsonl.zstd')
      try {
        await stat(candidate)
        return candidate
      } catch {
        // 继续找
      }
    }
  }
  return undefined
}

/**
 * 检查并（必要时）修复一个 dsh 会话文件（session.jsonl.zstd）。
 * @param path 会话文件绝对路径
 */
export async function repairSessionFile(path: string): Promise<RepairResult> {
  let buffer: Buffer
  try {
    buffer = await readFile(path)
  } catch {
    return { ok: false, repaired: false, message: `会话文件不存在或不可读: ${path}` }
  }
  if (buffer.length === 0) {
    return { ok: false, repaired: false, message: `会话文件为空: ${path}` }
  }

  let frames: Frame[]
  let tornStart: number | undefined
  try {
    const scanned = scanZstdFrames(buffer)
    frames = scanned.frames
    tornStart = scanned.tornStart
  } catch (error) {
    return { ok: false, repaired: false, message: `帧扫描失败: ${error instanceof Error ? error.message : String(error)}` }
  }
  if (frames.length < 2) {
    return { ok: false, repaired: false, message: `帧数不足（${frames.length}），无法解析` }
  }

  let headerText: string
  let bodyText: string
  try {
    const headerPlain = zstdDecompressSync(buffer.subarray(frames[0].start, frames[0].end))
    if (headerPlain.length === 0 || headerPlain.indexOf(10) !== headerPlain.length - 1) {
      return { ok: false, repaired: false, message: 'first frame is not exactly one header line' }
    }
    headerText = headerPlain.toString('utf8')
    bodyText = decodeAllFrames(buffer, frames.slice(1)).toString('utf8')
  } catch (error) {
    return { ok: false, repaired: false, message: `解压失败: ${error instanceof Error ? error.message : String(error)}` }
  }

  // seq 连续性校验
  let healthySeq = false
  try {
    validateSeq(headerText + bodyText)
    healthySeq = true
  } catch {
    healthySeq = false
  }
  if (tornStart === undefined && healthySeq) {
    return { ok: true, repaired: false, message: '会话文件健康，无需修复' }
  }

  // 需要重建：备份原文件 → 重编号 → 重新压缩（header 单帧 + 事件帧，带 checksum）→ 原子替换
  const { header, rest, fixed } = renumberEvents(headerText + bodyText)
  const frame1 = zstdCompressSync(Buffer.from(header, 'utf8'), CHECKSUM_OPTIONS)
  const frame2 = zstdCompressSync(Buffer.from(rest, 'utf8'), CHECKSUM_OPTIONS)
  const rebuilt = Buffer.concat([frame1, frame2])

  try {
    const backup = `${path}.bak-${Date.now()}`
    await copyFile(path, backup)
    const tmp = `${path}.tmp-${Date.now()}`
    await writeFile(tmp, rebuilt)
    await rename(tmp, path)
    return {
      ok: true,
      repaired: true,
      message: `已自动修复：${fixed} 处 seq 重编号，原文件备份至 ${backup}`,
    }
  } catch (error) {
    return { ok: false, repaired: false, message: `修复写入失败: ${error instanceof Error ? error.message : String(error)}` }
  }
}
