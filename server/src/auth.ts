/**
 * Harness助手 · M3 账号体系（邀请码 + 邮箱验证码 + 双端绑定）
 *
 * 数据：
 *  - users.json：email -> UserRecord（phone_token / desktop_token 双端分离，M3 防多号）
 *  - 验证码：内存 Map，TTL 5 分钟，60s 冷却防轰炸
 *
 * 规则（用户拍板）：
 *  - 注册 = 邀请码 + 邮箱验证码
 *  - 登录 = 邮箱验证码（无密码）
 *  - 一账号一手机一电脑：phone/desktop 各一个在线名额（在线表在 index.ts）
 *  - 桌面端绑定 device_id：重复绑定不同 device_id 拒绝
 *  - 私密加密 = 服务器 config 开关，默认关，不暴露前端（会员预留）
 */

import { pbkdf2Sync, randomBytes, randomInt, timingSafeEqual } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync, renameSync } from 'node:fs'
import { join } from 'node:path'
import { AppConfig, SmtpCfg, SELF_DIR } from './config.js'
import { sendMail } from './smtp.js'

export interface UserRecord {
  email: string
  created_at: number
  /** 手机绑定标识（预留，当前以 token 为准） */
  phone_id: string | null
  /** 桌面伴侣绑定设备 id；非 null 后禁止换绑 */
  desktop_id: string | null
  phone_token: string
  desktop_token: string
  /** 密码哈希（pbkdf2_sha256，照晨曦AI 体系）；登录 = 邮箱+密码 */
  password_hash: string
}

export interface SendCodeResult {
  ok: boolean
  error?: string
  /** 仅 smtp.mode='log' 时返回，便于本地验证 */
  code?: string
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** 验证码错误尝试上限（超限锁定，防爆破：6 位验证码不设限等于裸奔）。 */
const MAX_CODE_ATTEMPTS = 5
/** 验证码锁定时长：10 分钟。 */
const CODE_LOCK_MS = 10 * 60 * 1000

/** 密码强度：≥8 位且同时包含字母和数字（与晨曦AI 一致）。 */
function pwdOk(pwd: string): boolean {
  if (pwd === null || pwd === undefined || pwd.length < 8) return false
  return /[a-zA-Z]/.test(pwd) && /[0-9]/.test(pwd)
}

/** 密码哈希：pbkdf2_hmac sha256，20 万迭代（对齐晨曦AI，标准库零依赖）。 */
const PBKDF2_ITERATIONS = 200_000
function hashPassword(pwd: string): string {
  const salt = randomBytes(16).toString('hex')
  const dk = pbkdf2Sync(pwd, Buffer.from(salt, 'hex'), PBKDF2_ITERATIONS, 32, 'sha256')
  return `pbkdf2_sha256$${PBKDF2_ITERATIONS}$${salt}$${dk.toString('hex')}`
}

function verifyPassword(pwd: string, stored: string): boolean {
  try {
    const [algo, iters, salt, hex] = stored.split('$')
    if (algo !== 'pbkdf2_sha256') return false
    const expect = Buffer.from(hex, 'hex')
    const got = pbkdf2Sync(pwd, Buffer.from(salt, 'hex'), Number(iters), expect.length, 'sha256')
    return expect.length === got.length && timingSafeEqual(expect, got)
  } catch {
    return false
  }
}

function genToken(): string {
  return 'tk_' + randomBytes(24).toString('hex')
}

export class AuthStore {
  private cfg: AppConfig
  private file: string
  private users = new Map<string, UserRecord>()
  private codes = new Map<string, { code: string; expire: number; lastSent: number }>()
  /** 爆破防护：验证码错误 -> 失败次数 / 锁定截止时间 */
  private attempts = new Map<string, { fails: number; lockedUntil: number }>()
  /** 爆破防护：密码登录失败 -> 失败次数 / 锁定截止时间（照晨曦AI：5次/5分钟锁15分钟） */
  private loginFails = new Map<string, { fails: number; lockedUntil: number }>()
  /** 找回密码验证码（与注册码分开，防止互相覆盖）。 */
  private resetCodes = new Map<string, { code: string; expire: number; lastSent: number }>()

  constructor(cfg: AppConfig) {
    this.cfg = cfg
    this.file = join(SELF_DIR, cfg.users_file)
    this.load()
  }

  private load(): void {
    try {
      if (!existsSync(this.file)) return
      const raw = JSON.parse(readFileSync(this.file, 'utf8')) as Record<string, UserRecord>
      for (const [email, u] of Object.entries(raw)) {
        if (u && typeof u.email === 'string') this.users.set(email, u)
      }
      console.log(`[auth] 已加载 ${this.users.size} 个用户`)
    } catch (e) {
      console.warn('[auth] users.json 加载失败（忽略）:', e)
    }
  }

  private save(): void {
    try {
      const obj: Record<string, UserRecord> = {}
      for (const [email, u] of this.users) obj[email] = u
      // 原子写：先写临时文件再 rename，避免写一半崩溃导致 users.json 损坏
      const tmp = `${this.file}.tmp`
      writeFileSync(tmp, JSON.stringify(obj, null, 2))
      renameSync(tmp, this.file)
    } catch (e) {
      console.warn('[auth] users.json 写入失败:', e)
    }
  }

  // ---------------- 验证码 ----------------

  async sendCode(email: string, inviteCode?: string): Promise<SendCodeResult> {
    email = (email ?? '').trim().toLowerCase()
    if (!EMAIL_RE.test(email)) return { ok: false, error: '邮箱格式不正确' }
    const now = Date.now()
    const rec = this.codes.get(email)
    if (rec && now - rec.lastSent < this.cfg.code_cooldown_sec * 1000) {
      const wait = Math.ceil((this.cfg.code_cooldown_sec * 1000 - (now - rec.lastSent)) / 1000)
      return { ok: false, error: `发送太频繁，请 ${wait} 秒后再试` }
    }
    // 有邀请码 = 注册流程；无 = 登录流程
    const exists = this.users.has(email)
    if (inviteCode && exists) return { ok: false, error: '该邮箱已注册，请直接登录' }
    if (!inviteCode && !exists) return { ok: false, error: '该邮箱未注册，请先注册' }
    if (inviteCode && !this.cfg.invite_codes.includes(inviteCode)) {
      return { ok: false, error: '邀请码无效' }
    }

    const code = String(randomInt(100000, 1000000))
    this.codes.set(email, { code, expire: now + this.cfg.code_ttl_sec * 1000, lastSent: now })

    const smtp: SmtpCfg = this.cfg.smtp
    if (smtp.mode === 'log' || !smtp.user || !smtp.pass) {
      console.warn(`[auth] [log-mode] ${email} 验证码 = ${code}（ttl ${this.cfg.code_ttl_sec}s）——本地验证模式，生产环境请确认 config.json smtp 已配置且 mode=smtp`)
      return { ok: true, code }
    }
    try {
      await sendMail(smtp, email, 'Harness助手 验证码', `你的验证码是 ${code}，${this.cfg.code_ttl_sec} 秒内有效。\n若非本人操作请忽略。`)
      console.log(`[auth] 验证码已发送 → ${email}`)
      return { ok: true }
    } catch (e) {
      this.codes.delete(email)
      console.warn(`[auth] 验证码发送失败 → ${email}:`, e)
      return { ok: false, error: '验证码发送失败，请稍后再试' }
    }
  }

  /** 校验并一次性消费验证码（带爆破防护：失败计数，超限锁定 10 分钟）。 */
  private verifyCode(email: string, code: string): boolean {
    // 锁定期间一律拒绝（含正确验证码），防止攻击者在锁定期内撞库
    if (this.lockRemainingMs(email) > 0) return false
    const rec = this.codes.get(email)
    if (!rec) return false
    if (Date.now() > rec.expire) {
      this.codes.delete(email)
      return false
    }
    if (rec.code !== code) {
      this.noteFailure(email)
      return false
    }
    this.codes.delete(email)
    this.attempts.delete(email) // 成功后清零失败计数
    return true
  }

  /** 记录一次验证码错误尝试；达上限后锁定该邮箱。 */
  private noteFailure(email: string): void {
    const now = Date.now()
    const rec = this.attempts.get(email) ?? { fails: 0, lockedUntil: 0 }
    if (rec.lockedUntil > now) return // 已锁定，不再递增
    rec.fails += 1
    if (rec.fails >= MAX_CODE_ATTEMPTS) {
      rec.fails = 0
      rec.lockedUntil = now + CODE_LOCK_MS
    }
    this.attempts.set(email, rec)
  }

  /** 返回邮箱剩余锁定时长（ms）；0 = 未锁定。 */
  private lockRemainingMs(email: string): number {
    const rec = this.attempts.get(email)
    if (!rec) return 0
    // 未锁定条目 lockedUntil=0（勿删——此前 `rem<=0` 把未锁定条目当过期删掉，
    // 导致失败计数每次被清零、锁定永不触发，爆破防护失效）
    if (rec.lockedUntil === 0) return 0
    const rem = rec.lockedUntil - Date.now()
    if (rem <= 0) {
      this.attempts.delete(email) // 锁定已过期，清除
      return 0
    }
    return rem
  }

  // ---------------- 注册 / 登录（照晨曦AI：邮箱+密码） ----------------

  register(
    email: string,
    code: string,
    password: string,
    phoneId: string | null,
  ): { ok: true; phone_token: string; desktop_token: string } | { ok: false; error: string } {
    email = (email ?? '').trim().toLowerCase()
    if (!EMAIL_RE.test(email)) return { ok: false, error: '邮箱格式不正确' }
    if (!pwdOk(password ?? '')) return { ok: false, error: '密码至少 8 位且同时包含字母和数字' }
    if (this.users.has(email)) return { ok: false, error: '该邮箱已注册' }
    if (!this.verifyCode(email, code)) {
      const rem = this.lockRemainingMs(email)
      if (rem > 0) return { ok: false, error: `验证码错误次数过多，请 ${Math.ceil(rem / 60000)} 分钟后再试` }
      return { ok: false, error: '验证码错误或已过期' }
    }
    const user: UserRecord = {
      email,
      created_at: Date.now(),
      phone_id: phoneId || null,
      desktop_id: null,
      phone_token: genToken(),
      desktop_token: genToken(),
      password_hash: hashPassword(password),
    }
    this.users.set(email, user)
    this.save()
    console.log(`[auth] 新用户注册: ${email}`)
    return { ok: true, phone_token: user.phone_token, desktop_token: user.desktop_token }
  }

  /** 密码登录（照晨曦AI：邮箱+密码，不再用验证码）。带密码爆破防护。 */
  login(
    email: string,
    password: string,
    device: 'phone' | 'desktop',
    deviceId: string | null,
  ): { ok: true; token: string; desktop_id?: string | null } | { ok: false; error: string } {
    email = (email ?? '').trim().toLowerCase()
    const user = this.users.get(email)
    if (!user) {
      // 未注册邮箱也记失败（防用户名枚举差异，晨曦AI 同策略）
      this.noteLoginFailure(email)
      return { ok: false, error: '邮箱或密码错误' }
    }
    const lockedRemain = this.loginLockRemainingMs(email)
    if (lockedRemain > 0) {
      return { ok: false, error: `尝试过于频繁，请 ${Math.ceil(lockedRemain / 60000)} 分钟后再试` }
    }
    if (!verifyPassword(password ?? '', user.password_hash ?? '')) {
      this.noteLoginFailure(email)
      return { ok: false, error: '邮箱或密码错误' }
    }
    this.loginFails.delete(email) // 成功后清零
    if (device === 'desktop') {
      if (user.desktop_id !== null && deviceId !== null && user.desktop_id !== deviceId) {
        return { ok: false, error: `该账号已绑定电脑 ${user.desktop_id}，不允许换绑（防多号）` }
      }
      if (deviceId) user.desktop_id = deviceId
      user.desktop_token = genToken()
      this.save()
      return { ok: true, token: user.desktop_token, desktop_id: user.desktop_id }
    }
    // phone：刷新手机 token
    user.phone_token = genToken()
    this.save()
    return { ok: true, token: user.phone_token }
  }

  /** 登录失败计数：5 次锁 15 分钟（照晨曦AI login_max_fail/login_lock）。 */
  private noteLoginFailure(email: string): void {
    const now = Date.now()
    const rec = this.loginFails.get(email) ?? { fails: 0, lockedUntil: 0 }
    if (rec.lockedUntil > now) return
    rec.fails += 1
    if (rec.fails >= 5) {
      rec.fails = 0
      rec.lockedUntil = now + 15 * 60 * 1000
    }
    this.loginFails.set(email, rec)
  }

  private loginLockRemainingMs(email: string): number {
    const rec = this.loginFails.get(email)
    if (!rec) return 0
    if (rec.lockedUntil === 0) return 0
    const rem = rec.lockedUntil - Date.now()
    if (rem <= 0) {
      this.loginFails.delete(email)
      return 0
    }
    return rem
  }

  // ---------------- 改密 / 找回密码（照晨曦AI：验证码+新密码） ----------------

  /** 登录后修改密码：验旧密 -> 强度校验 -> 更新 -> 吊销双端 token（照晨曦AI：改密吊销全家）。 */
  changePassword(email: string, oldPwd: string, newPwd: string): { ok: boolean; error?: string } {
    const user = this.users.get((email ?? '').trim().toLowerCase())
    if (!user) return { ok: false, error: '用户不存在' }
    if (!pwdOk(newPwd ?? '')) return { ok: false, error: '密码至少 8 位且同时包含字母和数字' }
    if (oldPwd === newPwd) return { ok: false, error: '新密码不能与原密码相同' }
    if (!verifyPassword(oldPwd ?? '', user.password_hash ?? '')) return { ok: false, error: '原密码错误' }
    user.password_hash = hashPassword(newPwd)
    // 吊销双端 token：改密后手机/桌面需重新登录（旧凭证立即失效）
    user.phone_token = genToken()
    user.desktop_token = genToken()
    this.save()
    return { ok: true }
  }

  /** 请求找回密码：生成验证码发邮件（防枚举：不存在也返回已发送）。 */
  async sendResetCode(email: string): Promise<SendCodeResult> {
    email = (email ?? '').trim().toLowerCase()
    if (!EMAIL_RE.test(email)) return { ok: false, error: '邮箱格式不正确' }
    const now = Date.now()
    const rec = this.resetCodes.get(email)
    if (rec && now - rec.lastSent < this.cfg.code_cooldown_sec * 1000) {
      return { ok: true } // 防爆破：不暴露限频细节，统一"已发送"
    }
    const code = String(randomInt(100000, 1000000))
    this.resetCodes.set(email, { code, expire: now + this.cfg.code_ttl_sec * 1000, lastSent: now })
    const smtp: SmtpCfg = this.cfg.smtp
    if (smtp.mode === 'log' || !smtp.user || !smtp.pass) {
      console.warn(`[auth] [log-mode] ${email} 找回密码验证码 = ${code}（本地验证模式，生产请确认 smtp 已配置）`)
      return { ok: true, code }
    }
    try {
      await sendMail(smtp, email, 'Harness助手 找回密码验证码', `你的找回密码验证码是 ${code}，${this.cfg.code_ttl_sec} 秒内有效。\n若非本人操作请忽略。`)
      console.log(`[auth] 找回验证码已发送 → ${email}`)
      return { ok: true }
    } catch (e) {
      this.resetCodes.delete(email)
      console.warn(`[auth] 找回验证码发送失败 → ${email}:`, e)
      return { ok: false, error: '验证码发送失败，请稍后再试' }
    }
  }

  /** 用验证码重置密码（一次性消费，5 次错误作废）。 */
  resetPassword(email: string, code: string, newPwd: string): { ok: boolean; error?: string } {
    email = (email ?? '').trim().toLowerCase()
    const user = this.users.get(email)
    if (!user) return { ok: false, error: '该邮箱未注册' }
    if (!pwdOk(newPwd ?? '')) return { ok: false, error: '密码至少 8 位且同时包含字母和数字' }
    const rec = this.resetCodes.get(email)
    if (!rec) return { ok: false, error: '验证码无效或已过期，请重新获取' }
    if (Date.now() > rec.expire) {
      this.resetCodes.delete(email)
      return { ok: false, error: '验证码已过期，请重新获取' }
    }
    // 找回验证码失败计数复用 attempts（5 次锁 10 分钟）
    if (this.lockRemainingMs(email) > 0) {
      return { ok: false, error: '尝试次数过多，请稍后再试' }
    }
    if (rec.code !== code) {
      this.noteFailure(email)
      return { ok: false, error: '验证码错误' }
    }
    this.resetCodes.delete(email)
    this.attempts.delete(email)
    // 身份已验证：清密码登录锁定，避免"找回成功还要等 15 分钟"的坏体验
    this.loginFails.delete(email)
    user.password_hash = hashPassword(newPwd)
    this.save()
    return { ok: true }
  }

  /** 桌面首次上线绑定（兜底：register 后 desktop_id 为 null，首次 device.online 绑定）。 */
  bindDesktop(email: string, deviceId: string): boolean {
    const user = this.users.get((email ?? '').trim().toLowerCase())
    if (!user) return false
    if (user.desktop_id !== null && user.desktop_id !== deviceId) return false
    user.desktop_id = deviceId
    this.save()
    return true
  }

  /** 按端校验 token → 返回用户；无效返回 null。 */
  validateToken(role: 'phone' | 'desktop', token: string): UserRecord | null {
    if (!token) return null
    for (const u of this.users.values()) {
      if (role === 'phone' && u.phone_token === token) return u
      if (role === 'desktop' && u.desktop_token === token) return u
    }
    return null
  }

  /**
   * P0 修复（device_id 抢占防护）：该 device_id 是否已被**其他**账号绑定。
   * 场景：B 的 desktop_id=null 时用 B 的 token 上线 A 已绑定的 device_id，
   * 原逻辑会直接绑定并把 A 的设备占走——必须全局查 users 表拦截。
   */
  isDeviceTakenByOther(email: string, deviceId: string): boolean {
    const self = (email ?? '').trim().toLowerCase()
    for (const u of this.users.values()) {
      if (u.email === self) continue
      if (u.desktop_id === deviceId) return true
    }
    return false
  }

  getUser(email: string): UserRecord | null {
    return this.users.get((email ?? '').trim().toLowerCase()) ?? null
  }
}
