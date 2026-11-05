<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {Check, ChevronLeft, ChevronRight, Copy, File, RefreshCw, Search, Trash2} from 'lucide-vue-next'
import {api, copy, date, size} from '../api'
import type {FileInfo, Page} from '../types'

const emit = defineEmits<{ changed: [] }>()
const files = ref<FileInfo[]>([]);
const total = ref(0);
const page = ref(0);
const pageSize = 20;
const query = ref('');
const loading = ref(false);
const busy = ref(false);
const deleting = ref<FileInfo | null>(null);
const error = ref('');
const copied = ref('');
const success = ref('')
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const result = await api<Page<FileInfo>>(`/admin/files?page=${page.value}&size=${pageSize}&query=${encodeURIComponent(query.value.trim())}`);
    files.value = result.items;
    total.value = result.total;
    if (page.value >= pageCount.value) {
      page.value = pageCount.value - 1;
      await load()
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0;
  void load()
}

function changePage(value: number) {
  page.value = value;
  void load()
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
defineExpose({load})
</script>
<template>
  <div v-if="error && !deleting" class="notice error" role="alert">{{ error }}</div>
  <div v-if="success" class="notice success" role="status">{{ success }}</div>
  <section class="panel">
    <div class="panel-header"><h2>全部服务器文件 <span class="count-badge">{{ total }}</span></h2>
      <button class="text-button" :disabled="loading || busy" @click="load">
        <RefreshCw :size="15"/>
        刷新
      </button>
    </div>
    <form class="search-bar" @submit.prevent="search"><label class="search-input">
      <Search :size="16"/>
      <input v-model="query" maxlength="100" placeholder="搜索文件名或上传用户"
             aria-label="搜索文件名或上传用户"></label>
      <button class="button button-small button-outline" :disabled="loading">搜索</button>
    </form>
    <div v-if="loading" class="empty-state"><span class="spinner"></span>正在读取文件…</div>
    <div v-else-if="!files.length" class="empty-state">
      <File :size="38"/>
      <h3>{{ query ? '没有匹配的文件' : '还没有文件' }}</h3>
      <p>{{ query ? '这个关键词没有内容' : '包含匿名用户和所有登录用户上传的文件' }}</p></div>
    <div v-else class="table-scroll">
      <table>
        <thead>
        <tr>
          <th>文件名称</th>
          <th>上传用户</th>
          <th>存储位置</th>
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
    <div class="pagination"><span>共 {{ total }} 个文件 · 第 {{ page + 1 }} / {{ pageCount }} 页</span>
      <div>
        <button class="icon-button" aria-label="上一页文件" :disabled="loading || page === 0"
                @click="changePage(page - 1)">
          <ChevronLeft :size="18"/>
        </button>
        <button class="icon-button" aria-label="下一页文件" :disabled="loading || page + 1 >= pageCount"
                @click="changePage(page + 1)">
          <ChevronRight :size="18"/>
        </button>
      </div>
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
