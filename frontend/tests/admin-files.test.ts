import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {FileInfo, Page} from '../src/types'
import {emptyFileFilters, useAdminFiles} from '../src/useAdminFiles'
import {api} from '../src/api'

vi.mock('../src/api', () => ({api: vi.fn()}))
const request = vi.mocked(api)
const file = (id: string) => ({id, originalName: `${id}.txt`} as FileInfo)
const result = (items: FileInfo[], total: number, page = 0, size = 20): Page<FileInfo> => ({items, total, page, size})
const params = (call: number) => new URL(String(request.mock.calls[call]![0]), 'http://localhost').searchParams

function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason: Error) => void
    const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
    return {promise, resolve, reject}
}

beforeEach(() => { vi.resetAllMocks() })

describe('admin file search and pagination', () => {
    it('keeps applied search filters while paging, even when the search form is edited', async () => {
        const list = useAdminFiles()
        const draft = {...emptyFileFilters(), query: '  报告 & 100%  ', location: 'S3' as const, retention: 'PERMANENT' as const}
        request.mockResolvedValueOnce(result([file('first')], 45))
        await list.search(draft)
        draft.query = 'not submitted'
        draft.location = 'S3'
        request.mockResolvedValueOnce(result([file('second')], 45, 1))
        await list.changePage(1)
        expect(params(0).get('query')).toBe('报告 & 100%')
        expect(params(1).get('query')).toBe('报告 & 100%')
        expect(params(1).get('location')).toBe('S3')
        expect(params(1).get('retention')).toBe('PERMANENT')
        expect(params(1).get('page')).toBe('1')
        expect(list.firstItem.value).toBe(21)
        expect(list.lastItem.value).toBe(40)
        expect(list.pageCount.value).toBe(3)
    })

    it('resets the page when searching or changing page size and retains applied filters', async () => {
        const list = useAdminFiles()
        request.mockResolvedValue(result([file('match')], 150))
        await list.search({...emptyFileFilters(), query: 'report'})
        await list.changePage(4)
        await list.changePageSize(50)
        expect(params(2).get('page')).toBe('0')
        expect(params(2).get('size')).toBe('50')
        expect(params(2).get('query')).toBe('report')
        expect(list.pageCount.value).toBe(3)
        await list.changePage(2)
        await list.search({...emptyFileFilters(), query: 'new'})
        expect(params(4).get('page')).toBe('0')
        expect(params(4).get('query')).toBe('new')
    })

    it('returns to the new last page after deletion removes the only item on a page', async () => {
        const list = useAdminFiles()
        request.mockResolvedValueOnce(result([file('first')], 21))
        await list.load()
        request.mockResolvedValueOnce(result([file('last')], 21, 1))
        await list.changePage(1)
        request.mockResolvedValueOnce(result([], 20, 1)).mockResolvedValueOnce(result([file('remaining')], 20))
        await list.load()
        expect(params(2).get('page')).toBe('1')
        expect(params(3).get('page')).toBe('0')
        expect(list.page.value).toBe(0)
        expect(list.files.value.map(value => value.id)).toEqual(['remaining'])
        expect(list.total.value).toBe(20)
    })

    it('ignores an older response and keeps loading until the current search finishes', async () => {
        const list = useAdminFiles()
        const old = deferred<Page<FileInfo>>()
        const latest = deferred<Page<FileInfo>>()
        request.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
        const first = list.search({...emptyFileFilters(), query: 'old'})
        const second = list.search({...emptyFileFilters(), query: 'latest'})
        old.resolve(result([file('old')], 100))
        await first
        expect(list.files.value).toEqual([])
        expect(list.loading.value).toBe(true)
        latest.resolve(result([file('latest')], 1))
        await second
        expect(list.files.value.map(value => value.id)).toEqual(['latest'])
        expect(list.total.value).toBe(1)
        expect(list.loading.value).toBe(false)
    })

    it('does not let an earlier failure erase successful newer results', async () => {
        const list = useAdminFiles()
        const old = deferred<Page<FileInfo>>()
        request.mockReturnValueOnce(old.promise).mockResolvedValueOnce(result([file('latest')], 1))
        const first = list.load()
        await list.search({...emptyFileFilters(), query: 'latest'})
        old.reject(new Error('old request failed'))
        await first
        expect(list.files.value.map(value => value.id)).toEqual(['latest'])
        expect(list.error.value).toBe('')
    })

    it('clears stale results on failure and can reload successfully', async () => {
        const list = useAdminFiles()
        request.mockResolvedValueOnce(result([file('old')], 100))
        await list.load()
        request.mockRejectedValueOnce(new Error('网络连接失败'))
        await list.search({...emptyFileFilters(), query: 'new'})
        expect(list.error.value).toBe('网络连接失败')
        expect(list.files.value).toEqual([])
        expect(list.total.value).toBe(0)
        request.mockResolvedValueOnce(result([file('new')], 1))
        await list.load()
        expect(list.error.value).toBe('')
        expect(list.files.value.map(value => value.id)).toEqual(['new'])
    })

    it('ignores responses after leaving the file list', async () => {
        const list = useAdminFiles()
        const pending = deferred<Page<FileInfo>>()
        request.mockReturnValueOnce(pending.promise)
        const loading = list.load()
        list.dispose()
        pending.resolve(result([file('late')], 1))
        await loading
        expect(list.files.value).toEqual([])
    })

    it('keeps empty results at page one and rejects invalid page navigation', async () => {
        const list = useAdminFiles()
        request.mockResolvedValueOnce(result([], 0))
        await list.load()
        await list.changePage(-1)
        await list.changePage(1)
        await list.changePage(0.5)
        await list.changePageSize(1000)
        expect(request).toHaveBeenCalledTimes(1)
        expect(list.pageCount.value).toBe(1)
        expect(list.firstItem.value).toBe(0)
        expect(list.lastItem.value).toBe(0)
    })
})
