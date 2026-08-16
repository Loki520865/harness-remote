/**
 * 用户本机配置复用（D3）：
 * 读已装 harness 的用户配置，桌面伴侣零配置接管。
 * - Key:    $DSH_HOME/.credentials.yaml（DEEPSEEK_API_KEY 等）
 * - 模型:   $DSH_HOME/settings.yaml（agent-default-model）
 * - 环境变量优先于文件（与 dsh-credentials-local 的层序一致：env > file > .env）
 */

import { readFileSync, existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { load as loadYaml } from 'js-yaml'

export interface UserConfig {
  /** API Key（可能为 undefined，留给 runtime 内部 credentials 层解析） */
  apiKey?: string
  /** 模型 provider，默认 deepseek-official */
  provider: string
  /** 模型 id，默认 deepseek-v4-flash */
  model: string
  /** 推理强度 */
  reasoningEffort?: string
  /** DSH_HOME 绝对路径 */
  dshHome: string
}

export function dshHome(): string {
  return process.env.DSH_HOME ?? join(homedir(), '.dsh')
}

function readYamlFile(file: string): Record<string, unknown> | undefined {
  if (!existsSync(file)) return undefined
  try {
    const parsed = loadYaml(readFileSync(file, 'utf8'))
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : undefined
  } catch {
    return undefined
  }
}

/**
 * 汇总用户配置。层序（高→低）：
 * 1. 进程环境变量（DEEPSEEK_API_KEY / DSH_MODEL / DSH_PROVIDER）
 * 2. $DSH_HOME/.credentials.yaml 与 $DSH_HOME/settings.yaml
 */
export function loadUserConfig(): UserConfig {
  const home = dshHome()

  const credentials = readYamlFile(join(home, '.credentials.yaml'))
  const settings = readYamlFile(join(home, 'settings.yaml'))

  const apiKey = process.env.DEEPSEEK_API_KEY
    ?? (typeof credentials?.DEEPSEEK_API_KEY === 'string' ? credentials.DEEPSEEK_API_KEY : undefined)

  const defaultModel = settings?.['agent-default-model']
  const modelFromSettings = defaultModel && typeof defaultModel === 'object'
    ? (defaultModel as Record<string, unknown>)
    : undefined

  const provider = process.env.DSH_PROVIDER
    ?? (typeof modelFromSettings?.provider === 'string' ? modelFromSettings.provider : undefined)
    ?? 'deepseek-official'
  const model = process.env.DSH_MODEL
    ?? (typeof modelFromSettings?.model === 'string' ? modelFromSettings.model : undefined)
    ?? 'deepseek-v4-flash'
  const reasoningEffort = typeof modelFromSettings?.reasoningEffort === 'string'
    ? modelFromSettings.reasoningEffort
    : undefined

  return { apiKey, provider, model, reasoningEffort, dshHome: home }
}
