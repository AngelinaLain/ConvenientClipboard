import {computed, ref} from 'vue'
import {api} from './api'
import type {FileInfo, Page} from './types'

export interface AdminFileFilters {
    query: string;
    location: '' | FileInfo['location'];
    retention: '' | 'PERMANENT' | 'TEMPORARY'
}

export const emptyFileFilters = (): AdminFileFilters => ({query: '', location: '', retention: ''})

export function useAdminFiles() {
    const files = ref<FileInfo[]>([])
    const total = ref(0)
    const page = ref(0)
    const pageSize = ref(20)
    const filters = ref(emptyFileFilters())
    const loading = ref(false)
    const error = ref('')
    let revision = 0

    const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
    const firstItem = computed(() => total.value ? page.value * pageSize.value + 1 : 0)
    const lastItem = computed(() => Math.min((page.value + 1) * pageSize.value, total.value))
    const filtered = computed(() => !!(filters.value.query || filters.value.location || filters.value.retention))

    async function load() {
        const request = ++revision
        const snapshot = {...filters.value}
        const requestedSize = pageSize.value
        let requestedPage = page.value
        loading.value = true
        error.value = ''
        try {
            // A deletion can empty the last page. Re-read the new last page using the same filters.
            while (true) {
                const params = new URLSearchParams({
                    page: String(requestedPage), size: String(requestedSize), ...snapshot
                })
                const result = await api<Page<FileInfo>>(`/admin/files?${params}`)
                if (request !== revision) return
                const lastPage = Math.max(0, Math.ceil(result.total / requestedSize) - 1)
                if (requestedPage > lastPage) {
                    requestedPage = lastPage
                    continue
                }
                files.value = result.items
                total.value = result.total
                page.value = requestedPage
                return
            }
        } catch (e) {
            if (request !== revision) return
            error.value = (e as Error).message
            files.value = []
            total.value = 0
            page.value = 0
        } finally {
            if (request === revision) loading.value = false
        }
    }

    function search(next: AdminFileFilters) {
        filters.value = {...next, query: next.query.trim()}
        page.value = 0
        return load()
    }

    function changePage(next: number) {
        if (!Number.isInteger(next) || next < 0 || next >= pageCount.value || next === page.value) return
        page.value = next
        return load()
    }

    function changePageSize(next: number) {
        if (![10, 20, 50, 100].includes(next) || next === pageSize.value) return
        pageSize.value = next
        page.value = 0
        return load()
    }

    function dispose() {
        revision++
    }

    return {files, total, page, pageSize, filters, loading, error, pageCount, firstItem, lastItem,
        filtered, load, search, changePage, changePageSize, dispose}
}
