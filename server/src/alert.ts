/**
 * Harness助手 · 服务器邮件告警（v0.6.3）
 *
 * 职责：
 *  - 桌面伴侣（device）离线：断开后等 60s 确认（自动重连/心跳抖动不误报），
 *    仍未重连则发邮件通知；同一设备 30 分钟内只发一封（防刷屏）。
 *  - relay 服务启动：发一封"服务已重启"通知（排查"为什么又连不上"）。
 *
 * 收件人 = cfg.smtp.user（QQ 邮箱，授权码在服务器 config.json，不进代码库）。
 */

import { sendMail } from './smtp.js'
import type { AppConfig } from './config.js'

/** 离线确认窗口：断开后等这么久再判（自动重连/短抖不告警） */
const OFFLINE_CONFIRM_MS = 60_000
/** 同一设备同类告警冷却期（防网络反复抖动刷邮件） */
const ALERT_COOLDOWN_MS = 30 * 60_000

const pendingTimers = new Map<string, NodeJS.Timeout>()
const lastSentAt = new Map<string, number>()

function log(...args: unknown[]): void {
  console.log('[alert]', ...args)
}

/** 设备重新上线 → 取消未触发的离线告警 */
export function cancelDeviceOfflineAlert(deviceId: string): void {
  const t = pendingTimers.get(deviceId)
  if (t) {
    clearTimeout(t)
    pendingTimers.delete(deviceId)
    log(`设备 ${deviceId} 已恢复在线，取消离线告警`)
  }
}

/**
 * 设备断开 → 安排离线告警（isOnline 在触发时查询，已重连则不发）。
 * 同 device 已有未触发告警时不再叠加。
 */
export function scheduleDeviceOfflineAlert(
  cfg: AppConfig,
  deviceId: string,
  deviceName: string,
  isOnline: () => boolean,
): void {
  if (pendingTimers.has(deviceId)) return
  const timer = setTimeout(() => {
    pendingTimers.delete(deviceId)
    if (isOnline()) return // 已重连，不告警
    const now = Date.now()
    const last = lastSentAt.get(deviceId) ?? 0
    if (now - last < ALERT_COOLDOWN_MS) return // 冷却期内，不重复打扰
    lastSentAt.set(deviceId, now)
    void sendAlert(
      cfg,
      `[Harness助手] 桌面伴侣离线（${deviceName || deviceId}）`,
      [
        `设备 ${deviceId}（${deviceName || '未命名'}）已离线 1 分钟以上未重连。`,
        '',
        `时间：${new Date(now).toLocaleString('zh-CN', { hour12: false })}`,
        '',
        '排查建议：',
        '1. 检查电脑是否关机/睡眠/网络断开；',
        '2. 桌面伴侣进程是否被结束（看门狗每 2 分钟自动拉起）；',
        '3. 手机连接状态下发送指令会报「设备离线」。',
      ].join('\r\n'),
    )
  }, OFFLINE_CONFIRM_MS)
  pendingTimers.set(deviceId, timer)
  log(`设备 ${deviceId} 离线，${OFFLINE_CONFIRM_MS / 1000}s 后确认是否告警`)
}

/** relay 服务启动通知（fire-and-forget，失败只记日志） */
export function notifyServerStarted(cfg: AppConfig): void {
  void sendAlert(
    cfg,
    '[Harness助手] 服务器已重启',
    `harness-relay 服务已启动（${new Date().toLocaleString('zh-CN', { hour12: false })}）。\r\n\r\n手机/桌面伴侣应已自动重连；若手机仍未恢复，请重新打开 App 或点「连接」。`,
  )
}

async function sendAlert(cfg: AppConfig, subject: string, body: string): Promise<void> {
  const to = cfg.smtp?.user
  if (!to || cfg.smtp?.mode !== 'smtp') {
    log('SMTP 未配置，跳过邮件:', subject)
    return
  }
  try {
    await sendMail(cfg.smtp, to, subject, body)
    log('已发送邮件:', subject)
  } catch (e) {
    log('邮件发送失败:', e instanceof Error ? e.message : String(e))
  }
}
