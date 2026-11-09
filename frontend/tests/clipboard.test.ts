import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {api, ApiError, copy} from '../src/api'
import {MAX_CLIPBOARD_LENGTH, useClipboard} from '../src/useClipboard'
import type {ClipboardInfo} from '../src/types'

vi.mock('../src/api', async importOriginal => ({
    ...await importOriginal<typeof import('../src/api')>(), api: vi.fn(), copy: vi.fn()
}))
const request = vi.mocked(api)
const copyText = vi.mocked(copy)
const start = Date.parse('2026-11-09T01:00:00Z')
const saved = (extra: Partial<ClipboardInfo> = {}): ClipboardInfo => ({
    id: 'clipboard-1', content: '  中文\n<script>alert(1)</script>\t🙂  ',
    shareUrl: 'http://localhost:8088/clipboard/clipboard-1',
    createdAt: new Date(start).toISOString(), expiresAt: new Date(start + 600000).toISOString(), ...extra
})

function deferred<T>() {
    let resolve!: (value: T) => void
    const promise = new Promise<T>(done => { resolve = done })
    return {promise, resolve}
}

beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers(); vi.setSystemTime(start) })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

describe('public clipboard lifecycle', () => {
    it('saves the exact text once while a save is pending and clears the draft after success', async () => {
        const clipboard = useClipboard()
        const pending = deferred<ClipboardInfo>()
        request.mockReturnValueOnce(pending.promise)
        clipboard.draft.value = saved().content
        const saving = clipboard.save()
        await clipboard.save()
        expect(request).toHaveBeenCalledTimes(1)
        expect(request.mock.calls[0]![1]).toMatchObject({method: 'POST', body: JSON.stringify({content: saved().content})})
        pending.resolve(saved())
        await saving
        expect(clipboard.entry.value).toEqual(saved())
        expect(clipboard.draft.value).toBe('')
        expect(clipboard.countdown.value).toBe('10:00')
        expect(clipboard.saving.value).toBe(false)
        clipboard.dispose()
    })

    it('rejects blank and oversized content locally, counting UTF-16 like the server', async () => {
        const clipboard = useClipboard()
        clipboard.draft.value = ' \t\n '
        await clipboard.save()
        expect(clipboard.error.value).toContain('请输入')
        clipboard.draft.value = '🙂'.repeat(MAX_CLIPBOARD_LENGTH / 2) + 'a'
        await clipboard.save()
        expect(clipboard.error.value).toContain('100,000')
        expect(request).not.toHaveBeenCalled()
    })

    it('retains the input after a failed save without automatically repeating the mutation', async () => {
        const clipboard = useClipboard()
        request.mockRejectedValueOnce(new Error('网络中断'))
        clipboard.draft.value = '我的草稿'
        await clipboard.save()
        await vi.advanceTimersByTimeAsync(3000)
        expect(clipboard.draft.value).toBe('我的草稿')
        expect(clipboard.error.value).toBe('网络中断')
        expect(clipboard.saving.value).toBe(false)
        expect(request).toHaveBeenCalledTimes(1)
    })

    it('clears saved content and links at exactly ten minutes without extending on copy', async () => {
        const clipboard = useClipboard()
        request.mockResolvedValueOnce(saved())
        await clipboard.load('clipboard-1')
        await vi.advanceTimersByTimeAsync(599000)
        expect(clipboard.countdown.value).toBe('0:01')
        await clipboard.copyEntry(false)
        expect(copyText).toHaveBeenCalledWith(saved().content)
        await vi.advanceTimersByTimeAsync(1000)
        expect(clipboard.entry.value).toBeNull()
        expect(clipboard.expired.value).toBe(true)
        expect(clipboard.copyMessage.value).toBe('')
        expect(clipboard.draft.value).toBe('')
        expect(vi.getTimerCount()).toBe(0)
    })

    it('checks the absolute deadline after a frozen tab resumes and refuses to copy expired content', async () => {
        const clipboard = useClipboard()
        request.mockResolvedValueOnce(saved())
        await clipboard.load('clipboard-1')
        vi.setSystemTime(start + 600001)
        await clipboard.copyEntry(false)
        expect(copyText).not.toHaveBeenCalled()
        expect(clipboard.entry.value).toBeNull()
        expect(clipboard.expired.value).toBe(true)
    })

    it('does not display an already expired late GET response', async () => {
        const clipboard = useClipboard()
        const pending = deferred<ClipboardInfo>()
        request.mockReturnValueOnce(pending.promise)
        const loading = clipboard.load('clipboard-1')
        vi.setSystemTime(start + 600000)
        pending.resolve(saved())
        await loading
        expect(clipboard.entry.value).toBeNull()
        expect(clipboard.expired.value).toBe(true)
        expect(vi.getTimerCount()).toBe(0)
    })

    it('aborts the previous route read and ignores its late response', async () => {
        const clipboard = useClipboard()
        const old = deferred<ClipboardInfo>()
        request.mockReturnValueOnce(old.promise).mockResolvedValueOnce(saved({id: 'new', content: 'new content'}))
        const reading = clipboard.load('old')
        const signal = request.mock.calls[0]![1]!.signal!
        await clipboard.load('new')
        expect(signal.aborted).toBe(true)
        old.resolve(saved())
        await reading
        expect(clipboard.entry.value!.id).toBe('new')
        expect(clipboard.entry.value!.content).toBe('new content')
        clipboard.dispose()
    })

    it('ignores late save and copy results after reset or disposal', async () => {
        const clipboard = useClipboard()
        request.mockResolvedValueOnce(saved())
        await clipboard.load('clipboard-1')
        const pendingCopy = deferred<void>()
        copyText.mockReturnValueOnce(pendingCopy.promise)
        const copying = clipboard.copyEntry(true)
        clipboard.reset()
        pendingCopy.resolve()
        await copying
        expect(clipboard.copyMessage.value).toBe('')

        const pendingSave = deferred<ClipboardInfo>()
        request.mockReturnValueOnce(pendingSave.promise)
        clipboard.draft.value = 'draft'
        const saving = clipboard.save()
        const signal = request.mock.calls[1]![1]!.signal!
        clipboard.dispose()
        expect(signal.aborted).toBe(true)
        pendingSave.resolve(saved())
        await saving
        expect(clipboard.entry.value).toBeNull()
        expect(vi.getTimerCount()).toBe(0)
    })

    it('reports copy failures and preserves the selectable content for manual copying', async () => {
        const clipboard = useClipboard()
        request.mockResolvedValueOnce(saved())
        copyText.mockRejectedValueOnce(new Error('clipboard denied'))
        await clipboard.load('clipboard-1')
        await clipboard.copyEntry(false)
        expect(clipboard.copyFailed.value).toBe(true)
        expect(clipboard.copyMessage.value).toContain('选中内容手动复制')
        expect(clipboard.entry.value!.content).toBe(saved().content)
        clipboard.dispose()
    })
})

describe('optional custom clipboard links', () => {
    it('keeps blank custom names compatible with random links by omitting the field', async () => {
        const clipboard = useClipboard()
        clipboard.draft.value = 'random link content'
        clipboard.customId.value = '  \t '
        request.mockResolvedValueOnce(saved())
        await clipboard.save()
        expect(JSON.parse(String(request.mock.calls[0]![1]!.body))).toEqual({content: 'random link content'})
        expect(clipboard.customId.value).toBe('')
        clipboard.dispose()
    })

    it('trims the custom name, preserves case, and retains exact text in the request', async () => {
        const clipboard = useClipboard()
        clipboard.draft.value = saved().content
        clipboard.customId.value = '  Team-note_1  '
        request.mockResolvedValueOnce(saved({id: 'Team-note_1'}))
        await clipboard.save()
        expect(JSON.parse(String(request.mock.calls[0]![1]!.body))).toEqual({content: saved().content, customId: 'Team-note_1'})
        expect(clipboard.entry.value!.id).toBe('Team-note_1')
        expect(clipboard.customId.value).toBe('')
        clipboard.dispose()
    })

    it('retains both drafts on conflict, clears the field error on edit, and lets a renamed link save', async () => {
        const clipboard = useClipboard()
        clipboard.draft.value = saved().content
        clipboard.customId.value = 'team-note'
        request.mockRejectedValueOnce(new ApiError('链接已被占用', 409))
        await clipboard.save()
        expect(clipboard.draft.value).toBe(saved().content)
        expect(clipboard.customId.value).toBe('team-note')
        expect(clipboard.customIdError.value).toContain('请换一个')
        expect(clipboard.error.value).toBe('')
        expect(clipboard.saving.value).toBe(false)
        await vi.advanceTimersByTimeAsync(2000)
        expect(request).toHaveBeenCalledTimes(1)

        clipboard.customId.value = 'team-note-2'
        expect(clipboard.customIdError.value).toBe('')
        request.mockResolvedValueOnce(saved({id: 'team-note-2'}))
        await clipboard.save()
        expect(JSON.parse(String(request.mock.calls[1]![1]!.body))).toEqual({content: saved().content, customId: 'team-note-2'})
        expect(clipboard.customId.value).toBe('')
        expect(clipboard.draft.value).toBe('')
        expect(clipboard.entry.value!.id).toBe('team-note-2')
        clipboard.dispose()
    })

    it.each(['ab', 'a'.repeat(65), '_abc', '-abc', 'abc.def', 'a b', '中文1', 'a/b', 'aa\nb', 'a🙂'])
    ('rejects an invalid custom link before creating anything: %s', async customId => {
        const clipboard = useClipboard()
        clipboard.draft.value = 'content'
        clipboard.customId.value = customId
        await clipboard.save()
        expect(clipboard.customIdError.value).toContain('3–64')
        expect(clipboard.customId.value).toBe(customId)
        expect(clipboard.draft.value).toBe('content')
        expect(request).not.toHaveBeenCalled()
        clipboard.dispose()
    })

    it.each(['0_a', 'A'.repeat(64)])('accepts the allowed name boundaries: %s', async customId => {
        const clipboard = useClipboard()
        clipboard.draft.value = 'content'
        clipboard.customId.value = customId
        request.mockResolvedValueOnce(saved({id: customId}))
        await clipboard.save()
        expect(JSON.parse(String(request.mock.calls[0]![1]!.body))).toEqual({content: 'content', customId})
        clipboard.dispose()
    })

    it('preserves the custom name on a network failure and clears it with reset', async () => {
        const clipboard = useClipboard()
        clipboard.draft.value = 'draft'
        clipboard.customId.value = 'team-note'
        request.mockRejectedValueOnce(new Error('网络中断'))
        await clipboard.save()
        expect(clipboard.customId.value).toBe('team-note')
        expect(clipboard.draft.value).toBe('draft')
        expect(clipboard.error.value).toBe('网络中断')
        clipboard.reset()
        expect(clipboard.customId.value).toBe('')
        expect(clipboard.customIdError.value).toBe('')
        clipboard.dispose()
    })
})
