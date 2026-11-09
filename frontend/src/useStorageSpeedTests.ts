import {computed, ref} from 'vue'
import {api, ApiError} from './api'
import type {StorageSpeedTest} from './types'

export interface StorageSpeedTestState {
    test: StorageSpeedTest | null;
    error: string;
    reading: boolean
}

export const speedTestFinished = (test: StorageSpeedTest) => ['COMPLETED', 'FAILED'].includes(test.status)

export function speedRate(bytesPerSecond: number | null) {
    if (bytesPerSecond === null || !Number.isFinite(bytesPerSecond) || bytesPerSecond < 0) return '—'
    return `${(bytesPerSecond / 1024 ** 2).toFixed(2)} MiB/s`
}

export function useStorageSpeedTests() {
    const states = ref<Record<string, StorageSpeedTestState>>({})
    const activeProviderId = ref<string | null>(null)
    const running = computed(() => activeProviderId.value !== null)
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined
    let revision = 0
    let disposed = false

    function state(providerId: string): StorageSpeedTestState {
        if (!states.value[providerId]) states.value[providerId] = {test: null, error: '', reading: false}
        return states.value[providerId]!
    }

    function accept(providerId: string, test: StorageSpeedTest) {
        state(providerId).test = test
        if (speedTestFinished(test)) activeProviderId.value = null
        else timer = setTimeout(() => { void read(providerId) }, 1000)
    }

    async function start(providerId: string) {
        if (disposed || running.value) return
        const current = ++revision
        const entry = state(providerId)
        entry.test = null
        entry.error = ''
        entry.reading = true
        activeProviderId.value = providerId
        controller = new AbortController()
        try {
            const test = await api<StorageSpeedTest>(`/admin/providers/${providerId}/speed-test`, {
                method: 'POST', signal: controller.signal
            })
            if (current === revision) accept(providerId, test)
        } catch (error) {
            if (current !== revision) return
            entry.error = (error as Error).message
            activeProviderId.value = null
        } finally {
            if (current === revision) entry.reading = false
        }
    }

    async function read(providerId: string) {
        const entry = state(providerId)
        if (disposed || activeProviderId.value !== providerId || entry.reading || !entry.test) return
        clearTimeout(timer)
        const current = ++revision
        entry.error = ''
        entry.reading = true
        controller = new AbortController()
        try {
            const test = await api<StorageSpeedTest>(`/admin/providers/${providerId}/speed-test/${entry.test.id}`, {
                signal: controller.signal
            })
            if (current === revision) accept(providerId, test)
        } catch (error) {
            if (current !== revision) return
            entry.error = `读取测速进度失败：${(error as Error).message}`
            // A missing task (e.g. after a server restart) cannot be resumed.
            if (error instanceof ApiError && [401, 403, 404].includes(error.status)) activeProviderId.value = null
        } finally {
            if (current === revision) entry.reading = false
        }
    }

    function clear(providerId: string) {
        if (activeProviderId.value !== providerId) delete states.value[providerId]
    }

    function dispose() {
        disposed = true
        revision++
        clearTimeout(timer)
        controller?.abort()
    }

    return {states, activeProviderId, running, start, read, clear, dispose}
}
