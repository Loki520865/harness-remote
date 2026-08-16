/**
 * Harness助手 · 桌面伴侣隧道入口（常驻）
 *
 * 用法: tsx src/tunnel-run.ts [工作区目录]
 * 环境变量: RELAY_URL=wss://<你的服务器>/relay/device  DEVICE_ID=d_xxx  DEVICE_TOKEN=<desktop_token>  PANEL_PORT=8717  NO_PANEL=1
 *
 * M3：DEVICE_ID 未显式设置时，优先读 desktop-login.mjs 写入的 ~/.harness-desktop.json
 * （禁换绑：必须用账号绑定的 device_id 上线，否则 auth_required=true 后被拒）。
 */
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { exec } from 'node:child_process'
import { CompanionTunnel } from './tunnel.ts'
import { LocalPanel } from './panel.ts'
import { loadUserConfig } from './config.ts'

function boundDeviceId(): string | undefined {
  try {
    const raw = readFileSync(join(homedir(), '.harness-desktop.json'), 'utf8')
    const obj = JSON.parse(raw) as { device_id?: string }
    if (typeof obj.device_id === 'string' && obj.device_id.length > 0) return obj.device_id
  } catch { /* 无绑定文件 */ }
  return undefined
}

/** v0.6.8：登录 token 自动读取——desktop-login.mjs 已写入 ~/.harness-desktop.json（免手动 setx）。 */
function boundToken(): string | undefined {
  try {
    const raw = readFileSync(join(homedir(), '.harness-desktop.json'), 'utf8')
    const obj = JSON.parse(raw) as { token?: string }
    if (typeof obj.token === 'string' && obj.token.length > 0) return obj.token
  } catch { /* 无绑定文件 */ }
  return undefined
}

/** v0.6.8：面板就绪后自动打开默认浏览器（NO_AUTO_OPEN=1 关闭）。 */
function openPanelInBrowser(port: number): void {
  if (process.env.NO_AUTO_OPEN === '1') return
  setTimeout(() => {
    exec(`start "" "http://localhost:${port}"`, (error) => {
      if (error) console.warn(`[panel] 自动打开浏览器失败: ${error.message}`)
    })
  }, 1200)
}

// v0.6.8 修复：优先读绑定文件（登录时写的最新值），环境变量仅作调试兜底——
// 用户机器若残留旧 setx 的 DEVICE_TOKEN/DEVICE_ID，会导致 token 无效/换绑被拒。
// 开源版：RELAY_URL 必须由部署者通过环境变量提供（不内置默认服务器）。
const url = process.env.RELAY_URL ?? ''
if (!url) {
  console.error('[tunnel-run] 未设置 RELAY_URL 环境变量（wss://<你的服务器>/relay/device），无法启动。')
  console.error('  示例（PowerShell）: setx RELAY_URL wss://your-server/relay/device（重开终端生效）')
  process.exit(1)
}
const deviceId = boundDeviceId() ?? process.env.DEVICE_ID ?? 'd_localhost'
const token = boundToken() ?? process.env.DEVICE_TOKEN ?? ''
const cwd = process.argv[2] ?? process.cwd()

console.log(`[tunnel-run] RELAY_URL=${url} DEVICE_ID=${deviceId} cwd=${cwd} token=${token ? '(已配置)' : '(未配置)'}`)

// v0.6.8：API Key 自检（新用户引导——缺失时 AI 调用静默失败，这里显式提示）
const userConfig = loadUserConfig()
if (!userConfig.apiKey) {
  console.warn('[tunnel-run] 未检测到 DEEPSEEK_API_KEY：AI 回复将失败。请任选其一配置：')
  console.warn('  1) 创建 ~/.dsh/.credentials.yaml 并写入:  DEEPSEEK_API_KEY: sk-xxx')
  console.warn('  2) 设置环境变量 DEEPSEEK_API_KEY（setx DEEPSEEK_API_KEY sk-xxx 后重开）')
  console.warn('  （面板登录页可直接填写 API Key，自动保存，无需手动操作）')
}

// M1.8: 本地面板（默认开，NO_PANEL=1 关闭）
let panel: LocalPanel | undefined
if (process.env.NO_PANEL !== '1') {
  const panelPort = Number(process.env.PANEL_PORT ?? 8717)
  panel = new LocalPanel({
    port: panelPort,
    deviceId,
    relayUrl: url,
    apiKeyConfigured: !!userConfig.apiKey,
    // v0.6.8：面板登录成功 → 用新 token/device 建立隧道（首次）或热切换 token（已登录重登）
    onLogin: (info) => {
      if (tunnel) {
        tunnel.setToken(info.token)
      } else {
        tunnel = new CompanionTunnel({
          url,
          deviceId: info.deviceId,
          deviceName: '我的工作站',
          cwd,
          token: info.token,
          onEvent: forwardToPanel,
        })
        tunnel.start()
      }
    },
  })
  panel.start()
  openPanelInBrowser(panelPort)
}

// v0.6.8：无 token（未登录）→ 不建立隧道，面板显示登录页；有 token → 直接连
let tunnel: CompanionTunnel | undefined
const forwardToPanel = (op: string, payload: Record<string, unknown>): void => {
  if (op === 'link') {
    panel?.publish('link', payload)
  } else if (op === 'forward') {
    panel?.publish('forward', payload)
  } else if (op === 'log') {
    panel?.publish('log', payload)
  }
}
if (token) {
  tunnel = new CompanionTunnel({
    url,
    deviceId,
    deviceName: '我的工作站',
    cwd,
    token,
    onEvent: forwardToPanel,
  })
  tunnel.start()
} else {
  console.log('[tunnel-run] 未登录：请在自动打开的页面中填写账号登录，登录后自动连接服务器。')
}

let stopping = false
async function stop(): Promise<void> {
  if (stopping) return
  stopping = true
  console.log('\n[tunnel-run] 正在关闭...')
  panel?.close()
  await tunnel?.close()
  process.exit(0)
}
process.on('SIGINT', () => void stop())
process.on('SIGTERM', () => void stop())

// v0.6.1 守护：未捕获异常/拒绝 → 记录原因后退出（外层 watchdog 计划任务会自动拉起）
process.on('uncaughtException', (err) => {
  console.error('[tunnel-run] uncaughtException:', err?.stack ?? err)
  process.exit(1)
})
process.on('unhandledRejection', (reason) => {
  console.error('[tunnel-run] unhandledRejection:', reason)
  process.exit(1)
})
