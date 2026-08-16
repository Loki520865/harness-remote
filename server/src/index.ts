/**
 * Harness助手 · 转发服务器（传话筒）+ M3 账号体系
 *
 * M1/M2 职责：device.online 注册 / chat.send 转发 / 结果广播 / 30s 心跳。
 * M3 新增：
 *  - HTTP API：/api/send_code /api/register /api/login /api/me（邮箱验证码 + 邀请码）
 *  - WS 鉴权：/app?token= 与 /device?token=（auth_required 时强制）
 *  - 防多号：一账号一手机一电脑，同端新连接挤掉旧连接；桌面端绑定 device_id 禁换绑
 *  - 私密加密：config.private_enabled 开关（默认关，不暴露前端，会员预留）
 *
 * 不碰 Key、不调 LLM、不承担算力（D5）。
 * 用法: npm run dev（默认 :8787）
 */

import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { URL } from 'node:url'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { WebSocketServer, WebSocket } from 'ws'
import { loadConfig, SELF_DIR } from './config.js'
import { AuthStore } from './auth.js'
import { scheduleDeviceOfflineAlert, cancelDeviceOfflineAlert, notifyServerStarted } from './alert.js'

const PORT = Number(process.env.PORT ?? 8787)
const cfg = loadConfig()
const auth = new AuthStore(cfg)

interface DeviceConn {
  socket: WebSocket
  name?: string
  /** P0 修复（账号↔设备归属）：桌面连接所属账号 email（auth_required 时必填）。 */
  owner?: string
}

// device_id -> 桌面伴侣连接（反向隧道，单连接）
const devices = new Map<string, DeviceConn>()
// 所有手机端连接：ws -> 所属账号 email（auth_required=false 兼容模式为 undefined）
const apps = new Map<WebSocket, string>()
// 手机 token -> app 连接（防多号：同 token 仅一个在线）
const appByToken = new Map<string, WebSocket>()

function send(ws: WebSocket, obj: unknown): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj))
  }
}

function close(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason)
  } catch { /* ignore */ }
}

/** 连接所属账号 email（手机/桌面都能查；未鉴权/兼容模式返回 undefined）。 */
function ownerOf(ws: WebSocket): string | undefined {
  const appOwner = apps.get(ws)
  if (appOwner !== undefined) return appOwner
  for (const conn of devices.values()) {
    if (conn.socket === ws) return conn.owner
  }
  return undefined
}

/** 设备事件 → 广播给所有手机端（P0 修复：只广播给该设备所属账号的手机，防跨账号事件泄露）。 */
function broadcastToApps(obj: unknown, ownerEmail?: string): void {
  for (const [app, email] of apps) {
    if (ownerEmail === undefined || email === undefined || email === ownerEmail) send(app, obj)
  }
}

// ---------------- HTTP API（/api/*） ----------------

/** 请求体上限：64KB（防内存耗尽；正常 API 请求远小于此）。 */
const MAX_BODY_BYTES = 64 * 1024

/** 读取请求体；超过上限返回 null（调用方回 413，多余数据丢弃不累积）。 */
function readBody(req: IncomingMessage): Promise<Record<string, unknown> | null> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = []
    let size = 0
    let over = false
    req.on('data', (c: Buffer) => {
      size += c.length
      if (size > MAX_BODY_BYTES) {
        over = true
        return // 超限后只丢弃、不累积（内存安全；不 destroy，保证能回 413 响应）
      }
      chunks.push(c)
    })
    req.on('end', () => {
      if (over) {
        resolve(null)
        return
      }
      try {
        const raw = Buffer.concat(chunks).toString('utf8')
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
    req.on('error', () => resolve(null))
  })
}

function json(res: { writeHead: (code: number, h: Record<string, string>) => void; end: (s: string) => void }, status: number, obj: unknown): void {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(obj))
}

async function handleApi(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const body = await readBody(req)
  if (body === null) {
    json(res, 413, { ok: false, error: '请求体过大' })
    return
  }

  const str = (v: unknown): string => (typeof v === 'string' ? v : '')
  const email = str(body.email).trim().toLowerCase()

  if (url.pathname === '/api/notice') {
    json(res, 200, { ok: true, notice: cfg.notice ?? '' })
    return
  }

  if (url.pathname === '/api/version') {
    // App 检查更新（照晨曦AI）：读与 index.js 同目录的 version.json（发布时更新）
    // 版本信息公开（APK 下载点本身公开），不鉴权
    const v = { version: 1, name: '0.0.0', url: '', note: '' }
    try {
      const vf = join(SELF_DIR, 'version.json')
      if (existsSync(vf)) Object.assign(v, JSON.parse(readFileSync(vf, 'utf8')))
    } catch {
      // 版本文件缺失/损坏 → 默认无更新
    }
    json(res, 200, v)
    return
  }

  if (url.pathname === '/api/send_code') {
    const inviteCode = str(body.invite_code)
    const r = await auth.sendCode(email, inviteCode || undefined)
    json(res, r.ok ? 200 : 400, r)
    return
  }

  if (url.pathname === '/api/register') {
    const r = auth.register(email, str(body.code), str(body.password), str(body.phone_id) || null)
    if (!r.ok) { json(res, 400, r); return }
    console.log(`[api] register ok → ${email}`)
    json(res, 200, { ok: true, email, phone_token: r.phone_token, desktop_token: r.desktop_token })
    return
  }

  if (url.pathname === '/api/login') {
    // 照晨曦AI：邮箱+密码登录（验证码仅用于注册/找回）
    const device = str(body.device) === 'desktop' ? 'desktop' : 'phone'
    const r = auth.login(email, str(body.password), device, str(body.device_id) || null)
    if (!r.ok) { json(res, 400, r); return }
    console.log(`[api] login ok → ${email} (${device})`)
    json(res, 200, { ok: true, email, token: r.token, desktop_id: r.desktop_id ?? null })
    return
  }

  if (url.pathname === '/api/send_reset_code') {
    const r = await auth.sendResetCode(email)
    json(res, r.ok ? 200 : 400, r)
    return
  }

  if (url.pathname === '/api/reset_password') {
    const r = auth.resetPassword(email, str(body.code), str(body.password))
    if (!r.ok) { json(res, 400, r); return }
    console.log(`[api] reset password ok → ${email}`)
    json(res, 200, { ok: true })
    return
  }

  if (url.pathname === '/api/change_password') {
    // 需登录：token 校验（phone/desktop 任一）
    const token = str(body.token)
    const user = auth.validateToken('phone', token) ?? auth.validateToken('desktop', token)
    if (!user) { json(res, 401, { ok: false, error: 'token 无效' }); return }
    const r = auth.changePassword(user.email, str(body.old_password), str(body.new_password))
    if (!r.ok) { json(res, 400, r); return }
    console.log(`[api] change password ok → ${user.email}`)
    json(res, 200, { ok: true })
    return
  }

  if (url.pathname === '/api/me') {
    const token = str(body.token)
    const user = auth.validateToken('phone', token) ?? auth.validateToken('desktop', token)
    if (!user) { json(res, 401, { ok: false, error: 'token 无效' }); return }
    json(res, 200, {
      ok: true,
      email: user.email,
      desktop_id: user.desktop_id,
      desktop_token: user.desktop_token,
      private_enabled: cfg.private_enabled,
    })
    return
  }

  json(res, 404, { ok: false, error: 'not found' })
}

const httpServer = createServer((req, res) => {
  if (req.url && req.url.startsWith('/api/')) {
    void handleApi(req, res)
    return
  }
  res.writeHead(200, { 'content-type': 'text/plain' })
  res.end('harness-relay alive\n')
})

const wss = new WebSocketServer({ noServer: true })

// ---------------- WS 连接（/app?token= /device?token=） ----------------

wss.on('connection', (ws: WebSocket, _req: IncomingMessage, meta: { role: 'device' | 'phone'; token: string }) => {
  // ---- M3 鉴权 ----
  let authedUser: { email: string } | null = null
  if (cfg.auth_required) {
    const role = meta.role === 'device' ? 'desktop' : 'phone'
    const user = auth.validateToken(role, meta.token)
    if (!user) {
      console.log(`[auth] 拒绝无 token 的 ${meta.role} 连接`)
      close(ws, 1008, 'unauthorized')
      return
    }
    authedUser = { email: user.email }
    if (meta.role === 'phone') {
      // 防多号：同 phone_token 已在线 → 挤掉旧连接
      const old = appByToken.get(meta.token)
      if (old && old !== ws && old.readyState === WebSocket.OPEN) {
        console.log(`[auth] 手机重复登录，踢掉旧连接（${user.email}）`)
        close(old, 4001, 'duplicate phone login')
      }
      appByToken.set(meta.token, ws)
    }
  }

  if (meta.role === 'phone') {
    apps.set(ws, authedUser?.email ?? '')
    console.log(`[app] 手机端接入（已鉴权），当前 ${apps.size} 个`)
    for (const [deviceId, conn] of devices) {
      // P0 修复：只推送本账号的设备
      if (!cfg.auth_required || conn.owner === undefined || conn.owner === authedUser?.email) {
        send(ws, { op: 'device.online', device_id: deviceId, name: conn.name ?? deviceId })
      }
    }
  } else {
    console.log('[device] 桌面伴侣接入（等待 device.online 注册）')
  }

  ws.on('message', (raw) => {
    let msg: Record<string, unknown>
    try {
      msg = JSON.parse(raw.toString())
    } catch {
      return
    }
    const op = String(msg.op ?? '')
    const deviceId = String(msg.device_id ?? '')

    if (op === 'device.online') {
      if (cfg.auth_required) {
        const user = auth.validateToken('desktop', meta.token)
        if (!user) {
          close(ws, 1008, 'unauthorized')
          return
        }
        if (!deviceId) {
          close(ws, 1008, 'device_id required')
          return
        }
        // P0 修复（device_id 抢占防护）：该 device_id 已绑定其他账号 → 拒绝
        if (auth.isDeviceTakenByOther(user.email, deviceId)) {
          console.log(`[auth] 拒绝抢占：${deviceId} 已绑定其他账号（${user.email} 尝试）`)
          send(ws, { op: 'error', message: '该设备 ID 已被其他账号绑定' })
          close(ws, 1008, 'device taken by another account')
          return
        }
        // 桌面绑定：首次绑定，之后禁换绑（防多号）
        if (user.desktop_id !== null && user.desktop_id !== deviceId) {
          console.log(`[auth] 拒绝换绑：${user.email} 已绑定 ${user.desktop_id}，本次 ${deviceId}`)
          send(ws, { op: 'error', message: '该账号已绑定其它电脑（防多号），请在已绑定电脑登录' })
          close(ws, 1008, 'device bound to another id')
          return
        }
        // 首次上线自动绑定（register 后 desktop_id 为 null）
        if (user.desktop_id === null) {
          auth.bindDesktop(user.email, deviceId)
          console.log(`[auth] 桌面首次上线绑定: ${user.email} → ${deviceId}`)
        }
        console.log(`[auth] 桌面在线: ${user.email} (${deviceId})`)
      }
      // BUG-3 修复（无条件生效，不依赖 auth_required）：同 device_id 新连接踢旧连接，
      // 防两个桌面伴侣同时注册抢转发（旧进程残留 → 手机指令被转发给旧代码/双写会话）
      const prevDevice = devices.get(deviceId)
      if (prevDevice && prevDevice.socket !== ws && prevDevice.socket.readyState === WebSocket.OPEN) {
        console.log(`[device] 重复上线，踢掉旧连接（${deviceId}）`)
        close(prevDevice.socket, 4001, 'duplicate device')
      }
      // P0 修复：记录设备归属账号，转发/广播时校验
      const deviceOwner = authedUser?.email ?? prevDevice?.owner
      devices.set(deviceId, { socket: ws, name: msg.name ? String(msg.name) : undefined, owner: deviceOwner })
      console.log(`[device] 注册 ${deviceId} (${msg.name ?? '未命名'})`)
      // v0.6.3：设备上线 → 取消未触发的离线告警
      cancelDeviceOfflineAlert(deviceId)
      send(ws, { op: 'device.online.ack', device_id: deviceId })
      broadcastToApps({ op: 'device.online', device_id: deviceId, name: msg.name ?? deviceId }, deviceOwner)
      return
    }

    const FORWARD_TO_DEVICE = new Set([
      'chat.send', 'session.list', 'session.rename', 'session.delete', 'session.create',
      'session.messages',
      'task.stop', 'task.list',
      'file.list', 'file.read', 'file.write', 'file.download',
      'user.answer', // v0.6.4：手机提交 ask_user_question 答案 → 桌面伴侣
    ])

    if (FORWARD_TO_DEVICE.has(op)) {
      const target = devices.get(deviceId)
      if (!target) {
        send(ws, { op: 'error', mid: msg.mid, message: `device ${deviceId} 不在线` })
        return
      }
      // P0 修复（越权防护）：手机只能向自己账号的设备发指令（读历史/文件/执行任务同理）
      const appOwner = apps.get(ws)
      if (cfg.auth_required && appOwner !== undefined && target.owner !== undefined && appOwner !== target.owner) {
        console.log(`[auth] 越权拦截：${appOwner} 尝试控制 ${target.owner} 的设备 ${deviceId} (${op})`)
        send(ws, { op: 'error', mid: msg.mid, message: '无权控制该设备（设备归属其他账号）' })
        return
      }
      // 全字段转发（P1 修复：此前 chat.send 只挑 4 个字段，丢未来扩展字段）
      send(target.socket, { ...msg, device_id: deviceId })
      console.log(`[relay] ${op} → ${deviceId}${op === 'chat.send' ? ` (mid=${msg.mid})` : ''}`)
      return
    }

    const BROADCAST_TO_APP = new Set([
      'event', 'session.status', 'run.done', 'run.error', 'chat.ack',
      'session.list.result', 'session.list.error',
      'session.rename.result', 'session.rename.error',
      'session.delete.result', 'session.delete.error',
      'session.create.result', 'session.create.error',
      'session.messages.result', 'session.messages.error',
      'task.stop.result', 'task.stop.error', 'task.list.result',
      'file.list.result', 'file.list.error',
      'file.read.result', 'file.read.error',
      'file.write.result', 'file.write.error',
      'file.download.result', 'file.download.error',
      'user.question', 'user.answer.ack', 'user.answer.error', // v0.6.4：ask_user_question 交互
    ])

    if (BROADCAST_TO_APP.has(op)) {
      // P0 修复：设备回传的结果只广播给该设备所属账号的手机
      broadcastToApps(msg, ownerOf(ws))
      return
    }

    if (op === 'ping') {
      send(ws, { op: 'pong' })
      return
    }
  })

  ;(ws as WebSocket & { isAlive?: boolean }).isAlive = true
  ws.on('pong', () => {
    ;(ws as WebSocket & { isAlive?: boolean }).isAlive = true
    // BUG-1 排查：记录 pong 到达（每 30s 心跳一次；只打简短日志）
    console.log(`[conn] ${meta.role} pong`)
  })

  ws.on('close', (code, reason) => {
    console.log(`[conn] ${meta.role} 连接关闭 code=${code} reason=${reason?.toString() ?? ''}`)
    apps.delete(ws)
    for (const [token, w] of appByToken) {
      if (w === ws) {
        appByToken.delete(token)
        console.log('[auth] 手机下线（token 释放）')
      }
    }
    for (const [id, conn] of devices) {
      if (conn.socket === ws) {
        devices.delete(id)
        console.log(`[device] 下线 ${id}`)
        broadcastToApps({ op: 'device.offline', device_id: id }, conn.owner)
        // v0.6.3：设备下线 → 60s 后仍未重连则邮件告警（已重连自动取消）
        scheduleDeviceOfflineAlert(cfg, id, conn.name ?? id, () => devices.has(id))
        break
      }
    }
  })
})

// 心跳保活：30s ping，未回 pong terminate（防半开假死；M3 定期验在线，防多号留位）
const HEARTBEAT_MS = 30_000
setInterval(() => {
  for (const client of wss.clients) {
    const c = client as WebSocket & { isAlive?: boolean }
    if (c.isAlive === false) {
      c.terminate()
      continue
    }
    c.isAlive = false
    c.ping()
  }
}, HEARTBEAT_MS)

httpServer.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  let role: 'device' | 'phone'
  if (url.pathname === '/device') role = 'device'
  else if (url.pathname === '/app') role = 'phone'
  else {
    socket.destroy()
    return
  }
  // P1 修复（token 泄露面）：优先读自定义头 x-dsh-token（新客户端），
  // query 兜底兼容旧客户端。wss 传输加密，query 方式仅 nginx 日志会残留。
  const headerToken = String(req.headers['x-dsh-token'] ?? '').trim()
  const queryToken = url.searchParams.get('token') ?? ''
  const token = headerToken || queryToken
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req, { role, token })
  })
})

const HOST = process.env.HOST ?? '0.0.0.0'

httpServer.listen(PORT, HOST, () => {
  console.log(`harness-relay 已启动: ws://${HOST}:${PORT}  (/device /app)`)
  console.log(`  auth_required=${cfg.auth_required} private_enabled=${cfg.private_enabled} 邀请码=${cfg.invite_codes.length} 个`)
  // v0.6.3：服务重启邮件通知（异步，失败只记日志）
  notifyServerStarted(cfg)
})
