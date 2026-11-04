import { reactive } from 'vue'
import type { User, PublicConfig, Settings } from './types'
export const session = reactive<{ user: User | null; ready: boolean; config: PublicConfig }>({ user: null, ready: false, config: { retentionDays: 7, maxUploadBytes: 1073741824, registrationEnabled: true } })
type CsrfToken = { token: string; headerName: string }
let csrfInFlight: Promise<CsrfToken> | null = null
let csrfGeneration = 0
let startup: Promise<void> | null = null
let publicConfigInFlight: Promise<PublicConfig> | null = null
let publicConfigRevision = 0
export class ApiError extends Error { constructor(message: string, public status: number, public code?: string) { super(message) } }
export function csrfToken(): Promise<CsrfToken> {
  if (csrfInFlight) return csrfInFlight
  const generation = csrfGeneration
  // Share concurrent requests, but never reuse a token across completed mutations.
  // Another tab or authentication transition may have replaced the CSRF cookie.
  const request = fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }).then(async response => {
    if (!response.ok) throw new ApiError('无法连接服务，请稍后重试', response.status)
    const token = await response.json() as CsrfToken
    return generation === csrfGeneration ? token : csrfToken()
  }).finally(() => { if (csrfInFlight === request) csrfInFlight = null })
  csrfInFlight = request
  return request
}
export function resetCsrf() { csrfGeneration++; csrfInFlight = null }
async function withCsrf<T>(operation: (token: CsrfToken) => Promise<T>): Promise<T> {
  try { return await operation(await csrfToken()) }
  catch (error) {
    // This explicit code is returned by the security filter before any write.
    // Other 403s, network failures and server errors must not repeat a mutation.
    if (!(error instanceof ApiError) || error.status !== 403 || error.code !== 'CSRF_INVALID') throw error
    return await operation(await csrfToken())
  }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const request = async (token?: CsrfToken): Promise<T> => {
    if (token) headers.set(token.headerName, token.token)
    const response = await fetch(`/api${path}`, { ...options, headers, cache: 'no-store', credentials: 'same-origin' })
    if (!response.ok) { const body = await response.json().catch(() => null); throw new ApiError(body?.message || `请求失败（${response.status}）`, response.status, body?.code) }
    return response.status === 204 ? undefined as T : await response.json()
  }
  return options.method && !['GET', 'HEAD', 'OPTIONS'].includes(options.method.toUpperCase()) ? withCsrf(request) : request()
}
export function applyUploadLimit(maxUploadBytes: number) {
  publicConfigRevision++
  session.config = { ...session.config, maxUploadBytes }
}
export function refreshPublicConfig(): Promise<PublicConfig> {
  if (publicConfigInFlight) return publicConfigInFlight
  const revision = publicConfigRevision
  const request = api<PublicConfig>('/public/config').then(config => {
    // A completed admin save must not be overwritten by an older pending read.
    if (revision === publicConfigRevision) session.config = config
    return session.config
  }).finally(() => { if (publicConfigInFlight === request) publicConfigInFlight = null })
  publicConfigInFlight = request
  return request
}
export async function saveAdminSettings(settings: Settings): Promise<Settings> {
  const saved = await api<Settings>('/admin/settings', { method: 'PUT', body: JSON.stringify(settings) })
  applyUploadLimit(saved.maxUploadBytes)
  return saved
}
export function initialize() {
  if (!startup) startup = Promise.all([
    api<User>('/auth/me').then(user => { session.user = user }).catch(() => { session.user = null }),
    refreshPublicConfig().catch(() => undefined),
  ]).then(() => { session.ready = true })
  return startup
}
export async function authenticate(mode: 'login' | 'register', username: string, password: string) {
  session.user = await api<User>(`/auth/${mode}`, { method: 'POST', body: JSON.stringify({ username, password }) })
  resetCsrf()
}
export async function logout() { await api('/auth/logout', { method: 'POST' }); session.user = null; resetCsrf() }
export async function uploadFile(file: File, permanent: boolean, progress: (value: number) => void): Promise<import('./types').FileInfo> {
  return withCsrf(token => new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/files')
    xhr.withCredentials = true
    xhr.setRequestHeader(token.headerName, token.token)
    xhr.upload.onprogress = event => { if (event.lengthComputable) progress(Math.min(99, Math.round(event.loaded / event.total * 100))) }
    xhr.onerror = () => reject(new Error('网络连接中断，请重试'))
    xhr.onload = () => { let body; try { body = JSON.parse(xhr.responseText) } catch { body = null }; if (xhr.status >= 200 && xhr.status < 300) { progress(100); resolve(body) } else reject(new ApiError(body?.message || `上传失败（${xhr.status}）`, xhr.status, body?.code)) }
    const body = new FormData(); body.append('file', file); body.append('permanent', String(permanent)); xhr.send(body)
  }))
}
export function size(bytes: number) { if (!bytes) return '0 B'; const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), 4); return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${['B', 'KB', 'MB', 'GB', 'TB'][index]}` }
export function date(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '永久保存' }
export async function copy(text: string) { if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text); else { const input = document.createElement('textarea'); input.value = text; input.style.position = 'fixed'; input.style.opacity = '0'; document.body.append(input); input.select(); const ok = document.execCommand('copy'); input.remove(); if (!ok) throw new Error('自动复制失败，请手动复制链接') } }
