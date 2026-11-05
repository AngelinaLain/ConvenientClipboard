<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {Check, Copy, File, FolderOpen, Plus, RefreshCw, Trash2} from 'lucide-vue-next'
import {api, copy, date, size} from '../api'
import type {FileInfo} from '../types'

const files = ref<FileInfo[]>([]);
const loading = ref(true);
const error = ref('');
const copied = ref('');
const busy = ref('');
const deleting = ref<FileInfo | null>(null)

async function load() {
  loading.value = true;
  error.value = '';
  try {
    files.value = await api<FileInfo[]>('/files')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function retention(file: FileInfo) {
  if (busy.value) return;
  busy.value = file.id;
  error.value = '';
  try {
    const updated = await api<FileInfo>(`/files/${file.id}/retention`, {
      method: 'PATCH',
      body: JSON.stringify({permanent: !file.permanent})
    });
    files.value = files.value.map(value => value.id === updated.id ? updated : value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}

async function remove() {
  if (!deleting.value) return;
  busy.value = deleting.value.id;
  try {
    await api(`/files/${deleting.value.id}`, {method: 'DELETE'});
    files.value = files.value.filter(file => file.id !== deleting.value?.id);
    deleting.value = null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
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

onMounted(load)
</script>
<template>
  <div class="page-width content-page">
    <div class="page-title">
      <div>
        <div class="eyebrow">YOUR FILES</div>
        <h1>我的文件</h1>
        <p class="muted">分享、保存和管理，文件尽在掌握。</p></div>
      <RouterLink to="/" class="button button-primary">
        <Plus :size="17"/>
        上传文件
      </RouterLink>
    </div>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <section class="panel">
      <div class="panel-header"><h2>全部文件 <span class="count-badge">{{ files.length }}</span></h2>
        <button class="text-button" :disabled="loading" @click="load">
          <RefreshCw :size="15"/>
          刷新
        </button>
      </div>
      <div v-if="loading" class="empty-state"><span class="spinner"></span>正在读取文件…</div>
      <div v-else-if="!files.length" class="empty-state">
        <FolderOpen :size="42" :stroke-width="1.3"/>
        <h3>这里还没有文件</h3>
        <p>登录后上传的文件会出现在这里。</p>
        <RouterLink to="/" class="button button-outline">上传第一个文件</RouterLink>
      </div>
      <div v-else class="table-scroll">
        <table>
          <thead>
          <tr>
            <th>文件名称</th>
            <th>保存方式</th>
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
                  }} · {{ date(file.createdAt) }}</small></div>
              </div>
            </td>
            <td><span class="badge" :class="file.permanent ? 'badge-teal' : 'badge-neutral'">{{
                file.permanent ? '永久保存' : '临时保存'
              }}</span></td>
            <td class="muted nowrap">{{ date(file.expiresAt) }}</td>
            <td>
              <div class="table-actions">
                <button class="icon-button" :aria-label="copied === file.id ? '已复制' : '复制分享链接'"
                        :title="copied === file.id ? '已复制' : '复制分享链接'" @click="copyLink(file)">
                  <Check v-if="copied === file.id" :size="17"/>
                  <Copy v-else :size="17"/>
                </button>
                <button class="text-button" :disabled="!!busy" @click="retention(file)">
                  {{ file.permanent ? '改为临时' : '永久保存' }}
                </button>
                <button class="icon-button danger" aria-label="删除文件" :disabled="!!busy" @click="deleting = file">
                  <Trash2 :size="17"/>
                </button>
              </div>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p class="page-note">改为临时保存后，文件将重新开始 7 天有效期。删除后分享链接立即失效。</p></div>
  <div v-if="deleting" class="modal-backdrop" @click.self="deleting = null">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="delete-title"><h2 id="delete-title">
      删除这个文件？</h2>
      <p>“{{ deleting.originalName }}”将被删除，已有分享链接会失效。</p>
      <div class="modal-actions">
        <button class="button button-outline" :disabled="!!busy" @click="deleting = null">取消</button>
        <button class="button button-danger" :disabled="!!busy" @click="remove">{{
            busy ? '正在删除…' : '确认删除'
          }}
        </button>
      </div>
    </section>
  </div>
</template>
