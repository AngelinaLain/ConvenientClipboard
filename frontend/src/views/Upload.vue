<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, Check, CheckCircle2, CloudUpload, Copy, File, Infinity, Link2, LockKeyhole, Plus, Timer, X } from 'lucide-vue-next'
import { copy, date, refreshPublicConfig, session, size, uploadFile } from '../api'
import type { FileInfo } from '../types'
import { formatUploadLimit, validateUploadBatch } from '../uploadPolicy'
type Item = { id: number; file: File; progress: number; state: 'queued' | 'uploading' | 'done' | 'error'; result?: FileInfo; error?: string }
const input = ref<HTMLInputElement>(); const dragging = ref(false); const permanent = ref(false); const items = ref<Item[]>([]); const uploading = ref(false); const notice = ref(''); const copied = ref(''); let sequence = 0
const queued = computed(() => items.value.filter(item => item.state === 'queued' || item.state === 'error'))
async function select(files: FileList | File[] | null) {
  if (!files || uploading.value) return
  const selected = Array.from(files)
  if (input.value) input.value.value = ''
  if (!selected.length) return
  notice.value = ''
  try { for (const { file, error } of await validateUploadBatch(selected)) { if (error) { notice.value = error; continue }; items.value.push({ id: ++sequence, file, progress: 0, state: 'queued' }) } }
  catch (e) { notice.value = `无法读取当前上传限制：${(e as Error).message}` }
}
function drop(event: DragEvent) { dragging.value = false; select(event.dataTransfer?.files || null) }
async function start() {
  if (uploading.value) return
  const batch = [...queued.value]
  if (!batch.length) return
  uploading.value = true; notice.value = ''
  try {
    const checked = await validateUploadBatch(batch.map(item => item.file))
    for (const [index, item] of batch.entries()) {
      item.error = ''; item.progress = 0
      if (checked[index]!.error) { item.state = 'error'; item.error = checked[index]!.error!; continue }
      item.state = 'uploading'
      try { item.result = await uploadFile(item.file, permanent.value && !!session.user, value => { item.progress = value }); item.state = 'done' }
      catch (e) { item.state = 'error'; item.error = (e as Error).message }
    }
  } catch (e) { notice.value = `无法读取当前上传限制：${(e as Error).message}` }
  finally { uploading.value = false }
}
async function refreshConfig() { try { await refreshPublicConfig() } catch (e) { notice.value = `无法读取当前上传限制：${(e as Error).message}` } }
onMounted(() => { void refreshConfig(); window.addEventListener('focus', refreshConfig) })
onUnmounted(() => { window.removeEventListener('focus', refreshConfig) })
async function copyLink(file: FileInfo) { try { await copy(file.shareUrl); copied.value = file.id; setTimeout(() => { copied.value = '' }, 2200) } catch (e) { notice.value = (e as Error).message } }
</script>
<template>
  <div class="upload-page page-width">
    <div class="eyebrow"><span class="status-dot"></span>轻量分享，自在流转</div>
    <section class="hero"><h1>文件，即刻分享<span>简单，刚刚好。</span></h1><p>把文件放在这里，让链接替你传递。<br class="mobile-break">无需注册，打开就能用。</p></section>
    <section class="upload-card" aria-label="文件上传">
      <div class="upload-heading"><span><span class="step-number">01</span>选择你的文件</span><span class="muted">单个文件最大 {{ formatUploadLimit(session.config.maxUploadBytes) }}</span></div>
      <input ref="input" type="file" multiple class="visually-hidden" aria-label="选择上传文件" @change="select(($event.target as HTMLInputElement).files)">
      <button class="drop-zone" :class="{ dragging }" :disabled="uploading" @click="input?.click()" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="drop"><span class="upload-illustration"><CloudUpload :size="36" :stroke-width="1.5" /></span><strong>{{ dragging ? '松手，文件就到这里' : '拖拽文件到这里' }}</strong><span>或<span class="link-color">点击选择文件<ArrowRight :size="14" /></span></span><small>支持任意类型文件，可一次选择多个</small></button>
      <div class="retention-row"><div class="retention-info"><Timer :size="18" /><span>默认保存 <strong>{{ session.config.retentionDays }} 天</strong><small>到期后自动删除，分享链接同时失效</small></span></div><label v-if="session.user" class="switch-label"><input v-model="permanent" type="checkbox" :disabled="uploading"><span class="switch"></span>永久保存</label><RouterLink v-else to="/login" class="text-link">登录可永久保存<ArrowRight :size="14" /></RouterLink></div>
      <div v-if="notice" class="notice error" role="alert">{{ notice }}<button class="icon-button" aria-label="关闭提示" @click="notice = ''"><X :size="16" /></button></div>
      <div v-if="items.length" class="upload-list"><div v-for="item in items" :key="item.id" class="upload-item"><span class="file-icon" :class="{ 'file-success': item.state === 'done' }"><CheckCircle2 v-if="item.state === 'done'" :size="21" /><File v-else :size="21" /></span><div class="file-detail"><strong :title="item.file.name">{{ item.file.name }}</strong><span>{{ size(item.file.size) }}<template v-if="item.state === 'done'"> · {{ item.result?.permanent ? '永久保存' : `${date(item.result?.expiresAt || null)} 到期` }}</template><template v-else-if="item.state === 'uploading'"> · {{ item.progress === 99 ? '服务端正在保存…' : `上传中 ${item.progress}%` }}</template><template v-else> · {{ item.state === 'error' ? '上传失败' : '等待上传' }}</template></span><div v-if="item.state === 'uploading'" class="progress-track"><span :style="{ width: `${item.progress}%` }"></span></div><span v-if="item.error" class="inline-error">{{ item.error }}</span><a v-if="item.result" :href="item.result.shareUrl" class="result-url" target="_blank" rel="noopener">{{ item.result.shareUrl }}</a></div><button v-if="item.result" class="button button-small button-outline" @click="copyLink(item.result)"><Check v-if="copied === item.result.id" :size="15" /><Copy v-else :size="15" />{{ copied === item.result.id ? '已复制' : '复制链接' }}</button><button v-else-if="!uploading" class="icon-button" aria-label="移除文件" @click="items = items.filter(value => value.id !== item.id)"><X :size="17" /></button></div></div>
      <div class="upload-actions"><span><LockKeyhole :size="14" />文件通过随机链接访问</span><button v-if="queued.length" class="button button-primary" :disabled="uploading" @click="start">{{ uploading ? '正在上传…' : `上传${queued.length > 1 ? ` ${queued.length} 个` : ''}文件并生成链接` }}<ArrowRight :size="17" /></button><button v-else class="button button-primary" :disabled="uploading" @click="input?.click()">{{ items.length ? '继续添加文件' : '选择文件' }}<Plus :size="17" /></button></div>
    </section>
    <section class="benefits" aria-label="服务特性"><article><span class="benefit-icon"><Link2 :size="20" /></span><h3>一个链接，轻松送达</h3><p>上传完成即生成分享链接<br>复制，发送，就这么简单。</p></article><article><span class="benefit-icon"><Timer :size="20" /></span><h3>七天留存，及时清理</h3><p>临时文件自动到期<br>为每一次分享留出空间。</p></article><article><span class="benefit-icon"><Infinity :size="20" /></span><h3>值得留住，就永久保存</h3><p>登录后主动选择永久保存<br>在“我的文件”中随时管理。</p></article></section>
  </div>
</template>
