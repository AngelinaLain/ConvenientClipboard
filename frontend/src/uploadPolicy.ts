import {refreshPublicConfig} from './api'

export const MIB = 1024 ** 2
export const GIB = 1024 ** 3
export const MIN_UPLOAD_BYTES = MIB
export const MAX_UPLOAD_BYTES = 100 * GIB
export type UploadSizeUnit = 'MiB' | 'GiB'

export function parseUploadLimit(value: number | string, unit: UploadSizeUnit): number {
    const number = typeof value === 'number' ? value : value.trim() ? Number(value) : NaN
    const multiplier = unit === 'MiB' ? MIB : unit === 'GiB' ? GIB : NaN
    const bytes = Math.round(number * multiplier)
    if (!Number.isFinite(number) || !Number.isSafeInteger(bytes) || bytes < MIN_UPLOAD_BYTES || bytes > MAX_UPLOAD_BYTES) {
        throw new Error('单文件上传上限须为 1 MiB 至 100 GiB 之间的有效数值')
    }
    return bytes
}

export function uploadLimitInput(bytes: number): { value: number; unit: UploadSizeUnit } {
    const unit = bytes >= GIB ? 'GiB' : 'MiB'
    return {value: bytes / (unit === 'GiB' ? GIB : MIB), unit}
}

export function formatUploadLimit(bytes: number): string {
    const {value, unit} = uploadLimitInput(bytes)
    return `${Number(value.toFixed(3))} ${unit}`
}

export function fileUploadError(file: Pick<File, 'name' | 'size'>, maxUploadBytes: number): string | null {
    if (!file.size) return `“${file.name}”是空文件，无法上传`
    if (file.size > maxUploadBytes) return `“${file.name}”超过当前单文件上限 ${formatUploadLimit(maxUploadBytes)}`
    return null
}

export async function validateUploadBatch<T extends Pick<File, 'name' | 'size'>>(files: readonly T[]) {
    // Refresh once for the entire selection or batch; each file uses that snapshot.
    const config = await refreshPublicConfig()
    return files.map(file => ({file, error: fileUploadError(file, config.maxUploadBytes)}))
}
