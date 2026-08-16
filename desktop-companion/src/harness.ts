/**
 * Harness SDK 驱动层：拉起 runtime 子进程（dsh-jsonrpc-agent + cordis.yml），
 * 提供 run/事件流/关闭。Key 与模型等用户配置由 config.ts 注入 runtime 环境。
 */

import { fileURLToPath } from 'node:url'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { createRequire } from 'node:module'
import { DeepSeekHarness } from '@deepseek-ai/dsh-sdk-client'
import type { HarnessNotification, RunResult } from '@deepseek-ai/dsh-sdk-client'
import { loadUserConfig } from './config.ts'

const require = createRequire(import.meta.url)

/** 本包内 npm 安装的 dsh-jsonrpc-agent bin 入口 */
function jsonrpcAgentBin(): string {
  return require.resolve('@deepseek-ai/dsh-sdk-jsonrpc-demo/bin')
}

export interface RunOptions {
  sessionId?: string
  onNotification?: (notification: HarnessNotification) => void
}

export interface CompanionOptions {
  /** 工作区目录（默认 process.cwd()） */
  cwd?: string
  /** runtime cordis.yml 路径（默认本包 runtime/cordis.yml） */
  cordisConfig?: string
}

export class HarnessCompanion {
  private harness?: DeepSeekHarness
  private readonly cwd: string
  private readonly cordisConfig: string

  constructor(options: CompanionOptions = {}) {
    this.cwd = options.cwd ?? process.cwd()
    const defaultConfig = fileURLToPath(new URL('../runtime/cordis.yml', import.meta.url))
    this.cordisConfig = options.cordisConfig ?? defaultConfig
  }

  /** 惰性创建 SDK 实例（复用用户配置注入 runtime 环境） */
  private ensureHarness(): DeepSeekHarness {
    if (this.harness) return this.harness
    const user = loadUserConfig()
    const bin = jsonrpcAgentBin()
    this.harness = new DeepSeekHarness({
      // 显式 launch spec：dsh-jsonrpc-agent bin + 本包 cordis.yml
      launch: {
        command: process.execPath,
        args: [bin, this.cordisConfig],
        cwd: this.cwd,
        // 继承父环境并注入用户 Key/模型参数（Key 只在本机，不离开这台电脑）
        env: {
          ...process.env,
          ...(user.apiKey ? { DEEPSEEK_API_KEY: user.apiKey } : {}),
          ...(user.reasoningEffort ? { DSH_REASONING_EFFORT: user.reasoningEffort } : {}),
          // 与官方 Web 端共享会话：root = ~/.dsh/sessions（除非显式覆盖）
          ...(process.env.DSH_SESSION_ROOT ? {} : { DSH_SESSION_ROOT: join(homedir(), '.dsh', 'sessions') }),
        },
      },
      cwd: this.cwd,
      provider: user.provider,
      model: user.model,
    })
    return this.harness
  }

  /** 跑一轮（或续接 sessionId） */
  async run(input: string, options: RunOptions = {}): Promise<RunResult> {
    const harness = this.ensureHarness()
    return harness.run(input, {
      sessionId: options.sessionId,
      onNotification: options.onNotification,
    })
  }

  /** Harness助手扩展：发送自定义 JSON-RPC 方法（session.list/rename/delete） */
  async request<T = unknown>(method: string, params?: Record<string, unknown>, timeoutMs?: number): Promise<T> {
    const harness = this.ensureHarness()
    return harness.client.request(method, params, timeoutMs) as Promise<T>
  }

  /** 关闭并回收 runtime 子进程 */
  async close(): Promise<void> {
    if (this.harness) {
      await this.harness.close()
      this.harness = undefined
    }
  }

  get started(): boolean {
    return this.harness !== undefined
  }
}

/** 便捷：一键跑一轮并返回最终回复 */
export async function quickRun(input: string, options: CompanionOptions & RunOptions = {}): Promise<string> {
  const { onNotification, sessionId, ...companionOptions } = options
  const companion = new HarnessCompanion(companionOptions)
  try {
    const result = await companion.run(input, { sessionId, onNotification })
    return result.finalResponse
  } finally {
    await companion.close()
  }
}
