/**
 * Harness助手 · M3 服务器配置
 *
 * 读取与 index.js 同目录的 config.json（不存在时用默认值）。
 * 敏感项（SMTP 授权码、邀请码）只存在服务器 config.json，不进入代码库。
 */

import { existsSync, readFileSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 当前模块所在目录（config.json / users.json 的存放目录）。
 * 三态兼容：tsx(ESM) / node 直跑 ESM / esbuild CJS bundle，一律以被执行的入口文件所在目录为准。
 * 生产部署时 config.json 与 index.js 放同目录即可。
 */
export const SELF_DIR: string = (() => {
  const entry = process.argv[1]
  if (entry) {
    try { return dirname(resolve(entry)) } catch { /* 继续回退 */ }
  }
  return (globalThis as { __dirname?: string }).__dirname
    ?? dirname(fileURLToPath(import.meta.url))
})()

export interface SmtpCfg {
  host: string
  port: number
  user: string
  pass: string
  /** smtp = 真实发信；log = 只打印验证码（本地验证用，生产必须 smtp） */
  mode: 'smtp' | 'log'
}

export interface AppConfig {
  /** true = 必须带 token 才能接入（M3 防多号）；false = 兼容旧客户端 */
  auth_required: boolean
  /** 私密加密总开关（A 方案：默认关，不暴露前端，会员功能预留） */
  private_enabled: boolean
  /** 注册邀请码（用户拍板：邀请码 + 邮箱验证码） */
  invite_codes: string[]
  smtp: SmtpCfg
  /** 用户库文件名（相对 index.js 所在目录） */
  users_file: string
  /** 公告文本（设置页展示；空 = 不显示公告） */
  notice?: string
  code_ttl_sec: number
  code_cooldown_sec: number
}

const DEFAULTS: AppConfig = {
  auth_required: true,
  private_enabled: false,
  invite_codes: [],
  smtp: { host: 'smtp.qq.com', port: 465, user: '', pass: '', mode: 'smtp' },
  users_file: 'users.json',
  code_ttl_sec: 300,
  code_cooldown_sec: 60,
}

export function loadConfig(): AppConfig {
  // 优先级：HARNESS_CONFIG 环境变量 > 与 index.js 同目录的 config.json
  const file = process.env.HARNESS_CONFIG || join(SELF_DIR, 'config.json')
  try {
    if (!existsSync(file)) return { ...DEFAULTS }
    const raw = JSON.parse(readFileSync(file, 'utf8')) as Partial<AppConfig>
    return {
      ...DEFAULTS,
      ...raw,
      smtp: { ...DEFAULTS.smtp, ...(raw.smtp ?? {}) },
    }
  } catch (e) {
    console.warn('[config] config.json 读取失败，使用默认值:', e)
    return { ...DEFAULTS }
  }
}
