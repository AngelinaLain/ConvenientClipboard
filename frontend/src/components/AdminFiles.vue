<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import {Check, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Copy, File, RefreshCw, Search, Trash2} from 'lucide-vue-next'
import {api, copy, date, size} from '../api'
import type {FileInfo} from '../types'
import {emptyFileFilters, useAdminFiles} from '../useAdminFiles'

const emit = defineEmits<{ changed: [] }>()
const {files, total, page, pageSize, loading, error: listError, pageCount, firstItem, lastItem,
  filtered, load, search: applySearch, changePage, changePageSize, dispose} = useAdminFiles()
const draft = reactive(emptyFileFilters())
const jumpPage = ref<number | string>(1)
const busy = ref(false);
const deleting = ref<FileInfo | null>(null);
const error = ref('');
const copied = ref('');
const success = ref('')
const pageNumbers = computed(() => {
  const start = Math.max(0, Math.min(page.value - 2, pageCount.value - 5))
  return Array.from({length: Math.min(5, pageCount.value)}, (_, index) => start + index)
})
watch(page, value => { jumpPage.value = value + 1 })

function search() {
  if (busy.value) return
  error.value = ''
  void applySearch(draft)
}

function reset() {
  Object.assign(draft, emptyFileFilters())
  search()
}

function jump() {
  const target = Number(jumpPage.value)
  if (Number.isInteger(target) && target >= 1 && target <= pageCount.value) void changePage(target - 1)
  jumpPage.value = page.value + 1
}

async function copyLink(file: FileInfo) {
  try {
    await copy(file.shareUrl);
    copied.value = file.id;
    setTimeout(() => {
      copied.value = ''
    }, 2200)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function remove() {
  if (!deleting.value || busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api(`/files/${deleting.value.id}`, {method: 'DELETE'});
    deleting.value = null;
    success.value = '文件已删除，原分享链接已失效';
    await load();
    emit('changed')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)
onUnmounted(dispose)
defineExpose({load})
</script>
<template>
  <div v-if="error && !deleting" class="notice error" role="alert">{{ error }}</div>
  <div v-if="listError" class="notice error" role="alert">{{ listError }}</div>
  <div v-if="success" class="notice success" role="status">{{ success }}</div>
  <section class="panel">
    <div class="panel-header"><h2>全部服务器文件 <span class="count-badge">{{ total }}</span></h2>
      <button class="text-button" :disabled="loading || busy" @click="load">
        <RefreshCw :size="15"/>
        刷新
      </button>
    </div>
    <form class="search-bar file-search-bar" @submit.prevent="search">
      <label class="search-input">
        <Search :size="16"/>
        <input v-model="draft.query" type="search" maxlength="200" placeholder="搜索文件名、上传用户或文件 ID"
               aria-label="搜索文件名、上传用户或文件 ID">
      </label>
      <label class="file-filter">存储位置<select v-model="draft.location" aria-label="筛选存储位置">
        <option value="">全部位置</option>
        <option value="LOCAL">本地</option>
        <option value="S3">S3</option>
        <option value="NFS">NFS</option>
        <option value="SMB">SMB</option>
      </select></label>
      <label class="file-filter">保存方式<select v-model="draft.retention" aria-label="筛选保存方式">
        <option value="">全部方式</option>
        <option value="PERMANENT">永久保存</option>
        <option value="TEMPORARY">临时保存</option>
      </select></label>
      <div class="file-search-actions">
        <button class="button button-small button-outline" :disabled="busy">搜索</button>
        <button type="button" class="text-button" :disabled="busy" @click="reset">重置</button>
      </div>
    </form>
    <div v-if="loading" class="empty-state"><span class="spinner"></span>正在读取文件…</div>
    <div v-else-if="listError" class="empty-state"><File :size="38"/><h3>文件列表读取失败</h3>
      <button class="button button-small button-outline" @click="load">重新加载</button></div>
    <div v-else-if="!files.length" class="empty-state">
      <File :size="38"/>
      <h3>{{ filtered ? '没有匹配的文件' : '还没有文件' }}</h3>
      <p>{{ filtered ? '试试其他关键词或调整筛选条件。' : '包含匿名用户和所有登录用户上传的文件' }}</p>
      <button v-if="filtered" class="button button-small button-outline" @click="reset">清空筛选</button></div>
    <div v-else class="table-scroll">
      <table>
        <thead>
        <tr>
          <th>文件名称</th>
          <th>上传用户</th>
          <th>存储位置</th>
          <th>保存方式</th>
          <th>上传时间</th>
          <th>到期时间</th>
          <th>操作</th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="file in files" :key="file.id">
          <td>
            <div class="table-file"><span class="file-icon"><File :size="19"/></span>
              <div><strong :title="file.originalName">{{ file.originalName }}</strong><small>{{
                  size(file.sizeBytes)
                }}</small></div>
            </div>
          </td>
          <td>{{ file.ownerName || '匿名用户' }}</td>
          <td><span class="badge badge-neutral">{{ file.location === 'LOCAL' ? '本地' : file.location }}</span></td>
          <td><span class="badge" :class="file.permanent ? 'badge-teal' : 'badge-neutral'">{{
              file.permanent ? '永久保存' : '临时保存'
            }}</span></td>
          <td class="nowrap muted">{{ date(file.createdAt) }}</td>
          <td class="nowrap muted">{{ date(file.expiresAt) }}</td>
          <td>
            <div class="table-actions">
              <button class="icon-button" :title="copied === file.id ? '已复制' : '复制链接'"
                      :aria-label="copied === file.id ? '已复制' : '复制链接'" @click="copyLink(file)">
                <Check v-if="copied === file.id" :size="17"/>
                <Copy v-else :size="17"/>
              </button>
              <button class="icon-button danger" aria-label="删除文件" :disabled="busy"
                      @click="deleting = file; error = ''">
                <Trash2 :size="17"/>
              </button>
            </div>
          </td>
        </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination file-pagination" :aria-busy="loading">
      <span role="status">{{ loading ? '正在读取文件…' : `共 ${total} 个文件 · 第 ${firstItem}–${lastItem} 条` }}</span>
      <label class="file-page-size">每页
        <select :value="pageSize" aria-label="每页文件数" :disabled="busy"
                @change="changePageSize(Number(($event.target as HTMLSelectElement).value))">
          <option v-for="count in [10, 20, 50, 100]" :key="count" :value="count">{{ count }} 条</option>
        </select>
      </label>
      <nav class="file-page-buttons" aria-label="文件列表分页">
        <button class="icon-button" aria-label="第一页文件" :disabled="loading || busy || page === 0"
                @click="changePage(0)"><ChevronsLeft :size="16"/></button>
        <button class="icon-button" aria-label="上一页文件" :disabled="loading || busy || page === 0"
                @click="changePage(page - 1)">
          <ChevronLeft :size="18"/>
        </button>
        <button v-for="number in pageNumbers" :key="number" class="icon-button file-page-number"
                :class="{'is-current': number === page}" :aria-label="`第 ${number + 1} 页文件`"
                :aria-current="number === page ? 'page' : undefined" :disabled="loading || busy"
                @click="changePage(number)">{{ number + 1 }}</button>
        <button class="icon-button" aria-label="下一页文件" :disabled="loading || busy || page + 1 >= pageCount"
                @click="changePage(page + 1)">
          <ChevronRight :size="18"/>
        </button>
        <button class="icon-button" aria-label="最后一页文件" :disabled="loading || busy || page + 1 >= pageCount"
                @click="changePage(pageCount - 1)"><ChevronsRight :size="16"/></button>
      </nav>
      <form class="file-page-jump" @submit.prevent="jump">第
        <input v-model="jumpPage" type="number" min="1" :max="pageCount" step="1" required
               aria-label="跳转到文件页码" :disabled="loading || busy">
        / {{ pageCount }} 页
        <button class="text-button" :disabled="loading || busy">跳转</button>
      </form>
    </div>
  </section>
  <p class="page-note">此处列出所有用户和匿名上传的文件，按创建时间排序。管理员可删除任意文件。</p>
  <div v-if="deleting" class="modal-backdrop" @click.self="!busy && (deleting = null)">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="admin-file-delete-title"><h2
        id="admin-file-delete-title">删除文件？</h2>
      <p>“{{ deleting.originalName }}”将被删除，分享链接会立即失效。</p>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div class="modal-actions">
        <button class="button button-outline" :disabled="busy" @click="deleting = null">取消</button>
        <button class="button button-danger" :disabled="busy" @click="remove">{{
            busy ? '正在删除…' : '确认删除'
          }}
        </button>
      </div>
    </section>
  </div>
</template>
