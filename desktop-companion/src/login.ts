/**
 * Harness助手 · 桌面登录核心（v0.6.8：一键安装包用）
 *
 * 面板登录页复用：调服务器 /api/login（桌面认证）→ 写 ~/.harness-desktop.json
 * （token/device_id/email，免手动 setx）→ 可选的 API Key 写 ~/.dsh/.credentials.yaml
 * （免手动建文件）。与 deploy/desktop-login.mjs 逻辑一致，供本地面板 HTTP 调用。
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { dump, load as loadYaml } from 'js-yaml'

export interface DeviceLoginInput {
  email: string
  password: string
  /** 首次登录可选填的 API Key（写入 ~/.dsh/.credentials.yaml） */
  apiKey?: string
  /** relay 地址（wss://.../relay/device），登录接口由其推导 */
  relayUrl?: string
}

export interface DeviceLoginResult {
  ok: boolean
  token?: string
  deviceId?: string
  error?: string
}

export function boundDeviceFile(): string {
  return join(homedir(), '.harness-desktop.json')
}

export function loadBound(): { email?: string; device_id?: string; token?: string } {
  try {
    return JSON.parse(readFileSync(boundDeviceFile(), 'utf8')) as { email?: string; device_id?: string; token?: string }
  } catch {
    return {}
  }
}

export function saveBound(obj: Record<string, unknown>): void {
  writeFileSync(boundDeviceFile(), JSON.stringify(obj, null, 2))
}

/** 把 API Key 合并写入 ~/.dsh/.credentials.yaml（保留已有字段） */
export function saveApiKey(apiKey: string): void {
  const home = process.env.DSH_HOME ?? join(homedir(), '.dsh')
  const file = join(home, '.credentials.yaml')
  let obj: Record<string, unknown> = {}
  try {
    if (existsSync(file)) {
      const parsed = loadYaml(readFileSync(file, 'utf8'))
      if (parsed && typeof parsed === 'object') obj = parsed as Record<string, unknown>
    }
  } catch { /* 原文件不可解析 → 覆盖重写 */ }
  obj.DEEPSEEK_API_KEY = apiKey
  mkdirSync(home, { recursive: true })
  writeFileSync(file, dump(obj))
}

/** wss://host/relay/device → https://host/relay（登录 HTTP 接口根） */
export function relayHttpFrom(url: string): string {
  return url.replace(/^wss?/, 'https').replace(/\/device$/, '')
}

export async function deviceLogin(input: DeviceLoginInput): Promise<DeviceLoginResult> {
  if (!input.relayUrl) {
    return { ok: false, error: '未配置中继服务器地址（relayUrl），请先部署服务器并填写 wss://<你的服务器>/relay/device' }
  }
  const server = relayHttpFrom(input.relayUrl)
  const bound = loadBound()
  // 复用本机已绑定 device_id（禁换绑：一账号一电脑）
  const deviceId = bound.device_id ?? 'd_' + Math.random().toString(16).slice(2, 10)

  let res: Response
  try {
    res = await fetch(server + '/api/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        email: input.email,
        password: input.password,
        device: 'desktop',
        device_id: deviceId,
      }),
    })
  } catch (e) {
    return { ok: false, error: '无法连接服务器: ' + (e instanceof Error ? e.message : String(e)) }
  }

  let json: { ok?: boolean; token?: unknown; desktop_id?: unknown; error?: string } = {}
  try {
    json = await res.json() as { ok?: boolean; token?: unknown; desktop_id?: unknown; error?: string }
  } catch { /* 非 JSON 响应 */ }

  if (res.status !== 200 || !json.ok) {
    return { ok: false, error: json?.error ?? `HTTP ${res.status}` }
  }

  const token = String(json.token ?? '')
  const boundId = String(json.desktop_id ?? deviceId)
  saveBound({ email: input.email, device_id: boundId, token })
  if (input.apiKey && input.apiKey.trim().length > 0) {
    saveApiKey(input.apiKey.trim())
  }
  return { ok: true, token, deviceId: boundId }
}
