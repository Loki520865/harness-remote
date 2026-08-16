/**
 * Harness助手 · 自写 JSON-RPC SDK server（fork 官方 dsh-sdk-jsonrpc-server）
 *
 * 与官方唯一的行为差异：createSession 优先走 `agents.resume`（磁盘已有持久化日志时
 * 恢复会话上下文/记忆，支持跨进程续接），无日志才 `agents.create`（全新会话）。
 * 官方实现只用 create，遇到同 id 持久化日志会抛 id collision。
 * 其余逻辑（initialize/prompt/shutdown/事件订阅/子代理通知）与官方一致。
 */

import type { Context } from '@deepseek-ai/cordis'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mkdtemp, readFile, readdir, realpath, rename, rm, stat, writeFile } from 'node:fs/promises'
import { spawn, spawnSync } from 'node:child_process'
import { homedir, tmpdir } from 'node:os'
import { randomUUID } from 'node:crypto'
import type { Agent, AgentHandle } from '@deepseek-ai/dsh-agent'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { carrierKeyOf, type Scoped } from '@deepseek-ai/dsh-scope'
import { SessionId, type SessionEvent } from '@deepseek-ai/dsh-session'
import type SubagentRuntime from '@deepseek-ai/dsh-subagent'
import type { SubagentRunEndInfo } from '@deepseek-ai/dsh-subagent'
import * as LlmDeepSeek from '@deepseek-ai/dsh-llm-deepseek'
import type { AskUserQuestionAnswer } from '@deepseek-ai/dsh-user-questions'
import type { SessionPersistenceSnapshot } from '@deepseek-ai/dsh-session-persistence'
import { locateSessionFile, repairSessionFile } from './session-repair.ts'
import type {
  InitializeParams,
  InitializeResult,
  JsonRpcTransportPeer,
  SessionEventNotification,
  SessionPromptParams,
  SessionPromptResult,
  SubagentFinishedNotification,
  SubagentStartedNotification,
} from '@deepseek-ai/dsh-sdk-protocol'

interface SessionRecord {
  handle: AgentHandle
  /** 方案 A（双写冲突检测）：本进程已 append 的最后事件 seq（内存连续 seq）。 */
  memoryLastSeq?: number
  /** P1 修复（读历史/改名/删除不杀任务）：会话是否正在运行任务。 */
  running?: boolean
}

/** Harness助手扩展 · session.list 结果项。 */
export interface SessionListEntry {
  id: string
  /** 最新 session/title 事件的标题（无标题为 null）。 */
  title: string | null
  cwd: string | null
  createdAt: number | null
  updatedAt: number | null
}

/** Harness助手扩展 · session.rename 入参。 */
export interface SessionRenameParams {
  sessionId: string
  title: string
}

/** Harness助手扩展 · session.delete 入参。 */
export interface SessionDeleteParams {
  sessionId: string
}

/** Harness助手扩展 · session.messages 入参。 */
export interface SessionMessagesParams {
  sessionId: string
}

/** Harness助手扩展 · session.messages 结果项（人类可见 transcript 一行 = 一条消息）。 */
export interface SessionMessageEntry {
  role: 'user' | 'assistant'
  content: string
  time: number
}

/** Harness助手扩展 · session.create 入参（cwd 可空 = 默认工作区）。 */
export interface SessionCreateParams {
  cwd?: string
}

/** Harness助手扩展 · task.cancel 入参。 */
export interface TaskCancelParams {
  sessionId: string
}

/** Harness助手扩展 · 目录项。 */
export interface FileEntry {
  name: string
  type: 'dir' | 'file'
  size: number
  mtime: number
}

/** Harness助手扩展 · file.list 入参/结果。 */
export interface FileListParams {
  cwd: string
  path?: string
}

/** Harness助手扩展 · file.read / file.write 入参。 */
export interface FileTextParams {
  cwd: string
  path: string
  content?: string
  /** file.write 必须显式确认（F15：对齐 Web approval=ask）。 */
  confirmed?: boolean
}

/** Harness助手扩展 · file.download 入参。 */
export interface FileDownloadParams {
  cwd: string
  path: string
}

/** Harness助手扩展 · model/set 入参（全部可选，只更新传入项；新会话生效）。 */
export interface ModelSetParams {
  provider?: string
  model?: string
  maxTokens?: number | null
}

/** Harness助手扩展 · model 信息。 */
export interface ModelInfo {
  provider: string
  model: string
  maxTokens: number | null
  /** 当前可用的 provider 路由列表（id → 显示名）。 */
  providers: { id: string; name: string }[]
  /** 当前 provider 的候选模型名（适配器未提供时为空，App 可自定义）。 */
  modelCandidates: string[]
}

/** Harness助手扩展 · terminal/exec 入参。 */
export interface TerminalExecParams {
  cwd: string
  command: string
  /** 高危命令必须显式确认（对齐 F15 approval=ask）。 */
  confirmed?: boolean
}

/** Harness助手扩展 · terminal/exec 结果。 */
export interface TerminalExecResult {
  cwd: string
  command: string
  exitCode: number | null
  stdout: string
  stderr: string
  truncated: boolean
  timedOut: boolean
  durationMs: number
}

/** Harness助手扩展 · session/fork 入参。 */
export interface SessionForkParams {
  sessionId: string
}

/** Harness助手扩展 · session/trace 结果条目（手机端轨迹面板时间线）。 */
export interface TraceEntry {
  seq: number
  time: number
  type: 'user' | 'assistant' | 'reasoning' | 'tool' | 'tool-result' | 'step' | 'step-end'
  title: string
  /** 内容/参数摘要（截断，≤2KB）。 */
  detail?: string
  /** tool/call → tool/result 的耗时（毫秒）。 */
  durationMs?: number
}

/** Harness助手扩展 · ocr/image 入参（图片 base64，≤15MB）。 */
export interface OcrImageParams {
  base64: string
  name?: string
}

/** 远程终端高危命令特征（对齐手机端 isRisky 口径：含删除/格式化/关机/清库等）。 */
const HIGH_RISK_PATTERN = /(^|[\s;&|])(rm\s+-rf?|del\s+\/f\s|format\s+\w:|shutdown\s|reboot\s|mkfs\.\w+|dd\s+if=)|rm\s+-rf|清空|删除全部|格式化|清库|重置/

/** provider → 候选模型名（已知路由的常见模型；其余由 App 自定义输入）。 */
const MODEL_CATALOG: Record<string, string[]> = {
  'deepseek-official': ['deepseek-chat', 'deepseek-reasoner'],
}

/** 显式改名的标题被规范化后为空。 */
export class SessionTitleInvalidError extends Error {
  override readonly name = 'SessionTitleInvalidError'
}

/** 标题规范化上限（与 Web 端一致级：单行、清理控制字符、UTF-8 截断）。 */
const MAX_TITLE_BYTES = 200

/** 清理控制字符并规范化空白为单个空格。 */
function cleanTitleText(input: string): string {
  return input
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 规范化标题并施加 UTF-8 字节预算（不拆散码点）。 */
function normalizeTitleText(input: string, maxBytes = MAX_TITLE_BYTES): string {
  const cleaned = cleanTitleText(input)
  if (Buffer.byteLength(cleaned, 'utf8') <= maxBytes) return cleaned.trimEnd()
  let output = ''
  for (const character of cleaned) {
    if (Buffer.byteLength(output, 'utf8') + Buffer.byteLength(character, 'utf8') > maxBytes) break
    output += character
  }
  return output.trimEnd()
}

/** 把消息 content 块数组折叠成纯文本（text 块拼接，其余块忽略）。 */
function contentText(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  let out = ''
  for (const block of content) {
    const b = block as { type?: string; text?: string }
    if (b?.type === 'text' && typeof b.text === 'string') {
      out += out.length === 0 ? b.text : '\n' + b.text
    }
  }
  return out.trim()
}

/** 折叠日志中最新一条 session/title 事件（latest-wins），无则 undefined。 */
function foldTitleFromEvents(events: readonly SessionEvent[]): { title: string; updatedAt: number } | undefined {
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const event = events[i] as unknown as { type: string; time: number; data: { title?: unknown } }
    if (event.type !== 'session/title') continue
    if (typeof event.data.title !== 'string') continue
    return { title: event.data.title, updatedAt: event.time }
  }
  return undefined
}

/** Harness助手扩展 · 投影缓存同步操作。 */
type ProjectionCacheOp =
  | { type: 'rename'; id: string; title: string }
  | { type: 'delete'; id: string }

/**
 * Harness助手扩展 · 同步 dsh web 的会话列表投影缓存（session_projcache.json）。
 *
 * dsh web 的会话列表条目与标题只从该投影缓存读取（启动播种、运行中读内存，
 * 外部写入的 JSONL session/title 事件不会反映到 Web UI）。此处按与 dsh web
 * 相同的缓存结构直接更新文件：rename 改 title.val（ver 递增、seq 保持），
 * delete 移除整条会话记录。dsh web 重启后即呈现新标题/消失。
 *
 * 已知取舍（用户 2026-08-15 拍板 A 方案）：dsh web 运行中自己的 checkpoint 可能
 * 覆写本文件；因此更新失败/文件损坏时静默跳过，JSONL 始终是持久化真相。
 */
async function updateProjectionCache(ops: readonly ProjectionCacheOp[]): Promise<void> {
  try {
    const cachePath = join(homedir(), '.dsh', 'storages', 'session_projcache.json')
    let raw: string
    try {
      raw = await readFile(cachePath, 'utf8')
    } catch {
      return // 无投影缓存（未装 dsh web 或尚未生成），无需同步
    }
    const cache = JSON.parse(raw) as {
      tables?: { sessions?: Record<string, { rows?: Record<string, { ver?: number; val?: unknown }> }> }
    }
    const sessions = cache.tables?.sessions
    if (sessions === undefined) return
    for (const op of ops) {
      if (op.type === 'rename') {
        const titleRow = sessions[op.id]?.rows?.title
        if (titleRow === undefined) continue
        titleRow.val = op.title
        titleRow.ver = (titleRow.ver ?? 0) + 1
      } else {
        delete sessions[op.id]
      }
    }
    const tmpPath = `${cachePath}.tmp`
    await writeFile(tmpPath, JSON.stringify(cache, null, 2), 'utf8')
    await rename(tmpPath, cachePath)
  } catch {
    // 投影缓存同步失败不阻断主流程：JSONL 才是持久化真相
  }
}

/** Recover the delegating parent from the service-owned scoped carrier. */
function subagentParentOf(carrier: Scoped<SubagentRuntime>): Agent {
  return carrierKeyOf(carrier) as Agent
}

/** Deployment-specific status mapping for SDK turn and subagent outcomes. */
export interface HarnessCompanionJsonRpcServerOptions {
  /** Report max-token termination as an accepted result instead of an infrastructure error. */
  maxTokensAsSuccess?: boolean
}

function successStatus(reason: string, options: HarnessCompanionJsonRpcServerOptions): 'ok' | 'error' {
  if (reason === 'completed') return 'ok'
  return reason === 'max-tokens' && options.maxTokensAsSuccess === true ? 'ok' : 'error'
}

/**
 * SDK server over one booted harness context and transport peer. Construction
 * subscribes to session, agent, and subagent lifecycle events until shutdown;
 * reinitialization is unsupported.
 */
export class HarnessCompanionJsonRpcServer {
  private readonly ctx: Context
  private readonly transport: JsonRpcTransportPeer
  private readonly options: HarnessCompanionJsonRpcServerOptions
  private cwd = process.cwd()
  private provider = 'deepseek-official'
  private model = 'deepseek-official'
  private maxTokens: number | undefined
  private llmFiber: { dispose(): Promise<void> } | undefined
  private readonly sessions = new Map<string, SessionRecord>()
  private readonly sessionCreations = new Map<string, Promise<SessionRecord>>()
  private readonly disposers: (() => void)[] = []
  private shutdownTask: Promise<Record<string, never>> | undefined
  private shuttingDown = false
  /** v0.6.4：手机端 ask_user_question —— 挂起等待手机答案的提问（requestId → resolve/reject）。 */
  private readonly pendingQuestions = new Map<string, {
    resolve: (value: { answers: unknown }) => void
    reject: (err: Error) => void
    timer: NodeJS.Timeout
  }>()

  constructor(
    ctx: Context,
    transport: JsonRpcTransportPeer,
    options: HarnessCompanionJsonRpcServerOptions = {},
  ) {
    // 注意：node strip-only 模式不支持 TS 参数属性，必须显式赋值
    this.ctx = ctx
    this.transport = transport
    this.options = options
    const serverOptions = this.options
    this.disposers.push(ctx.on('session/event', (session, event) => {
      // 双写冲突检测基准（方案 A）：跟踪本进程已写 seq
      const rec = this.sessions.get(String(session.id))
      if (rec !== undefined) rec.memoryLastSeq = event.seq
      const payload: SessionEventNotification = { sessionId: String(session.id), event }
      this.transport.notify('session.event', payload)
    }))
    this.disposers.push(ctx.on('agent/status', ({ agent, status }) => {
      const rec = this.sessions.get(String(agent.session.id))
      if (rec !== undefined) rec.running = status !== 'idle'
      this.transport.notify('session.status', { sessionId: String(agent.session.id), status })
    }))
    this.disposers.push(ctx.on('session/created', (session) => {
      const parentSession = session.header.parentSession
      if (parentSession === undefined) return
      const payload: SubagentStartedNotification = {
        parentSessionId: String(parentSession),
        childSessionId: String(session.id),
      }
      this.transport.notify('subagent.started', payload)
    }))
    this.disposers.push(ctx.on('subagent/end', function (this: Scoped<SubagentRuntime>, info: SubagentRunEndInfo) {
      const parent = subagentParentOf(this)
      // This protocol reports only in-process child sessions. The service
      // snapshots the provider name and local flag through child disposal;
      // matching ids or parent lineage alone never establishes locality.
      if (!info.local) return
      const payload: SubagentFinishedNotification = {
        provider: info.provider,
        agentId: String(info.id),
        parentSessionId: String(parent.session.id),
        childSessionId: String(info.id),
        status: successStatus(info.stopReason, serverOptions),
        stopReason: info.stopReason,
        ...(info.lastAssistantMessage === undefined ? {} : { lastAssistantMessage: info.lastAssistantMessage }),
      }
      transport.notify('subagent.finished', payload)
    }))

    // ── v0.6.4：手机端 ask_user_question 交互 ──────────────────────────────
    // 模型调用 ask_user_question 时，UserQuestionService.ask() 会暂停并等待 UI
    // provider 返回答案。这里注册一个「桥」provider：把问题经 JSON-RPC 通知
    // `user.question` 发给主进程（tunnel）→ 转发到手机渲染选项卡；手机答案经
    // `user/answer` 方法回填，agent loop 继续。无 UI 应答时 10 分钟超时兜底。
    const PENDING_TTL_MS = 10 * 60_000
    // userQuestions service 由 cordis.yml 顶层挂载的 dsh-user-questions 提供
    // （子插件 isolate 之间不可见，service 必须注册在 root 才能被 tool-ask-user 找到）。
    const uq = ctx.userQuestions
    const disposeProvider = uq.registerProvider({
      ask: (request) => {
        if (request.signal?.aborted) {
          return Promise.reject(new Error('ask_user_question was aborted before the user answered'))
        }
        const requestId = randomUUID()
        return new Promise<AskUserQuestionAnswer>((resolve, reject) => {
          const timer = setTimeout(() => {
            this.pendingQuestions.delete(requestId)
            reject(new Error('提问等待用户回复超时（10 分钟），已自动取消'))
          }, PENDING_TTL_MS)
          this.pendingQuestions.set(requestId, {
            // 手机回传结构 {answers: [...]} 经 answerUserQuestion 透传；wire 类型对齐后强转
            resolve: (value) => resolve(value as never),
            reject,
            timer,
          })
          this.transport.notify('user.question', {
            requestId,
            sessionId: String(request.agent?.session?.id ?? ''),
            questions: request.questions.map((q) => ({
              id: q.id,
              question: q.question,
              ...(q.header !== undefined ? { header: q.header } : {}),
              ...(q.options !== undefined ? { options: q.options } : {}),
              ...(q.multiSelect !== undefined ? { multiSelect: q.multiSelect } : {}),
            })),
          })
        })
      },
    })
    this.disposers.push(() => disposeProvider())
  }

  /**
   * Configure the SDK route, mounting the DeepSeek fallback only when unowned.
   * @param params - SDK handshake parameters.
   * @returns server identity for the handshake.
   */
  async initialize(params: InitializeParams): Promise<InitializeResult> {
    if (params.maxTokens !== undefined
      && (!Number.isSafeInteger(params.maxTokens) || params.maxTokens <= 0)) {
      throw new TypeError('initialize maxTokens must be a positive safe integer')
    }
    this.cwd = resolve(params.cwd)
    this.provider = params.provider
    this.model = params.model
    this.maxTokens = params.maxTokens
    if (!this.hasAdapterFor(this.provider)) {
      if (this.provider !== 'deepseek-official') throw new Error(`no adapter registered for provider "${this.provider}"`)
      this.llmFiber = await this.ctx.plugin(LlmDeepSeek, {})
    }
    return { serverInfo: { name: 'deepseek-harness-sdk-runtime', version: '0.0.1' } }
  }

  /**
   * Queue one identified prompt without assigning later activity to it.
   * @param params - target session and user content.
   * @returns the durable message identity.
   */
  async prompt(params: SessionPromptParams): Promise<SessionPromptResult> {
    const rec = await this.getOrCreateSession(params.sessionId)
    // 双写冲突检测（方案 A，2026-08-15）：磁盘最后 seq 超过本进程已写 seq = 其他端（如 dsh web）
    // 正在写同一会话 → 拒绝执行，避免两个 runtime 交错 append 损坏会话文件（BUG-2 根因）。
    // v0.6.8：加载失败（文件已被写坏）→ 自动修复后重试一次，不再直接拒绝抛给用户手动修。
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence !== undefined) {
      let diskLast: number
      try {
        const loaded = await persistence.load(SessionId(params.sessionId))
        diskLast = loaded.events.at(-1)?.seq ?? -1
      } catch (error) {
        // 结构性损坏（seq gap / 坏帧）→ 尝试自愈
        const repaired = await this.repairSessionIfNeeded(params.sessionId)
        if (!repaired) {
          throw new Error(
            `会话文件校验失败且自动修复不成功（可能正在被其他端写入）：${error instanceof Error ? error.message : String(error)}`,
          )
        }
        console.log(`[self-heal] 会话 ${params.sessionId} 已自动修复，重试执行`)
        try {
          const loaded = await persistence.load(SessionId(params.sessionId))
          diskLast = loaded.events.at(-1)?.seq ?? -1
        } catch (error) {
          throw new Error(
            `会话文件已自愈但读取仍失败：${error instanceof Error ? error.message : String(error)}`,
          )
        }
        rec.memoryLastSeq = diskLast // 重编号后以磁盘为准，避免与旧编号误判冲突
      }
      if (rec.memoryLastSeq !== undefined && diskLast > rec.memoryLastSeq) {
        throw new Error(
          `检测到会话被其他端（如 Web 端）同时写入（本端 seq=${rec.memoryLastSeq}，磁盘 seq=${diskLast}），` +
          '为避免双写损坏已拒绝执行。请先关闭其他端该会话，或重启本端后再试。',
        )
      }
    }
    // An agent-loop-only reload disposes the loop's agents while this record
    // survives; a retained agent accepts followup() silently, so validate the
    // record against the live registry before delivery (as the ACP bridge does).
    if (this.ctx.agents.get(rec.handle.agent.id) !== rec.handle.agent) {
      throw new Error(`session agent was disposed outside the server: ${params.sessionId}`)
    }
    const message = createUserMessage({ content: params.contentBlocks, source: { kind: 'user' } })
    rec.handle.agent.followup(message)
    return { messageId: message.id }
  }

  /**
   * Dispose server-owned agents, adapter, and subscriptions to quiescence.
   * The surrounding context remains running.
   * @returns empty JSON-RPC result.
   */
  shutdown(): Promise<Record<string, never>> {
    this.shutdownTask ??= this.performShutdown()
    return this.shutdownTask
  }

  private async performShutdown(): Promise<Record<string, never>> {
    this.shuttingDown = true
    const pendingCreations = [...this.sessionCreations.values()]
    await Promise.allSettled(pendingCreations)
    this.sessionCreations.clear()
    const records = [...this.sessions.values()]
    this.sessions.clear()
    const failures: unknown[] = []
    while (this.disposers.length > 0) {
      try {
        this.disposers.pop()?.()
      } catch (error) {
        failures.push(error)
      }
    }
    const teardownResults = await Promise.allSettled([
      ...records.map(rec => Promise.resolve().then(() => rec.handle.dispose())),
      ...(this.llmFiber === undefined ? [] : [Promise.resolve().then(() => this.llmFiber?.dispose())]),
    ])
    this.llmFiber = undefined
    failures.push(...teardownResults
      .filter((result): result is PromiseRejectedResult => result.status === 'rejected')
      .map(result => result.reason as unknown))
    if (failures.length === 1) throw failures[0]
    if (failures.length > 1) throw new AggregateError(failures, 'SDK server teardown failed')
    return {}
  }

  /**
   * Dispatch one incoming JSON-RPC request to its typed handler. Throws (→ a
   * JSON-RPC error response) on an unknown method.
   * @param method - the JSON-RPC method name.
   * @param params - the raw params object from the wire.
   * @returns the handler's result, to be serialized as the response.
   */
  async handleRequest(method: string, params: Record<string, unknown> | undefined): Promise<unknown> {
    switch (method) {
      case 'initialize':
        return this.initialize(params as unknown as InitializeParams)
      case 'session/prompt':
        return this.prompt(params as unknown as SessionPromptParams)
      case 'session/list':
        return this.listSessions()
      case 'session/rename':
        return this.renameSession(params as unknown as SessionRenameParams)
      case 'session/delete':
        return this.deleteSession(params as unknown as SessionDeleteParams)
      case 'session/create':
        return this.createNewSession(params as unknown as SessionCreateParams)
      case 'session/messages':
        return this.readSessionMessages(params as unknown as SessionMessagesParams)
      case 'task/cancel':
        return this.cancelTask(params as unknown as TaskCancelParams)
      case 'file/list':
        return this.listFiles(params as unknown as FileListParams)
      case 'file/read':
        return this.readTextFile(params as unknown as FileTextParams)
      case 'file/write':
        return this.writeTextFile(params as unknown as FileTextParams)
      case 'file/download':
        return this.downloadFile(params as unknown as FileDownloadParams)
      case 'user/answer':
        return this.answerUserQuestion(params as unknown as { requestId?: unknown; answers?: unknown })
      case 'model/list':
        return this.getModelInfo()
      case 'model/set':
        return this.setModel(params as unknown as ModelSetParams)
      case 'terminal/exec':
        return this.terminalExec(params as unknown as TerminalExecParams)
      case 'session/fork':
        return this.forkSession(params as unknown as SessionForkParams)
      case 'session/trace':
        return this.readSessionTrace(params as unknown as SessionMessagesParams)
      case 'ocr/image':
        return this.ocrImage(params as unknown as OcrImageParams)
      case 'shutdown':
        return this.shutdown()
      default:
        throw new Error(`unknown DeepSeek Harness SDK runtime method: ${method}`)
    }
  }

  /**
   * Harness助手扩展：列出所有持久化会话（跨工作区），标题取日志最新
   * session/title 事件。与官方 Web 端共享同一会话库（~/.dsh/sessions）。
   */
  private async listSessions(): Promise<SessionListEntry[]> {
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error('session persistence is not configured')
    const snapshots = await persistence.listSnapshots()
    const sessions = await Promise.all(snapshots.map(async (snapshot): Promise<SessionListEntry> => {
      let title: string | null = null
      let updatedAt: number | null = null
      try {
        const { events } = await persistence.inspect(snapshot.header.id)
        const folded = foldTitleFromEvents(events)
        if (folded !== undefined) {
          title = folded.title
          updatedAt = folded.updatedAt
        }
      } catch {
        // 单个会话读取失败（损坏/压缩不匹配）不阻断整个列表
      }
      return {
        id: String(snapshot.header.id),
        title,
        cwd: snapshot.header.cwd ?? null,
        createdAt: snapshot.header.createdAt ?? null,
        updatedAt,
      }
    }))
    return sessions
  }

  /**
   * Harness助手扩展：重命名持久化会话。以 append-only 写入一条
   * session/title 事件（source: user，pin 住标题），Web 端折叠时可见。
   */
  private async renameSession(params: SessionRenameParams): Promise<{ ok: true }> {
    const { sessionId, title } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    if (typeof title !== 'string') throw new TypeError('title must be a string')
    const normalized = normalizeTitleText(title)
    if (normalized.length === 0) throw new SessionTitleInvalidError('title normalizes to empty')
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error('session persistence is not configured')
    // P1 修复：会话正在运行任务时拒绝改名（此前 dispose live 会话会直接杀掉运行中的任务）。
    // 空闲的 live 会话才 dispose（其持久化部分已 flush），避免与持久化写冲突。
    await this.ensureNotRunning(sessionId)
    await this.disposeSessionIfLive(sessionId)
    const id = SessionId(sessionId)
    const loaded = await persistence.load(id)
    const lastSeq = loaded.events.at(-1)?.seq ?? 0
    const event = {
      type: 'session/title',
      seq: lastSeq + 1,
      time: Date.now(),
      data: { title: normalized, messageSeqs: [], source: { kind: 'user' } },
    } as unknown as SessionEvent
    await persistence.append(id, [event])
    // A 方案（用户 2026-08-15 拍板）：同步 Web 端投影缓存，dsh web 重启后呈现
    await updateProjectionCache([{ type: 'rename', id: sessionId, title: normalized }])
    return { ok: true }
  }

  /**
   * Harness助手扩展：删除持久化会话。定位其独立产物目录（JSONL 后端为
   * root/<workspace>/session-<id>/）后整目录删除，Web 端同库同步消失。
   */
  private async deleteSession(params: SessionDeleteParams): Promise<{ ok: true }> {
    const { sessionId } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error('session persistence is not configured')
    // P1 修复：会话正在运行任务时拒绝删除（此前会 dispose 杀掉运行中的任务）
    await this.ensureNotRunning(sessionId)
    await this.disposeSessionIfLive(sessionId)
    const snapshots = await persistence.listSnapshots()
    const snapshot = snapshots.find(candidate => String(candidate.header.id) === sessionId)
    if (snapshot === undefined) throw new Error(`session not found: ${sessionId}`)
    const location = persistence.locate(snapshot.header)
    if (location === undefined) {
      throw new Error('session persistence backend does not expose per-session artifacts')
    }
    await rm(dirname(location.path), { recursive: true, force: true })
    // A 方案（用户 2026-08-15 拍板）：同步从 Web 端投影缓存移除该会话
    await updateProjectionCache([{ type: 'delete', id: sessionId }])
    return { ok: true }
  }

  /**
   * Harness助手扩展：新建一个全新会话（可指定工作区 cwd，缺省用默认工作区）。
   * 返回新会话 id；App 端续接即填此 id。
   */
  private async createNewSession(params: SessionCreateParams): Promise<{ sessionId: string; cwd: string }> {
    const { cwd } = params
    if (cwd !== undefined && typeof cwd !== 'string') throw new TypeError('cwd must be a string')
    if (this.shuttingDown) throw new Error('SDK server is shutting down')
    const sessionId = `s_${randomUUID().replace(/-/g, '')}`
    const cwdResolved = resolve(cwd ?? this.cwd)
    const agentOptions = {
      provider: this.provider,
      model: this.model,
      ...this.maxTokens === undefined ? {} : { maxTokens: this.maxTokens },
    }
    const handle = await this.ctx.agents.create({
      sessionId: SessionId(sessionId),
      meta: { cwd: cwdResolved },
      agentOptions,
    })
    this.sessions.set(sessionId, { handle })
    return { sessionId, cwd: cwdResolved }
  }

  /** Harness助手扩展：读取会话历史（人类可见 transcript）。
   * 只取 surfaceOp='append' 的 user/message 与 assistant/message（官方语义：
   * replace 是模型视图影子，会抹掉用户已看到的内容，不纳入 transcript）。
   */
  private async readSessionMessages(params: SessionMessagesParams): Promise<SessionMessageEntry[]> {
    const { sessionId } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error('session persistence is not configured')
    // P1 修复：读历史是只读操作，绝不 dispose live 会话（此前会杀掉正在运行的任务）
    const loaded = await persistence.load(SessionId(sessionId))
    const entries: SessionMessageEntry[] = []
    for (const event of loaded.events) {
      const raw = event as unknown as { type: string; time: number; surfaceOp?: unknown; data: { content?: unknown; message?: { content?: unknown } } }
      if (raw.surfaceOp !== 'append') continue // 只保留人类 transcript 的 append 事件
      if (raw.type === 'user/message') {
        const text = contentText(raw.data.content)
        if (text.length > 0) entries.push({ role: 'user', content: text, time: raw.time })
      } else if (raw.type === 'assistant/message') {
        const text = contentText(raw.data.message?.content)
        if (text.length > 0) entries.push({ role: 'assistant', content: text, time: raw.time })
      }
    }
    return entries
  }

  /** Harness助手扩展：中断指定会话正在运行的任务（对齐 Web 端"停止生成"）。 */
  private async cancelTask(params: TaskCancelParams): Promise<{ ok: true }> {
    const { sessionId } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    const agent = this.ctx.agents.get(SessionId(sessionId))
    if (agent === undefined) throw new Error(`no live task for session: ${sessionId}`)
    agent.cancel({ kind: 'user' })
    return { ok: true }
  }

  /** v0.6.4：手机端 ask_user_question —— 提交答案，唤醒挂起的提问（agent loop 继续）。 */
  private async answerUserQuestion(params: { requestId?: unknown; answers?: unknown }): Promise<{ ok: true }> {
    const requestId = String(params.requestId ?? '')
    const pending = this.pendingQuestions.get(requestId)
    if (pending === undefined) throw new Error(`no pending user question: ${requestId}`)
    clearTimeout(pending.timer)
    this.pendingQuestions.delete(requestId)
    pending.resolve({ answers: params.answers })
    return { ok: true }
  }

  /**
   * 解析并校验工作区路径（F12 安全边界：只允许访问 cwd 内）。
   * 两道校验：① 词法（resolve 后相对路径不得逃逸）；② realpath（P1 修复：
   * 防 symlink/junction 指向工作区外——此前只做词法校验，符号链接可越界）。
   * @returns 解析后的绝对路径。
   */
  private async assertInsideCwd(cwd: string, path: string): Promise<string> {
    const base = resolve(cwd)
    const target = resolve(base, path)
    const rel = relative(base, target)
    if (rel !== '' && (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel))) {
      throw new Error(`path escapes workspace: ${path}`)
    }
    return this.realpathWithin(base, target, path)
  }

  /**
   * 逐级 realpath 校验：目标存在 → 直接校验；不存在（新建/深层路径）→
   * 向上找最近存在祖先校验后拼回剩余路径，防止 symlink 逃逸工作区。
   */
  private async realpathWithin(base: string, target: string, originalPath: string): Promise<string> {
    const baseReal = await realpath(base)
    let probe = target
    const tail: string[] = []
    for (;;) {
      let real: string
      try {
        real = await realpath(probe)
      } catch {
        const parent = dirname(probe)
        if (parent === probe) break
        tail.unshift(basename(probe))
        probe = parent
        continue
      }
      const rel = relative(baseReal, real)
      if (rel !== '' && (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel))) {
        throw new Error(`path escapes workspace (symlink): ${originalPath}`)
      }
      return tail.length === 0 ? real : join(real, ...tail)
    }
    throw new Error(`path escapes workspace: ${originalPath}`)
  }

  /** Harness助手扩展：列工作区目录。 */
  private async listFiles(params: FileListParams): Promise<{ cwd: string; path: string; entries: FileEntry[] }> {
    const { cwd, path } = params
    if (typeof cwd !== 'string' || cwd.length === 0) throw new TypeError('cwd must be a non-empty string')
    const dir = await this.assertInsideCwd(cwd, path ?? '.')
    const items = await readdir(dir, { withFileTypes: true })
    const entries: FileEntry[] = await Promise.all(items.map(async (item) => {
      let size = 0
      let mtime = 0
      if (item.isFile()) {
        try {
          const info = await stat(resolve(dir, item.name))
          size = info.size
          mtime = info.mtimeMs
        } catch {
          // 单个文件 stat 失败不影响列表
        }
      }
      return {
        name: item.name,
        type: item.isDirectory() ? 'dir' as const : 'file' as const,
        size,
        mtime,
      }
    }))
    // 目录在前、按名称排序
    entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1))
    return { cwd: resolve(cwd), path: resolve(dir), entries }
  }

  /** Harness助手扩展：读取文本文件（预览，≤256KB，超限截断）。 */
  private async readTextFile(params: FileTextParams): Promise<{ path: string; content: string; truncated: boolean }> {
    const { cwd, path } = params
    if (typeof cwd !== 'string' || cwd.length === 0) throw new TypeError('cwd must be a non-empty string')
    if (typeof path !== 'string' || path.length === 0) throw new TypeError('path must be a non-empty string')
    const target = await this.assertInsideCwd(cwd, path)
    const buffer = await readFile(target)
    const MAX = 256 * 1024
    const truncated = buffer.length > MAX
    const content = buffer.subarray(0, MAX).toString('utf8')
    return { path: resolve(target), content, truncated }
  }

  /** Harness助手扩展：写入文本文件（≤256KB，必须 confirmed=true，F15）。 */
  private async writeTextFile(params: FileTextParams): Promise<{ path: string }> {
    const { cwd, path, content, confirmed } = params
    if (typeof cwd !== 'string' || cwd.length === 0) throw new TypeError('cwd must be a non-empty string')
    if (typeof path !== 'string' || path.length === 0) throw new TypeError('path must be a non-empty string')
    if (confirmed !== true) throw new Error('file.write requires explicit confirmation (approval=ask)')
    if (typeof content !== 'string') throw new TypeError('content must be a string')
    const MAX = 256 * 1024
    if (Buffer.byteLength(content, 'utf8') > MAX) throw new Error(`content exceeds ${MAX} bytes`)
    const target = await this.assertInsideCwd(cwd, path)
    await writeFile(target, content, 'utf8')
    return { path: resolve(target) }
  }

  /** Harness助手扩展：下载文件（≤10MB，base64 回传，预览图片/下载用）。 */
  private async downloadFile(params: FileDownloadParams): Promise<{
    path: string; name: string; size: number; mime: string; base64: string
  }> {
    const { cwd, path } = params
    if (typeof cwd !== 'string' || cwd.length === 0) throw new TypeError('cwd must be a non-empty string')
    if (typeof path !== 'string' || path.length === 0) throw new TypeError('path must be a non-empty string')
    const target = await this.assertInsideCwd(cwd, path)
    const buffer = await readFile(target)
    const MAX = 10 * 1024 * 1024
    if (buffer.length > MAX) throw new Error(`file exceeds ${MAX} bytes`)
    const name = target.split(sep).pop() ?? 'file'
    return {
      path: resolve(target),
      name,
      size: buffer.length,
      mime: mimeForPath(name),
      base64: buffer.toString('base64'),
    }
  }

  /** 若同 id 会话正在本进程运行任务 → 拒绝（改名/删除前置检查，防杀任务）。 */
  private async ensureNotRunning(sessionId: string): Promise<void> {
    const rec = this.sessions.get(sessionId)
    if (rec?.running === true) {
      throw new Error(`会话正在运行任务，请先停止（task.stop）后再操作: ${sessionId}`)
    }
  }

  // ── v0.7.0：模型切换（model/list + model/set，新会话生效） ─────────────
  // 注意：运行中的会话不热切换模型（dsh agent 无运行时换模型 API）；
  // 修改后 createSession/resume 的 agentOptions 使用新值，旧会话继续用旧模型。
  private async getModelInfo(): Promise<ModelInfo> {
    const llm = this.ctx.get('llm')
    const providers = llm?.listProviders() ?? []
    return {
      provider: this.provider,
      model: this.model,
      maxTokens: this.maxTokens ?? null,
      providers: providers.map(entry => ({ id: entry.id, name: entry.name })),
      modelCandidates: MODEL_CATALOG[this.provider] ?? [],
    }
  }

  private async setModel(params: ModelSetParams): Promise<{ ok: true; provider: string; model: string; maxTokens?: number }> {
    const { provider, model, maxTokens } = params
    if (provider !== undefined) {
      if (typeof provider !== 'string' || provider.length === 0) {
        throw new TypeError('provider must be a non-empty string')
      }
      // 只允许切到已注册路由；deepseek-official 是 initialize 的兜底（无 llm service 时自挂）
      if (!this.hasAdapterFor(provider) && provider !== 'deepseek-official') {
        throw new Error(`no adapter registered for provider "${provider}"`)
      }
      this.provider = provider
    }
    if (model !== undefined) {
      if (typeof model !== 'string' || model.length === 0) {
        throw new TypeError('model must be a non-empty string')
      }
      this.model = model
    }
    if (maxTokens !== undefined) {
      if (maxTokens === null) {
        this.maxTokens = undefined
      } else {
        if (!Number.isSafeInteger(maxTokens) || maxTokens <= 0) {
          throw new TypeError('maxTokens must be a positive safe integer')
        }
        this.maxTokens = maxTokens
      }
    }
    return { ok: true, provider: this.provider, model: this.model, ...(this.maxTokens === undefined ? {} : { maxTokens: this.maxTokens }) }
  }

  // ── v0.7.0：远程终端（terminal/exec，一次性执行 + 返回完整输出） ────────
  private terminalExec(params: TerminalExecParams): Promise<TerminalExecResult> {
    const { cwd, command, confirmed } = params
    if (typeof cwd !== 'string' || cwd.length === 0) throw new TypeError('cwd must be a non-empty string')
    if (typeof command !== 'string' || command.length === 0) throw new TypeError('command must be a non-empty string')
    if (HIGH_RISK_PATTERN.test(command) && confirmed !== true) {
      throw new Error('命令包含高危操作（删除/格式化/关机/清库等），需确认后再执行（confirmed=true）')
    }
    const base = resolve(cwd)
    const MAX_OUT = 256 * 1024
    const TIMEOUT_MS = 120_000
    return new Promise<TerminalExecResult>((resolvePromise) => {
      const startedAt = Date.now()
      const child = spawn(command, { cwd: base, shell: true, windowsHide: true })
      let stdout = ''
      let stderr = ''
      let truncated = false
      let timedOut = false
      const timer = setTimeout(() => {
        timedOut = true
        child.kill('SIGKILL')
      }, TIMEOUT_MS)
      const collect = (buffer: Buffer, target: { value: string }): void => {
        if (truncated) return
        const room = MAX_OUT - Buffer.byteLength(target.value, 'utf8')
        if (room <= 0) {
          truncated = true
          return
        }
        const text = buffer.toString('utf8')
        if (Buffer.byteLength(text, 'utf8') <= room) {
          target.value += text
        } else {
          target.value += text.slice(0, Math.max(0, room))
          truncated = true
        }
      }
      const outSink = { value: stdout }
      const errSink = { value: stderr }
      child.stdout?.on('data', (chunk: Buffer) => collect(chunk, outSink))
      child.stderr?.on('data', (chunk: Buffer) => collect(chunk, errSink))
      child.on('error', (error) => {
        clearTimeout(timer)
        resolvePromise({
          cwd: base, command, exitCode: null, stdout: outSink.value, stderr: `spawn 失败: ${error.message}`,
          truncated, timedOut: false, durationMs: Date.now() - startedAt,
        })
      })
      child.on('close', (code) => {
        clearTimeout(timer)
        resolvePromise({
          cwd: base, command, exitCode: code, stdout: outSink.value, stderr: errSink.value,
          truncated, timedOut, durationMs: Date.now() - startedAt,
        })
      })
    })
  }

  // ── v0.7.0：会话分支（session/fork，官方 fork + resume） ────────────────
  // ctx.sessions.fork 会校验"前缀结束时无开放轮次"，运行中 fork 会失败 → 先提示停止。
  private async forkSession(params: SessionForkParams): Promise<{ sessionId: string; cwd: string }> {
    const { sessionId } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    const rec = await this.getOrCreateSession(sessionId)
    if (rec.running === true) {
      throw new Error('会话正在运行任务，请先停止后再分支')
    }
    const childId = `s_${randomUUID().replace(/-/g, '')}`
    const child = this.ctx.sessions.fork(rec.handle.agent.session, undefined, SessionId(childId))
    // fork 出的子会话 seed 需落盘（onCreated 只建索引，flush 才写事件）
    await this.ctx.sessions.flush(child)
    const agentOptions = {
      provider: this.provider,
      model: this.model,
      ...this.maxTokens === undefined ? {} : { maxTokens: this.maxTokens },
    }
    const handle = await this.ctx.agents.resume({ resumeSessionId: SessionId(childId), agentOptions })
    const cwd = typeof child.header.cwd === 'string' && child.header.cwd.length > 0 ? child.header.cwd : this.cwd
    this.sessions.set(childId, { handle })
    return { sessionId: childId, cwd }
  }

  // ── v0.7.0：轨迹面板（session/trace，结构化时间线） ────────────────────
  private async readSessionTrace(params: SessionMessagesParams): Promise<{ sessionId: string; entries: TraceEntry[] }> {
    const { sessionId } = params
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new TypeError('sessionId must be a non-empty string')
    }
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error('session persistence is not configured')
    const loaded = await persistence.load(SessionId(sessionId))
    const entries: TraceEntry[] = []
    const toolStarts = new Map<string, number>() // callId -> time
    for (const event of loaded.events) {
      const raw = event as unknown as { type: string; seq: number; time: number; data: Record<string, unknown> }
      const type = raw.type
      const data = (raw.data ?? {}) as Record<string, unknown>
      if (type === 'tool/call') {
        const callId = typeof data.callId === 'string' ? data.callId : undefined
        const name = typeof data.name === 'string' ? data.name : '?'
        if (callId !== undefined) toolStarts.set(callId, raw.time)
        const args = typeof data.arguments === 'string' ? data.arguments : ''
        entries.push({
          seq: raw.seq, time: raw.time, type: 'tool', title: `工具 · ${name}`,
          ...(args ? { detail: args.slice(0, 2000) } : {}),
        })
      } else if (type === 'tool/result') {
        const callId = typeof data.callId === 'string' ? data.callId : undefined
        const name = typeof data.name === 'string' ? data.name : '?'
        const start = callId !== undefined ? toolStarts.get(callId) : undefined
        const durationMs = start !== undefined ? Math.max(0, raw.time - start) : undefined
        entries.push({
          seq: raw.seq, time: raw.time, type: 'tool-result', title: `结果 · ${name}`,
          ...(durationMs !== undefined ? { durationMs } : {}),
        })
      } else if (type === 'reasoning-chunks') {
        const chunks = Array.isArray(data.chunks)
          ? (data.chunks as Array<{ text?: unknown }>).map(item => typeof item?.text === 'string' ? item.text : '').join('')
          : ''
        if (chunks.trim().length > 0) {
          entries.push({ seq: raw.seq, time: raw.time, type: 'reasoning', title: '思考', detail: chunks.slice(0, 2000) })
        }
      } else if (type === 'step/start') {
        entries.push({ seq: raw.seq, time: raw.time, type: 'step', title: '步骤开始' })
      } else if (type === 'step/end') {
        entries.push({ seq: raw.seq, time: raw.time, type: 'step-end', title: '步骤结束' })
      } else if (type === 'user/message') {
        const text = contentText(data.content)
        if (text.length > 0) entries.push({ seq: raw.seq, time: raw.time, type: 'user', title: '用户', detail: text.slice(0, 2000) })
      } else if (type === 'assistant/message') {
        const message = data.message as { content?: unknown } | undefined
        const text = contentText(message?.content)
        if (text.length > 0) entries.push({ seq: raw.seq, time: raw.time, type: 'assistant', title: '助手', detail: text.slice(0, 2000) })
      }
    }
    return { sessionId, entries }
  }

  // ── v0.7.0：图片 OCR（ocr/image，Windows.Media.Ocr 免费本地识别） ───────
  // App 上传 base64 → 临时文件 → powershell ocr.ps1（WinRT）→ 返回文本。
  private async ocrImage(params: OcrImageParams): Promise<{ text: string; engine: string }> {
    const { base64 } = params
    if (typeof base64 !== 'string' || base64.length === 0) throw new TypeError('base64 must be a non-empty string')
    if (base64.length > 15 * 1024 * 1024) throw new Error('image exceeds 15MB')
    const name = typeof params.name === 'string' && params.name.trim().length > 0 ? params.name.trim() : 'image.png'
    const safeName = name.replace(/[^\w.-]/g, '_')
    const dir = await mkdtemp(join(tmpdir(), 'harness-ocr-'))
    const imgPath = join(dir, safeName)
    try {
      await writeFile(imgPath, Buffer.from(base64, 'base64'))
      const scriptPath = join(dirname(fileURLToPath(import.meta.url)), 'scripts', 'ocr.ps1')
      const result = spawnSync(
        'powershell',
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, imgPath],
        { timeout: 30_000, encoding: 'utf8', windowsHide: true, maxBuffer: 4 * 1024 * 1024 },
      )
      const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`.trim()
      if (result.status !== 0 || output.includes('__OCR_UNSUPPORTED__')) {
        throw new Error(`本机不支持 Windows OCR（需 Windows 10+ 且安装对应语言包）${output.includes('__OCR_UNSUPPORTED__') ? '' : `: ${output.slice(0, 300)}`}`)
      }
      if (output.includes('__OCR_ERROR__')) {
        throw new Error(`OCR 识别失败: ${output.slice(0, 300)}`)
      }
      const text = output
        .split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .join('\n')
      if (text.length === 0) return { text: '', engine: 'windows-ocr' }
      return { text, engine: 'windows-ocr' }
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  }

  /**
   * v0.6.8：定位会话文件 → 校验/修复（自愈兜底）。损坏已自动重建返回 true，
   * 文件健康返回 true（无需动），无法定位/修复失败返回 false。
   */
  private async repairSessionIfNeeded(sessionId: string): Promise<boolean> {
    try {
      const path = await locateSessionFile(sessionId)
      if (path === undefined) return false
      const result = await repairSessionFile(path)
      if (!result.ok) {
        console.error(`[self-heal] 修复失败: ${result.message}`)
        return false
      }
      if (result.repaired) console.log(`[self-heal] ${result.message}`)
      return true
    } catch (error) {
      console.error('[self-heal] 修复过程异常:', error instanceof Error ? error.message : String(error))
      return false
    }
  }

  /** 若同 id 会话正在本进程 live，先 dispose 并移出会话表。 */
  private async disposeSessionIfLive(sessionId: string): Promise<void> {
    const rec = this.sessions.get(sessionId)
    if (rec === undefined) return
    this.sessions.delete(sessionId)
    await Promise.resolve(rec.handle.dispose())
  }

  private async getOrCreateSession(sessionId: string): Promise<SessionRecord> {
    if (this.shuttingDown) throw new Error('SDK server is shutting down')
    const existing = this.sessions.get(sessionId)
    if (existing) return existing
    const pending = this.sessionCreations.get(sessionId)
    if (pending) return pending
    const creation = this.createSession(sessionId)
    this.sessionCreations.set(sessionId, creation)
    void creation.then(
      () => { this.sessionCreations.delete(sessionId) },
      () => { this.sessionCreations.delete(sessionId) },
    )
    return creation
  }

  private async createSession(sessionId: string): Promise<SessionRecord> {
    const id = SessionId(sessionId)
    const agentOptions = {
      provider: this.provider,
      model: this.model,
      ...this.maxTokens === undefined ? {} : { maxTokens: this.maxTokens },
    }
    // Harness助手增强：磁盘已有该会话的持久化日志 → 跨进程恢复（resume），
    // 保留完整对话历史与记忆；无日志才创建全新会话。
    // 低优先级优化：直接用 persistence.load 探测（此前全量 listSnapshots 扫目录，O(n)/会话）。
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence !== undefined) {
      let lastSeq = -1
      let exists = false
      try {
        const loaded = await persistence.load(id)
        lastSeq = loaded.events.at(-1)?.seq ?? -1
        exists = true
      } catch {
        // v0.6.8：不存在（或损坏）→ 先尝试自愈，成功则重新探测；仍失败走 create
        const repaired = await this.repairSessionIfNeeded(sessionId)
        if (repaired) {
          try {
            const loaded = await persistence.load(id)
            lastSeq = loaded.events.at(-1)?.seq ?? -1
            exists = true
          } catch {
            // 修复后仍读不出 → 视为新会话
          }
        }
        // 未修复：不存在（或损坏且无法修）→ 走 create（损坏文件 create 会抛 id collision，报错更明确）
      }
      if (exists) {
        const handle = await this.ctx.agents.resume({ resumeSessionId: id, agentOptions })
        const rec: SessionRecord = { handle, memoryLastSeq: lastSeq }
        this.sessions.set(sessionId, rec)
        return rec
      }
    }
    const handle = await this.ctx.agents.create({
      sessionId: id,
      meta: { cwd: this.cwd },
      agentOptions,
    })
    const rec: SessionRecord = { handle }
    this.sessions.set(sessionId, rec)
    return rec
  }

  private hasAdapterFor(provider: string): boolean {
    return this.ctx.get('llm')?.listProviders().some(entry => entry.id === provider) ?? false
  }
}

/** 按扩展名映射 MIME（file.download 预览用，未知回 application/octet-stream）。 */
function mimeForPath(name: string): string {
  const dot = name.lastIndexOf('.')
  const ext = dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
  const map: Record<string, string> = {
    txt: 'text/plain', md: 'text/markdown', json: 'application/json',
    py: 'text/x-python', ts: 'text/x-typescript', js: 'text/javascript',
    html: 'text/html', css: 'text/css', java: 'text/x-java-source',
    kt: 'text/x-kotlin', xml: 'text/xml', yml: 'text/yaml', yaml: 'text/yaml',
    toml: 'text/plain', properties: 'text/plain', log: 'text/plain', csv: 'text/csv',
    pdf: 'application/pdf', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
    gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml', bmp: 'image/bmp',
    mp4: 'video/mp4', mp3: 'audio/mpeg', wav: 'audio/wav',
    zip: 'application/zip', apk: 'application/vnd.android.package-archive',
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  }
  return map[ext] ?? 'application/octet-stream'
}
