import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {UploadWebSocket} from '../src/uploadWebSocket'
import {ApiError} from '../src/api'
import type {UploadRecord} from '../src/uploadStorage'

class FakeSocket {
    static OPEN = 1
    static instances: FakeSocket[] = []
    static failFirstPart = false
    static partError = 0
    readyState = 0
    onopen?: () => void
    onmessage?: (event: {data: string}) => void
    onerror?: () => void
    onclose?: () => void
    sent: Array<string | Blob> = []

    constructor(readonly url: string) {
        FakeSocket.instances.push(this)
        queueMicrotask(() => { this.readyState = FakeSocket.OPEN; this.onopen?.() })
    }

    send(data: string | Blob) {
        this.sent.push(data)
        if (typeof data === 'string') {
            queueMicrotask(() => this.onmessage?.({data: JSON.stringify({type: 'ready'})}))
        } else if (FakeSocket.partError) {
            queueMicrotask(() => this.onmessage?.({data: JSON.stringify({type: 'error', status: FakeSocket.partError, message: 'bad part'})}))
        } else if (FakeSocket.failFirstPart && FakeSocket.instances.length === 1) {
            queueMicrotask(() => this.close())
        } else {
            queueMicrotask(() => this.onmessage?.({data: JSON.stringify({type: 'ack', index: 2})}))
        }
    }

    close() {
        if (this.readyState === 3) return
        this.readyState = 3
        this.onclose?.()
    }
}

const record = {id: '11111111-1111-4111-8111-111111111111', token: 'a'.repeat(64)} as UploadRecord
beforeEach(() => {
    FakeSocket.instances = []
    FakeSocket.failFirstPart = false
    FakeSocket.partError = 0
    vi.stubGlobal('location', {protocol: 'https:', host: 'files.example'})
    vi.stubGlobal('WebSocket', FakeSocket)
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('WebSocket part transport', () => {
    it('authenticates without a URL token, frames a part and reuses its connection', async () => {
        const socket = new UploadWebSocket(record, new AbortController().signal)
        await socket.part(2, new Blob([new Uint8Array([7, 8])]))
        await socket.part(2, new Blob([new Uint8Array([7, 8])]))
        expect(FakeSocket.instances).toHaveLength(1)
        const connection = FakeSocket.instances[0]!
        expect(connection.url).toBe('wss://files.example/api/upload-stream')
        expect(JSON.parse(connection.sent[0] as string)).toEqual({id: record.id, token: record.token})
        const frame = new Uint8Array(await (connection.sent[1] as Blob).arrayBuffer())
        expect([...frame]).toEqual([0, 0, 0, 2, 7, 8])
        socket.close()
    })

    it('reconnects and resends the same part after a lost acknowledgement', async () => {
        FakeSocket.failFirstPart = true
        vi.useFakeTimers()
        const socket = new UploadWebSocket(record, new AbortController().signal)
        const sending = socket.part(2, new Blob([new Uint8Array([7])]))
        await vi.advanceTimersByTimeAsync(1100)
        await sending
        expect(FakeSocket.instances).toHaveLength(2)
        expect(FakeSocket.instances.map(value => value.sent.filter(part => part instanceof Blob).length)).toEqual([1, 1])
        socket.close()
    })

    it('does not retry a rejected SHA-256 part', async () => {
        FakeSocket.partError = 422
        const socket = new UploadWebSocket(record, new AbortController().signal)
        await expect(socket.part(2, new Blob([new Uint8Array([7])]))).rejects.toMatchObject({status: 422} satisfies Partial<ApiError>)
        expect(FakeSocket.instances).toHaveLength(1)
        socket.close()
    })
})
