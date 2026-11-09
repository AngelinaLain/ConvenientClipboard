import {computed, ref, shallowRef, watch} from 'vue'
import {api, ApiError, copy} from './api'
import type {ClipboardInfo} from './types'

export const MAX_CLIPBOARD_LENGTH = 100000

export function useClipboard() {
    const draft = ref('')
    const customId = ref('')
    const customIdError = ref('')
    const stopCustomIdWatch = watch(customId, () => { customIdError.value = '' }, {flush: 'sync'})
    const entry = shallowRef<ClipboardInfo | null>(null)
    const loading = ref(false)
    const saving = ref(false)
    const expired = ref(false)
    const error = ref('')
    const copying = ref(false)
    const copyMessage = ref('')
    const copyFailed = ref(false)
    const now = ref(Date.now())
    const secondsRemaining = computed(() => entry.value
        ? Math.max(0, Math.ceil((Date.parse(entry.value.expiresAt) - now.value) / 1000)) : 0)
    const countdown = computed(() => `${Math.floor(secondsRemaining.value / 60)}:${String(secondsRemaining.value % 60).padStart(2, '0')}`)
    let timer: ReturnType<typeof setInterval> | undefined
    let controller: AbortController | undefined
    let revision = 0
    let disposed = false

    function checkExpiration() {
        now.value = Date.now()
        if (entry.value && !(Date.parse(entry.value.expiresAt) > now.value)) {
            clearInterval(timer)
            entry.value = null
            draft.value = ''
            customId.value = ''
            customIdError.value = ''
            copyMessage.value = ''
            copying.value = false
            expired.value = true
        }
    }

    function accept(value: ClipboardInfo) {
        entry.value = value
        draft.value = ''
        customId.value = ''
        customIdError.value = ''
        checkExpiration()
        if (entry.value) timer = setInterval(checkExpiration, 1000)
    }

    function reset() {
        revision++
        controller?.abort()
        clearInterval(timer)
        entry.value = null
        draft.value = ''
        customId.value = ''
        customIdError.value = ''
        loading.value = false
        saving.value = false
        expired.value = false
        error.value = ''
        copying.value = false
        copyMessage.value = ''
        copyFailed.value = false
    }

    async function save() {
        if (disposed || saving.value || entry.value) return
        error.value = ''
        customIdError.value = ''
        if (!draft.value.trim()) {
            error.value = '请输入要分享的内容'
            return
        }
        if (draft.value.length > MAX_CLIPBOARD_LENGTH) {
            error.value = '内容不能超过 100,000 个字符'
            return
        }
        const requestedId = customId.value.trim()
        if (requestedId && !/^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$/.test(requestedId)) {
            customIdError.value = '请使用 3–64 位英文字母、数字、下划线或短横线，并以字母或数字开头。'
            return
        }
        const current = ++revision
        saving.value = true
        error.value = ''
        expired.value = false
        controller = new AbortController()
        try {
            const saved = await api<ClipboardInfo>('/clipboards', {
                method: 'POST', body: JSON.stringify({content: draft.value, ...(requestedId ? {customId: requestedId} : {})}), signal: controller.signal
            })
            if (current === revision && !disposed) accept(saved)
        } catch (cause) {
            if (current === revision && !disposed) {
                if (requestedId && cause instanceof ApiError && cause.status === 409) {
                    customIdError.value = '这个自定义链接正在使用中，请换一个。'
                } else error.value = (cause as Error).message
            }
        } finally {
            if (current === revision && !disposed) saving.value = false
        }
    }

    async function load(id: string) {
        if (disposed) return
        reset()
        const current = revision
        loading.value = true
        controller = new AbortController()
        try {
            const saved = await api<ClipboardInfo>(`/clipboards/${encodeURIComponent(id)}`, {signal: controller.signal})
            if (current === revision && !disposed) accept(saved)
        } catch (cause) {
            if (current === revision && !disposed) error.value = (cause as Error).message
        } finally {
            if (current === revision && !disposed) loading.value = false
        }
    }

    async function copyEntry(link: boolean) {
        checkExpiration()
        if (disposed || copying.value || !entry.value) return
        const saved = entry.value
        copying.value = true
        copyMessage.value = ''
        copyFailed.value = false
        try {
            await copy(link ? saved.shareUrl : saved.content)
            checkExpiration()
            if (entry.value === saved && !disposed) copyMessage.value = link ? '链接已复制' : '内容已复制'
        } catch {
            checkExpiration()
            if (entry.value === saved && !disposed) {
                copyFailed.value = true
                copyMessage.value = link ? '自动复制失败，请选中分享链接手动复制。' : '自动复制失败，请选中内容手动复制。'
            }
        } finally {
            if (entry.value === saved && !disposed) copying.value = false
        }
    }

    function dispose() {
        reset()
        disposed = true
        stopCustomIdWatch()
    }

    return {draft, customId, customIdError, entry, loading, saving, expired, error, copying, copyMessage, copyFailed, countdown,
        save, load, reset, copyEntry, checkExpiration, dispose}
}
