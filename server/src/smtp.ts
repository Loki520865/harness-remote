/**
 * Harness助手 · M3 零依赖 SMTP 发送（smtp.qq.com:465，AUTH LOGIN）
 *
 * 服务器零 npm 依赖（bundle 仅含 ws），因此手写最小 SMTP over TLS 客户端。
 * 只覆盖 QQ 邮箱所需的最小命令集：EHLO / AUTH LOGIN / MAIL / RCPT / DATA / QUIT。
 */

import * as tls from 'node:tls'
import { SmtpCfg } from './config.js'

function base64(s: string): string {
  return Buffer.from(s, 'utf8').toString('base64')
}

/** 将 UTF-8 文本 base64 编码并按 76 字符换行（MIME 标准）。 */
function b64wrap(text: string): string {
  const b = Buffer.from(text, 'utf8').toString('base64')
  let out = ''
  for (let i = 0; i < b.length; i += 76) out += b.slice(i, i + 76) + '\r\n'
  return out
}

export async function sendMail(cfg: SmtpCfg, to: string, subject: string, body: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({ host: cfg.host, port: cfg.port, servername: cfg.host })
    let buffer = ''
    let step = 0
    let authed = false
    let finished = false

    const sendLine = (line: string): void => {
      socket.write(line + '\r\n')
    }

    const fail = (msg: string): void => {
      if (!finished) {
        finished = true
        try { sendLine('QUIT') } catch { /* ignore */ }
        socket.destroy()
        reject(new Error(msg))
      }
    }

    const onData = (chunk: Buffer): void => {
      buffer += chunk.toString('utf8')
      // SMTP 多行响应以 "250-xxx" 开头，末行 "250 xxx"；这里按行处理
      let idx: number
      while ((idx = buffer.indexOf('\r\n')) >= 0) {
        const line = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (!line) continue
        const code = line.slice(0, 3)
        const isLast = line.length < 4 || line[3] !== '-'
        if (!isLast) continue // 多行响应继续读
        handleReply(code, line)
        if (finished) return
      }
    }

    const handleReply = (code: string, line: string): void => {
      if (code !== '220' && step === 0) return fail(`SMTP 连接失败: ${line}`)
      switch (step) {
        case 0: // 220 banner -> EHLO
          step = 1
          sendLine(`EHLO harness-relay`)
          break
        case 1: // 250 EHLO -> AUTH LOGIN
          if (code !== '250') return fail(`SMTP EHLO 失败: ${line}`)
          step = 2
          sendLine(`AUTH LOGIN`)
          break
        case 2: // 334 VXNlcm5hbWU6 -> user
          if (code !== '334') return fail(`SMTP AUTH 失败: ${line}`)
          step = 3
          sendLine(base64(cfg.user))
          break
        case 3: // 334 UGFzc3dvcmQ6 -> pass
          if (code !== '334') return fail(`SMTP AUTH 失败: ${line}`)
          step = 4
          sendLine(base64(cfg.pass))
          break
        case 4: // 235 auth ok -> MAIL FROM
          if (code !== '235') return fail(`SMTP 认证失败: ${line}`)
          authed = true
          step = 5
          sendLine(`MAIL FROM:<${cfg.user}>`)
          break
        case 5: // 250 MAIL FROM -> RCPT TO
          if (code !== '250') return fail(`SMTP MAIL FROM 失败: ${line}`)
          step = 6
          sendLine(`RCPT TO:<${to}>`)
          break
        case 6: // 250 RCPT TO -> DATA
          if (code !== '250') return fail(`SMTP RCPT TO 失败: ${line}`)
          step = 7
          sendLine('DATA')
          break
        case 7: { // 354 -> 邮件内容 + .
          if (code !== '354') return fail(`SMTP RCPT/DATA 失败: ${line}`)
          step = 8
          const msg =
            `From: ${cfg.user}\r\n` +
            `To: ${to}\r\n` +
            `Subject: =?UTF-8?B?${base64(subject)}?=\r\n` +
            `MIME-Version: 1.0\r\n` +
            `Content-Type: text/plain; charset=UTF-8\r\n` +
            `Content-Transfer-Encoding: base64\r\n` +
            `\r\n` +
            b64wrap(body) +
            `.\r\n`
          socket.write(msg)
          break
        }
        case 8: // 250 queued -> QUIT
          if (code !== '250') return fail(`SMTP 发送失败: ${line}`)
          step = 9
          sendLine('QUIT')
          break
        case 9: // 221 bye
          if (code !== '221') return fail(`SMTP 关闭异常: ${line}`)
          finished = true
          socket.end()
          resolve()
          break
      }
    }

    socket.on('data', onData)
    socket.on('error', (e) => fail(`SMTP 网络错误: ${e.message}`))
    socket.on('timeout', () => fail('SMTP 超时'))
    socket.setTimeout(10_000)
    socket.on('close', () => {
      if (!finished) fail('SMTP 连接提前关闭')
    })
  })
}
