<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import {
  ArrowRight,
  Check,
  CheckCircle2,
  CloudUpload,
  Copy,
  File,
  LockKeyhole,
  Pause,
  Play,
  Plus,
  Timer,
  X
} from 'lucide-vue-next'
import {copy, date, refreshPublicConfig, session, size} from '../api'
import type {FileInfo} from '../types'
import {formatUploadLimit, validateUploadBatch} from '../uploadPolicy'
import {useResumableUploads} from '../useResumableUploads'
import type {UploadItem} from '../useResumableUploads'

const uploads = useResumableUploads(() => session.user?.id || null)
const {items, active: uploading, pending: queued} = uploads
const input = ref<HTMLInputElement>();
const resumeInput = ref<HTMLInputElement>();
const resumeTarget = ref<UploadItem>();
const dragging = ref(false);
const permanent = ref(false);
const notice = ref('');
const copied = ref('');
let pollTimer: ReturnType<typeof setInterval> | undefined
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function select(files: FileList | File[] | null) {
  if (!files) return
  const selectedOwner = session.user?.id || null
  const selected = Array.from(files)
  if (input.value) input.value.value = ''
  if (!selected.length) return
  notice.value = ''
  try {
    const checked = await validateUploadBatch(selected)
    if (selectedOwner !== (session.user?.id || null)) return
    for (const {file, error} of checked) {
      if (error) {
        notice.value = error;
        continue
      }
      uploads.add(file, permanent.value && !!session.user)
    }
  } catch (e) {
    notice.value = `无法读取当前上传限制：${(e as Error).message}`
  }
}

function drop(event: DragEvent) {
  dragging.value = false;
  select(event.dataTransfer?.files || null)
}

async function start() {
  await uploads.startAll()
}

function progress(item: UploadItem) {
  return item.stage === 'hashing' ? item.hashProgress : Math.min(100, Math.floor(item.savedBytes / item.size * 100))
}
function statusText(item: UploadItem) {
  if (item.stage === 'hashing') return `正在校验原文件 ${item.hashProgress}%`
  if (item.stage === 'checking') return '正在查询服务器保存进度…'
  if (item.stage === 'merging') return '分片已保存，服务器正在合并…'
  if (item.stage === 'uploading') return `服务器已保存 ${size(item.savedBytes)} / ${size(item.size)}`
  if (item.stage === 'paused') return `已暂停 · 服务器已保存 ${size(item.savedBytes)}`
  if (item.stage === 'needs-file') return `请选择原文件继续 · 服务器已保存 ${size(item.savedBytes)}`
  if (item.stage === 'error') return item.record?.cancelled ? '取消待同步' : '已停止，可重试继续'
  if (item.stage === 'cancelling') return '正在取消并清理未完成分片…'
  return '等待上传'
}
function chooseOriginal(item: UploadItem) {
  resumeTarget.value = item
  resumeInput.value?.click()
}
async function resumeSelected(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  const item = resumeTarget.value
  element.value = ''
  if (file && item) await uploads.attach(item, file)
}

function beforeUnload(event: BeforeUnloadEvent) {
  event.preventDefault()
  event.returnValue = ''
}
watch(uploading, active => {
  if (active) window.addEventListener('beforeunload', beforeUnload)
  else window.removeEventListener('beforeunload', beforeUnload)
})
watch(() => session.user?.id || null, () => {
  permanent.value = false
  void uploads.restore()
}, {flush: 'sync'})
watch(permanent, value => {
  for (const item of items.value) if (!item.record && ['queued', 'paused', 'error'].includes(item.stage)) item.permanent = value && !!session.user
})
const suspend = () => uploads.suspend()
const pageShow = (event: PageTransitionEvent) => { if (event.persisted) void uploads.restore() }
const poll = () => { void uploads.poll() }

async function refreshConfig() {
  try {
    await refreshPublicConfig()
  } catch (e) {
    notice.value = `无法读取当前上传限制：${(e as Error).message}`
  }
}

onMounted(() => {
  void refreshConfig();
  void uploads.restore()
  pollTimer = setInterval(poll, 10000)
  window.addEventListener('focus', refreshConfig)
  window.addEventListener('online', poll)
  window.addEventListener('storage', poll)
  window.addEventListener('pagehide', suspend)
  window.addEventListener('pageshow', pageShow)
})
onUnmounted(() => {
  window.removeEventListener('focus', refreshConfig)
  window.removeEventListener('online', poll)
  window.removeEventListener('storage', poll)
  window.removeEventListener('pagehide', suspend)
  window.removeEventListener('pageshow', pageShow)
  window.removeEventListener('beforeunload', beforeUnload)
  clearInterval(pollTimer)
  clearTimeout(copyTimer)
  uploads.dispose()
})

async function copyLink(file: FileInfo) {
  try {
    await copy(file.shareUrl);
    copied.value = file.id;
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copied.value = ''
    }, 2200)
  } catch (e) {
    notice.value = (e as Error).message
  }
}
</script>
<template>
  <div class="upload-page page-width">
    <section class="upload-card" aria-label="文件上传">
      <div class="upload-heading"><span><span class="step-number">01</span>选择你的文件</span><span class="muted">单个文件最大 {{
          formatUploadLimit(session.config.maxUploadBytes)
        }}</span></div>
      <input ref="input" type="file" multiple class="visually-hidden" aria-label="选择上传文件"
             @change="select(($event.target as HTMLInputElement).files)">
      <input ref="resumeInput" type="file" class="visually-hidden" aria-label="选择原文件继续上传" @change="resumeSelected">
      <button class="drop-zone" :class="{ dragging }" @click="input?.click()"
              @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="drop"><span
          class="upload-illustration"><CloudUpload :size="36" :stroke-width="1.5"/></span><strong>{{
          dragging ? '松手' : '拖拽文件到这里'
        }}</strong><span>或<span class="link-color">点击选择文件<ArrowRight :size="14"/></span></span><small>支持任意类型文件，可一次选择多个 · 中断后可继续上传</small>
      </button>
      <div class="retention-row">
        <div class="retention-info">
          <Timer :size="18"/>
          <span>默认保存 <strong>{{
              session.config.retentionDays
            }} 天</strong><small>到期后自动删除，分享链接同时失效</small></span></div>
        <label v-if="session.user" class="switch-label"><input v-model="permanent" type="checkbox"
                                                               :disabled="uploading"><span class="switch"></span>永久保存</label>
        <RouterLink v-else to="/login" class="text-link">登录可永久保存
          <ArrowRight :size="14"/>
        </RouterLink>
      </div>
      <div v-if="notice" class="notice error" role="alert">{{ notice }}
        <button class="icon-button" aria-label="关闭提示" @click="notice = ''">
          <X :size="16"/>
        </button>
      </div>
      <p class="upload-resume-note">上传中断后，10 分钟内可回来续传；持续上传会延长恢复时间。超过中断期限后，服务器释放未完成分片，当前浏览器在页面打开时清理对应文件缓存。浏览器未缓存原文件时，请重新选择原文件继续。</p>
      <div v-if="items.length" class="upload-list">
        <div v-for="item in items" :key="item.id" class="upload-item"><span class="file-icon"
                                                                            :class="{ 'file-success': item.stage === 'done' }"><CheckCircle2
            v-if="item.stage === 'done'" :size="21"/><File v-else :size="21"/></span>
          <div class="file-detail"><strong :title="item.name">{{
              item.name
            }}</strong><span>{{ size(item.size) }} · {{ item.permanent ? '永久保存' : '临时保存' }}<template v-if="item.stage === 'done'"> · {{
              item.result?.permanent ? '永久保存' : `${date(item.result?.expiresAt || null)} 到期`
            }}</template><template v-else> · {{ statusText(item) }}</template></span>
            <div v-if="!item.result && item.stage !== 'queued'" class="progress-track" :aria-label="item.stage === 'hashing' ? '原文件校验进度' : '服务器保存进度'" role="progressbar" :aria-valuenow="progress(item)" aria-valuemin="0" aria-valuemax="100"><span
                :style="{ width: `${progress(item)}%` }"></span></div>
            <small v-if="item.cacheNotice && !item.result" class="upload-cache-note">{{ item.cacheNotice }}</small>
            <span v-if="item.error" class="inline-error">{{ item.error }}</span><a v-if="item.result"
                                                                                   :href="item.result.shareUrl"
                                                                                   class="result-url" target="_blank"
                                                                                   rel="noopener">{{
                item.result.shareUrl
              }}</a></div>
          <button v-if="item.result" class="button button-small button-outline" @click="copyLink(item.result)">
            <Check v-if="copied === item.result.id" :size="15"/>
            <Copy v-else :size="15"/>
            {{ copied === item.result.id ? '已复制' : '复制链接' }}
          </button>
          <div class="upload-item-actions">
            <button v-if="['hashing', 'checking', 'uploading', 'merging'].includes(item.stage)" class="text-button" @click="uploads.pause(item)"><Pause :size="15"/>暂停</button>
            <button v-else-if="!item.result && !item.record?.cancelled && item.stage !== 'cancelling'" class="text-button"
                    @click="item.source ? uploads.start(item) : chooseOriginal(item)"><Play :size="15"/>{{ item.source ? '继续' : '选择原文件' }}</button>
            <button class="icon-button" :aria-label="item.result ? '移除上传记录' : '取消上传'" :title="item.result ? '移除上传记录' : '取消上传并清理分片'"
                    :disabled="item.stage === 'cancelling'" @click="uploads.cancel(item)"><X :size="17"/></button>
          </div>
        </div>
      </div>
      <div class="upload-actions"><span><LockKeyhole :size="14"/>文件通过随机链接访问</span>
        <button v-if="queued.length" class="button button-primary" :disabled="uploading" @click="start">
          {{ uploading ? '正在上传…' : `上传${queued.length > 1 ? ` ${queued.length} 个` : ''}文件并生成链接` }}
          <ArrowRight :size="17"/>
        </button>
        <button v-else class="button button-primary" :disabled="uploading" @click="input?.click()">
          {{ items.length ? '继续添加文件' : '选择文件' }}
          <Plus :size="17"/>
        </button>
      </div>
    </section>
  </div>
</template>
