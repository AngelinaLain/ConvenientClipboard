import {ApiError} from './api'
import {abortError, checkAbort} from './chunkHash'
import {retryDelay, retryable} from './resumableUpload'
import type {UploadRecord} from './uploadStorage'

type ServerMessage = {type: 'ready' | 'ack' | 'error'; index?: number; status?: number; message?: string}
type Pending = {resolve: () => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout>;
    type: 'ready' | 'ack'; index?: number}
export const MAX_UPLOAD_CONNECTIONS = 4
const CONNECT_TIMEOUT_MS = 20_000
const PART_TIMEOUT_MS = 2 * 60_000
const MAX_PART_ATTEMPTS = 3
const RETRY_DELAY_MS = 1000

/** A worker keeps one socket open and waits for each durable part receipt before sending the next. */
export class UploadWebSocket {
    private socket?: WebSocket
    private pending?: Pending
    private opening?: Promise<void>
    private readonly abort = () => this.close()

    constructor(private readonly record: UploadRecord, private readonly signal: AbortSignal) {
        signal.addEventListener('abort', this.abort, {once: true})
    }

    private url(): string {
        return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/upload-stream`
    }

    private wait(milliseconds: number, type: 'ready' | 'ack', index?: number): Promise<void> {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.socket?.close()
                reject(new Error('上传连接响应超时'))
            }, milliseconds)
            this.pending = {resolve, reject, timer, type, index}
        })
    }

    private settle(error?: Error) {
        const pending = this.pending
        this.pending = undefined
        if (!pending) return
        clearTimeout(pending.timer)
        if (error) pending.reject(error)
        else pending.resolve()
    }

    private async connect(): Promise<void> {
        checkAbort(this.signal)
        if (this.socket?.readyState === WebSocket.OPEN) return
        if (this.opening) return this.opening
        const socket = new WebSocket(this.url())
        this.socket = socket
        socket.onopen = () => socket.send(JSON.stringify({id: this.record.id, token: this.record.token}))
        socket.onmessage = event => {
            if (this.socket !== socket) return
            let message: ServerMessage
            try { message = JSON.parse(String(event.data)) as ServerMessage }
            catch { this.settle(new Error('上传服务返回了无效响应')); socket.close(); return }
            if (message.type === 'error') this.settle(new ApiError(message.message || '上传失败', message.status || 500))
            else if (this.pending?.type === message.type && (message.type !== 'ack' || this.pending.index === message.index)) this.settle()
            else { this.settle(new Error('上传服务返回了不匹配的确认')); socket.close() }
        }
        socket.onerror = () => { if (this.socket === socket) this.settle(new Error('上传连接中断')) }
        socket.onclose = () => {
            if (this.socket !== socket) return
            this.socket = undefined
            this.settle(this.signal.aborted ? abortError() : new Error('上传连接中断'))
        }
        this.opening = this.wait(CONNECT_TIMEOUT_MS, 'ready').finally(() => { this.opening = undefined })
        return this.opening
    }

    async part(index: number, body: Blob, notice?: (attempt: number) => void): Promise<void> {
        for (let attempt = 0; ; attempt++) {
            checkAbort(this.signal)
            try {
                await this.connect()
                checkAbort(this.signal)
                const header = new ArrayBuffer(4)
                new DataView(header).setInt32(0, index)
                const receipt = this.wait(PART_TIMEOUT_MS, 'ack', index)
                this.socket!.send(new Blob([header, body]))
                await receipt
                return
            } catch (error) {
                checkAbort(this.signal)
                this.socket?.close()
                this.socket = undefined
                if (attempt + 1 >= MAX_PART_ATTEMPTS || !retryable(error)) throw error
                notice?.(attempt + 1)
                await retryDelay(RETRY_DELAY_MS * 2 ** attempt, this.signal)
            }
        }
    }

    close() {
        this.settle(abortError())
        this.socket?.close()
        this.socket = undefined
        this.signal.removeEventListener('abort', this.abort)
    }
}

export async function uploadMissingParts(record: UploadRecord, source: File, chunkSize: number, missing: number[],
                                         signal: AbortSignal, check: () => void, onPart: (bytes: number) => void,
                                         onRetry: (attempt: number) => void): Promise<void> {
    if (missing.length === 0) return
    const controller = new AbortController()
    const abort = () => controller.abort()
    signal.addEventListener('abort', abort, {once: true})
    let next = 0
    const worker = async () => {
        const socket = new UploadWebSocket(record, controller.signal)
        try {
            while (next < missing.length) {
                checkAbort(controller.signal)
                check()
                const index = missing[next++]!
                const body = source.slice(index * chunkSize, Math.min(source.size, (index + 1) * chunkSize))
                await socket.part(index, body, onRetry)
                checkAbort(controller.signal)
                check()
                onPart(body.size)
            }
        } finally { socket.close() }
    }
    try {
        await Promise.all(Array.from({length: Math.min(MAX_UPLOAD_CONNECTIONS, missing.length)}, () => worker().catch(error => {
            controller.abort()
            throw error
        })))
    } finally {
        controller.abort()
        signal.removeEventListener('abort', abort)
    }
}
