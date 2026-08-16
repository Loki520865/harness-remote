/**
 * Harness助手 · 桌面伴侣反向隧道（WSS 客户端）
 *
 * 连服务器 /device，注册 device.online，收 chat.send → 驱动 harness →
 * 把 session.event / session.status / run.done 流式转发回服务器。
 *
 * 断线自动重连（手机/服务器断线只断转发，harness 任务继续跑——M1.5 已验证）。
 */

import { WebSocket } from 'ws'
import { HarnessCompanion } from './harness.ts'
import type { HarnessNotification } from '@deepseek-ai/dsh-sdk-client'

export interface TunnelOptions {
  /** 服务器地址，如 ws://localhost:8787/device */
  url: string
  deviceId: string
  deviceName?: string
  /** M3：desktop_token（auth_required 时必填），自动拼到连接 URL 的 ?token= */
  token?: string
  /** harness 工作区 */
  cwd?: string
  /** 是否打印链路日志（默认 true） */
  verbose?: boolean
  /** 链路事件回调（M1.8 本地面板用：连接状态/转发事件/日志） */
  onEvent?: (op: string, payload: Record<string, unknown>) => void
}

export class CompanionTunnel {
  private readonly companion: HarnessCompanion
  private ws?: WebSocket
  private closed = false
  private reconnectTimer?: NodeJS.Timeout
  /** 连接 URL（不含 token；日志/面板只展示它，防 token 泄露） */
  private readonly url: string
  private readonly deviceId: string
  private readonly deviceName: string
  private token?: string
  private readonly verbose: boolean
  private readonly onEvent?: TunnelOptions['onEvent']

  /** M2（F21）：本机正在运行的任务（并发），task.list 数据源。 */
  private readonly runningTasks = new Map<string, {
    sessionId: string
    status: string
    startedAt: number
    lastEvent: string
  }>()

  /** M2（Q4）：最近处理过的 mid（幂等去重，防弱网重发重复驱动 harness）。 */
  private readonly recentMids = new Set<string>()

  /** v0.6.2：同会话串行队列——dsh 会话 turn 序号不允许同一 session 并发 run（否则 expected turn 错乱） */
  private readonly sessionQueues = new Map<string, Promise<void>>()

  constructor(options: TunnelOptions) {
    this.token = options.token
    this.url = options.url
    this.deviceId = options.deviceId
    this.deviceName = options.deviceName ?? options.deviceId
    this.verbose = options.verbose ?? true
    this.onEvent = options.onEvent
    this.companion = new HarnessCompanion({ cwd: options.cwd })
  }

  private log(...args: unknown[]): void {
    if (this.verbose) console.log(`[tunnel]`, ...args)
    this.onEvent?.('log', { text: args.map(String).join(' ') })
  }

  /** 建立（或重连）隧道 */
  start(): void {
    this.connect()
  }

  /** v0.6.8：登录后更新 token 并立即重连（无需重启进程） */
  setToken(token: string): void {
    this.token = token
    if (this.closed) return
    if (this.ws) {
      // close handler 会 3 秒后自动重连，届时使用新 token
      this.ws.close()
    } else {
      this.connect()
    }
  }

  private connect(): void {
    if (this.closed) return
    // P0 修复：日志/面板只显示无 token 的 URL（此前 this.url 含 ?token= 会进日志与面板 SSE）
    this.log(`连接服务器 ${this.url} ...`)
    const ws = new WebSocket(this.url, {
      // P1 修复（token 泄露面）：token 走自定义头，不再拼进 URL query（nginx 日志无残留）
      ...(this.token ? { headers: { 'x-dsh-token': this.token } } : {}),
    })
    this.ws = ws

    ws.on('open', () => {
      this.log('已连接，注册 device.online')
      this.onEvent?.('link', { state: 'up' })
      ws.send(JSON.stringify({
        op: 'device.online',
        device_id: this.deviceId,
        name: this.deviceName,
      }))
    })

    ws.on('message', (raw) => {
      let msg: Record<string, unknown>
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }
      const op = String(msg.op ?? '')
      if (op === 'chat.send') {
        void this.handleChatSend(ws, msg)
      } else if (op === 'session.list') {
        void this.handleSessionList(ws, msg)
      } else if (op === 'session.rename') {
        void this.handleSessionRename(ws, msg)
      } else if (op === 'session.delete') {
        void this.handleSessionDelete(ws, msg)
      } else if (op === 'session.create') {
        void this.handleSessionCreate(ws, msg)
      } else if (op === 'session.messages') {
        void this.handleSessionMessages(ws, msg)
      } else if (op === 'task.stop') {
        void this.handleTaskStop(ws, msg)
      } else if (op === 'user.answer') {
        // v0.6.4：手机提交 ask_user_question 答案 → 转发给 runtime（user/answer 方法）
        void this.handleUserAnswer(ws, msg)
      } else if (op === 'task.list') {
        void this.handleTaskList(ws, msg)
      } else if (op === 'file.list') {
        void this.handleFileList(ws, msg)
      } else if (op === 'file.read') {
        void this.handleFileRead(ws, msg)
      } else if (op === 'file.write') {
        void this.handleFileWrite(ws, msg)
      } else if (op === 'file.download') {
        void this.handleFileDownload(ws, msg)
      } else if (op === 'model.list') {
        void this.handleModelList(ws, msg)
      } else if (op === 'model.set') {
        void this.handleModelSet(ws, msg)
      } else if (op === 'terminal.exec') {
        void this.handleTerminalExec(ws, msg)
      } else if (op === 'session.fork') {
        void this.handleSessionFork(ws, msg)
      } else if (op === 'session.trace') {
        void this.handleSessionTrace(ws, msg)
      } else if (op === 'ocr.image') {
        void this.handleOcrImage(ws, msg)
      } else if (op === 'ping') {
        ws.send(JSON.stringify({ op: 'pong' }))
      }
    })

    ws.on('close', () => {
      this.onEvent?.('link', { state: 'down' })
      this.log('连接断开，3 秒后重连（harness 任务不受影响）')
      this.ws = undefined
      if (!this.closed) {
        this.reconnectTimer = setTimeout(() => this.connect(), 3000)
      }
    })

    ws.on('error', (err) => {
      this.log('连接错误:', err.message)
    })
  }

  /** 收到手机指令 → 回 ack → 按会话排队驱动 harness（同 session 串行，防 turn 错乱）→ 流式转发 */
  private async handleChatSend(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = msg.session_id ? String(msg.session_id) : undefined
    const mid = msg.mid ? String(msg.mid) : undefined
    const deviceId = String(msg.device_id ?? this.deviceId)

    // Q4 幂等：重复 mid（弱网重发）直接忽略，避免重复驱动 harness
    if (mid !== undefined) {
      if (this.recentMids.has(mid)) return
      this.recentMids.add(mid)
      if (this.recentMids.size > 20) {
        const first = this.recentMids.values().next().value
        if (first !== undefined) this.recentMids.delete(first)
      }
    }

    this.log(`收到指令: ${String(msg.content ?? '').slice(0, 60)} (session=${sessionId ?? '新会话'})`)
    // 先回 ack，让手机知道指令已送达（Q4 重发判定依据）
    this.reply(ws, 'chat.ack', { device_id: deviceId, mid })

    // v0.6.2：同 session 串行执行（turn 序号保护）；新会话（无 id）每轮独立 session，无需排队
    if (sessionId === undefined) {
      await this.driveChat(ws, msg)
      return
    }
    const prev = this.sessionQueues.get(sessionId) ?? Promise.resolve()
    const queued = prev
      .then(() => this.driveChat(ws, msg))
      .catch((error) => {
        // 吞掉队列异常，避免 rejected promise 卡死后续链（driveChat 内部已兜底，这里仅防御）
        this.log('串行队列异常（已忽略）:', error instanceof Error ? error.message : String(error))
      })
    this.sessionQueues.set(sessionId, queued)
  }

  /** 实际驱动 harness 一轮（排队后的执行体） */
  private async driveChat(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const content = String(msg.content ?? '')
    const sessionId = msg.session_id ? String(msg.session_id) : undefined
    const mid = msg.mid ? String(msg.mid) : undefined
    const deviceId = String(msg.device_id ?? this.deviceId)

    const forward = (op: string, payload: Record<string, unknown>): void => {
      this.onEvent?.('forward', { op, ...payload })
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ op, device_id: deviceId, ...payload }))
      }
    }

    // F21：注册运行任务（task.list 数据源）。
    // P1 修复：key 含 mid 唯一化——此前同会话快速连发两条会互相覆盖 runningTasks 条目。
    const key = `${sessionId ?? 'new'}:${mid ?? Date.now()}`
    this.runningTasks.set(key, { sessionId: sessionId ?? key, status: 'queued', startedAt: Date.now(), lastEvent: '' })

    try {
      const result = await this.companion.run(content, {
        sessionId,
        onNotification: (notification: HarnessNotification) => {
          const method = notification.method
          const params = notification.params as Record<string, unknown>
          if (method === 'session.event') {
            const ev = params.event as { type?: string }
            forward('event', { session_id: params.sessionId, event: params.event })
            const rec = this.runningTasks.get(key)
            if (rec !== undefined) {
              rec.status = 'running'
              if (ev?.type) rec.lastEvent = ev.type
            }
            if (this.verbose && ev?.type) console.log(`  [event] ${ev.type}`)
          } else if (method === 'session.status') {
            forward('session.status', { session_id: params.sessionId, status: params.status })
            const rec = this.runningTasks.get(key)
            if (rec !== undefined) {
              rec.status = String(params.status)
              rec.lastEvent = String(params.status)
            }
            if (this.verbose) console.log(`  [status] ${params.status}`)
          } else if (method === 'user.question') {
            // v0.6.4：模型调用 ask_user_question → 转发给手机渲染选项卡
            const payload = params as { requestId?: unknown; questions?: unknown }
            forward('user.question', {
              session_id: String(params.sessionId ?? sessionId ?? ''),
              request_id: String(payload.requestId ?? ''),
              questions: payload.questions,
            })
            if (this.verbose) console.log('  [ask] 模型向用户提问')
          }
        },
      })
      this.runningTasks.delete(key)
      forward('run.done', {
        session_id: result.sessionId,
        final: result.finalResponse ?? '',
        mid,
      })
      this.log(`指令完成: ${result.finalResponse?.slice(0, 80) ?? '(无文本回复)'}`)
    } catch (error) {
      this.runningTasks.delete(key)
      const message = error instanceof Error ? error.message : String(error)
      forward('run.error', { session_id: sessionId ?? '', error: message, mid })
      this.log('指令执行失败:', message)
    }
  }

  /** 收到手机 session.create → 新建会话（可指定工作区） */
  private async handleSessionCreate(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    const cwd = typeof msg.cwd === 'string' && msg.cwd.length > 0 ? msg.cwd : undefined
    this.log('收到 session.create' + (cwd ? ` (cwd=${cwd})` : ''))
    try {
      const result = await this.companion.request('session/create', cwd ? { cwd } : {}, 15000)
      const r = result as { sessionId: string; cwd: string }
      this.reply(ws, 'session.create.result', {
        device_id: deviceId,
        session_id: r.sessionId,
        cwd: r.cwd,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.create.error', { device_id: deviceId, error: message })
      this.log('session.create 失败:', message)
    }
  }

  /** 收到手机 task.stop → 中断指定会话任务 */
  private async handleTaskStop(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 task.stop: ${sessionId}`)
    try {
      await this.companion.request('task/cancel', { sessionId }, 10000)
      // 清理该会话的所有任务条目（key 为 `${sessionId}:${mid}`，须按前缀删）
      for (const key of [...this.runningTasks.keys()]) {
        if (key.startsWith(`${sessionId}:`)) this.runningTasks.delete(key)
      }
      this.reply(ws, 'task.stop.result', { device_id: deviceId, session_id: sessionId })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'task.stop.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('task.stop 失败:', message)
    }
  }

  /** v0.6.4：手机提交 ask_user_question 答案 → runtime user/answer 方法唤醒挂起的提问 */
  private async handleUserAnswer(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const requestId = String(msg.request_id ?? msg.requestId ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    if (!requestId) return
    try {
      const result = await this.companion.request('user/answer', { requestId, answers: msg.answers }, 10000)
      this.reply(ws, 'user.answer.ack', { device_id: deviceId, request_id: requestId, ...(result ?? {}) })
      this.log(`已回填提问答案 request=${requestId.slice(0, 8)}`)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'user.answer.error', { device_id: deviceId, request_id: requestId, error: message })
      this.log('提问答案回填失败:', message)
    }
  }

  /** 收到手机 task.list → 返回本机所有运行中任务（字段统一 snake_case，对齐 protocol-v2）。
   * P1 修复：同会话并发多条时按 session_id 聚合（保留最新一条），防列表刷屏。 */
  private async handleTaskList(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    const bySession = new Map<string, { sessionId: string; status: string; startedAt: number; lastEvent: string }>()
    for (const task of this.runningTasks.values()) {
      bySession.set(task.sessionId, task)
    }
    const tasks = [...bySession.values()].map(task => ({
      session_id: task.sessionId,
      status: task.status,
      started_at: task.startedAt,
      last_event: task.lastEvent,
    }))
    this.reply(ws, 'task.list.result', { device_id: deviceId, tasks })
  }

  /** 通用：文件类指令 → harness runtime 文件方法 → 回传 */
  private async handleFileOp(ws: WebSocket, msg: Record<string, unknown>, method: string, resultOp: string): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    const cwd = String(msg.cwd ?? '')
    const path = typeof msg.path === 'string' ? msg.path : undefined
    try {
      const result = await this.companion.request(method, {
        ...(cwd ? { cwd } : {}),
        ...(path !== undefined ? { path } : {}),
        ...(msg.content !== undefined ? { content: String(msg.content) } : {}),
        ...(msg.confirmed !== undefined ? { confirmed: msg.confirmed === true } : {}),
      }, 20000)
      this.reply(ws, resultOp, { device_id: deviceId, ...(result as Record<string, unknown>) })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, `${resultOp.replace('.result', '.error')}`, {
        device_id: deviceId,
        ...(cwd ? { cwd } : {}),
        ...(path !== undefined ? { path } : {}),
        error: message,
      })
      this.log(`${method} 失败:`, message)
    }
  }

  private handleFileList(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    return this.handleFileOp(ws, msg, 'file/list', 'file.list.result')
  }

  private handleFileRead(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    return this.handleFileOp(ws, msg, 'file/read', 'file.read.result')
  }

  private handleFileWrite(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    return this.handleFileOp(ws, msg, 'file/write', 'file.write.result')
  }

  private handleFileDownload(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    return this.handleFileOp(ws, msg, 'file/download', 'file.download.result')
  }

  // ── v0.7.0：模型信息/切换（model.list / model.set，新会话生效） ────────
  private async handleModelList(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log('收到 model.list')
    try {
      const result = await this.companion.request('model/list', undefined, 10000)
      this.reply(ws, 'model.list.result', { device_id: deviceId, ...(result as Record<string, unknown>) })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'model.list.error', { device_id: deviceId, error: message })
      this.log('model.list 失败:', message)
    }
  }

  private async handleModelSet(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log('收到 model.set')
    try {
      const result = await this.companion.request('model/set', {
        ...(msg.provider !== undefined ? { provider: String(msg.provider) } : {}),
        ...(msg.model !== undefined ? { model: String(msg.model) } : {}),
        ...(msg.maxTokens !== undefined ? { maxTokens: msg.maxTokens } : {}),
      }, 10000)
      this.reply(ws, 'model.set.result', { device_id: deviceId, ...(result as Record<string, unknown>) })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'model.set.error', { device_id: deviceId, error: message })
      this.log('model.set 失败:', message)
    }
  }

  // ── v0.7.0：远程终端（terminal.exec） ─────────────────────────────────
  private async handleTerminalExec(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    const cwd = String(msg.cwd ?? '')
    const command = String(msg.command ?? '')
    this.log(`收到 terminal.exec: ${command.slice(0, 60)} (cwd=${cwd})`)
    try {
      const result = await this.companion.request('terminal/exec', {
        cwd,
        command,
        ...(msg.confirmed !== undefined ? { confirmed: msg.confirmed === true } : {}),
      }, 150000)
      this.reply(ws, 'terminal.exec.result', { device_id: deviceId, ...(result as Record<string, unknown>) })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'terminal.exec.error', { device_id: deviceId, cwd, command, error: message })
      this.log('terminal.exec 失败:', message)
    }
  }

  // ── v0.7.0：会话分支（session.fork） ───────────────────────────────────
  private async handleSessionFork(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 session.fork: ${sessionId}`)
    try {
      const result = await this.companion.request('session/fork', { sessionId }, 30000)
      const r = result as { sessionId: string; cwd: string }
      this.reply(ws, 'session.fork.result', {
        device_id: deviceId,
        session_id: r.sessionId,
        cwd: r.cwd,
        parent_session_id: sessionId,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.fork.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('session.fork 失败:', message)
    }
  }

  // ── v0.7.0：轨迹面板（session.trace） ──────────────────────────────────
  private async handleSessionTrace(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 session.trace: ${sessionId}`)
    try {
      const result = await this.companion.request('session/trace', { sessionId }, 15000)
      const r = result as { entries: unknown[] }
      this.reply(ws, 'session.trace.result', {
        device_id: deviceId,
        session_id: sessionId,
        entries: r.entries ?? [],
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.trace.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('session.trace 失败:', message)
    }
  }

  // ── v0.7.0：图片 OCR（ocr.image） ──────────────────────────────────────
  private async handleOcrImage(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log('收到 ocr.image')
    try {
      const result = await this.companion.request('ocr/image', {
        base64: String(msg.base64 ?? ''),
        ...(msg.name !== undefined ? { name: String(msg.name) } : {}),
      }, 45000)
      this.reply(ws, 'ocr.image.result', { device_id: deviceId, ...(result as Record<string, unknown>) })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'ocr.image.error', { device_id: deviceId, error: message })
      this.log('ocr.image 失败:', message)
    }
  }

  /** 收到手机 session.list → 转发给 harness → 回传会话列表 */
  private async handleSessionList(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log('收到 session.list')
    try {
      const sessions = await this.companion.request('session/list', undefined, 15000)
      this.reply(ws, 'session.list.result', { device_id: deviceId, sessions: sessions ?? [] })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.list.error', { device_id: deviceId, error: message })
      this.log('session.list 失败:', message)
    }
  }

  /** 收到手机 session.messages → 转发给 harness → 回传会话历史 */
  private async handleSessionMessages(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 session.messages: ${sessionId}`)
    try {
      const messages = await this.companion.request('session/messages', { sessionId }, 15000)
      // 修复：回传带 session_id（App 端据此校验结果归属，防快速切会话竞态）
      this.reply(ws, 'session.messages.result', { device_id: deviceId, session_id: sessionId, messages: messages ?? [] })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.messages.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('session.messages 失败:', message)
    }
  }

  /** 收到手机 session.rename → 转发给 harness → 回传结果 */
  private async handleSessionRename(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const title = String(msg.title ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 session.rename: ${sessionId} -> ${title.slice(0, 40)}`)
    try {
      await this.companion.request('session/rename', { sessionId, title }, 15000)
      this.reply(ws, 'session.rename.result', { device_id: deviceId, session_id: sessionId })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.rename.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('session.rename 失败:', message)
    }
  }

  /** 收到手机 session.delete → 转发给 harness → 回传结果 */
  private async handleSessionDelete(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const sessionId = String(msg.session_id ?? '')
    const deviceId = String(msg.device_id ?? this.deviceId)
    this.log(`收到 session.delete: ${sessionId}`)
    try {
      await this.companion.request('session/delete', { sessionId }, 15000)
      this.reply(ws, 'session.delete.result', { device_id: deviceId, session_id: sessionId })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.reply(ws, 'session.delete.error', { device_id: deviceId, session_id: sessionId, error: message })
      this.log('session.delete 失败:', message)
    }
  }

  /** 向服务器回传一条响应消息 */
  private reply(ws: WebSocket, op: string, payload: Record<string, unknown>): void {
    this.onEvent?.('forward', { op, ...payload })
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ op, ...payload }))
    }
  }

  /** 关闭隧道（也会关闭 harness runtime） */
  async close(): Promise<void> {
    this.closed = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.ws?.close()
    await this.companion.close()
  }
}
