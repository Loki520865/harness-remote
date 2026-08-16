/**
 * Harness助手 · 桌面伴侣本地面板（M1.8 + v0.6.8 登录页）
 *
 * 只监听 127.0.0.1（不对公网开放），提供：
 *   GET  /           状态页（HTML，未登录时显示登录表单）
 *   GET  /events     SSE 事件流（连接状态/转发事件/日志）
 *   GET  /api/status 登录/连接状态（前端首屏判断）
 *   POST /api/login  桌面登录（邮箱+密码+可选 API Key）→ 写绑定文件 → onLogin
 */
import { createServer, type ServerResponse, type IncomingMessage } from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deviceLogin, loadBound } from './login.ts'

interface PanelEvent {
  ts: number
  type: string
  data: Record<string, unknown>
}

const MAX_HISTORY = 500

export class LocalPanel {
  private readonly port: number
  private readonly clients = new Set<ServerResponse>()
  private history: PanelEvent[] = []
  private server?: ReturnType<typeof createServer>
  private html: string
  private readonly deviceId: string
  private readonly relayUrl: string
  private readonly apiKeyConfigured: boolean
  /** v0.6.8：登录成功回调（tunnel-run 用它重建隧道） */
  private readonly onLogin?: (info: { token: string; deviceId: string; email: string }) => void

  constructor(options: {
    port?: number
    htmlFile?: string
    /** 展示用静态信息（设备 ID / 服务器地址） */
    deviceId?: string
    relayUrl?: string
    /** v0.6.8：启动时是否已配置 API Key（面板状态显示） */
    apiKeyConfigured?: boolean
    /** v0.6.8：登录成功回调 */
    onLogin?: (info: { token: string; deviceId: string; email: string }) => void
  } = {}) {
    this.port = options.port ?? 8717
    this.deviceId = options.deviceId ?? '-'
    this.relayUrl = options.relayUrl ?? '-'
    this.apiKeyConfigured = options.apiKeyConfigured ?? false
    this.onLogin = options.onLogin
    const here = dirname(fileURLToPath(import.meta.url))
    this.html = readFileSync(options.htmlFile ?? join(here, '..', 'panel', 'index.html'), 'utf8')
    // P1 修复：注入值先做 HTML 转义（防本地面板 XSS——此前 deviceId 原样拼进 HTML）
    const esc = (s: string): string => s.replace(/[&<>"']/g, (c) => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] ?? c
    ))
    if (options.deviceId) this.html = this.html.replace('id="deviceId">-', `id="deviceId">${esc(options.deviceId)}`)
    if (options.relayUrl) this.html = this.html.replace('id="relayUrl">-', `id="relayUrl">${esc(options.relayUrl)}`)
  }

  /** 启动本地服务 */
  start(): void {
    this.server = createServer((req, res) => this.route(req, res))
    this.server.listen(this.port, '127.0.0.1', () => {
      console.log(`[panel] 本地面板: http://localhost:${this.port}`)
    })
  }

  /** 面板收到一条事件（SSE 广播 + 本地历史） */
  publish(type: string, data: Record<string, unknown>): void {
    const ev: PanelEvent = { ts: Date.now(), type, data }
    this.history.push(ev)
    if (this.history.length > MAX_HISTORY) this.history.shift()
    const payload = `data: ${JSON.stringify(ev)}\n\n`
    // P1 修复：SSE 连接断开后 write 会抛错（此前异常冒泡会中断整个事件发布）
    for (const client of this.clients) {
      try {
        client.write(payload)
      } catch {
        this.clients.delete(client)
      }
    }
  }

  /** 关闭服务 */
  close(): void {
    this.server?.close()
    for (const client of this.clients) {
      client.end()
    }
    this.clients.clear()
  }

  private route(req: IncomingMessage, res: ServerResponse): void {
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (url.pathname === '/events') {
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      })
      res.write('retry: 3000\n\n')
      // 补发历史（断线重连不丢）
      for (const ev of this.history) {
        res.write(`data: ${JSON.stringify(ev)}\n\n`)
      }
      this.clients.add(res)
      req.on('close', () => {
        this.clients.delete(res)
      })
      return
    }
    if (url.pathname === '/api/status') {
      const bound = loadBound()
      this.sendJson(res, {
        loggedIn: typeof bound.token === 'string' && bound.token.length > 0,
        email: bound.email ?? '',
        deviceId: this.deviceId,
        relayUrl: this.relayUrl,
        apiKeyConfigured: this.apiKeyConfigured,
      })
      return
    }
    if (url.pathname === '/api/login' && req.method === 'POST') {
      void this.handleLogin(req, res)
      return
    }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
    res.end(this.html)
  }

  private sendJson(res: ServerResponse, obj: Record<string, unknown>): void {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify(obj))
  }

  /** POST /api/login：读 JSON body → 桌面登录 → 写绑定文件 → 回调 onLogin */
  private async handleLogin(req: IncomingMessage, res: ServerResponse): Promise<void> {
    let raw = ''
    for await (const chunk of req) {
      raw += chunk.toString()
      // 防止超长 body
      if (raw.length > 4096) break
    }
    let body: { email?: unknown; password?: unknown; apiKey?: unknown }
    try {
      body = JSON.parse(raw)
    } catch {
      this.sendJson(res, { ok: false, error: '请求格式错误' })
      return
    }
    const email = String(body.email ?? '').trim()
    const password = String(body.password ?? '')
    if (!email || !password) {
      this.sendJson(res, { ok: false, error: '请输入邮箱和密码' })
      return
    }
    const result = await deviceLogin({
      email,
      password,
      apiKey: typeof body.apiKey === 'string' ? body.apiKey : undefined,
      relayUrl: this.relayUrl,
    })
    if (result.ok && result.token && result.deviceId) {
      this.onLogin?.({ token: result.token, deviceId: result.deviceId, email })
      this.sendJson(res, { ok: true, token: result.token, desktop_id: result.deviceId })
    } else {
      this.sendJson(res, { ok: false, error: result.error ?? '登录失败' })
    }
  }
}
