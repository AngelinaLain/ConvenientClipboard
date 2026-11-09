<script setup lang="ts">
import {ArrowDown, ArrowUp, Gauge} from 'lucide-vue-next'
import type {StorageSpeedTest} from '../types'
import type {StorageSpeedTestState} from '../useStorageSpeedTests'
import {speedRate} from '../useStorageSpeedTests'

defineProps<{
  state?: StorageSpeedTestState;
  active: boolean;
  disabled: boolean
}>()
defineEmits<{start: []; resume: []}>()

const stages: Record<StorageSpeedTest['status'], string> = {
  PREPARING: '正在准备测试文件…',
  UPLOADING: '正在测试上传速度…',
  DOWNLOADING: '正在测试下载速度…',
  CLEANING: '正在清理测试文件…',
  COMPLETED: '测速完成',
  FAILED: '测速失败'
}

function duration(value: number | null) {
  return value === null ? '' : `用时 ${(value / 1000).toFixed(2)} 秒`
}

function finishedAt(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', {hour12: false})
}
</script>

<template>
  <section class="provider-speed-test" aria-label="远端存储测速">
    <div class="speed-test-header">
      <span class="speed-test-heading"><Gauge :size="15"/>传输测速</span>
      <button class="text-button" :disabled="disabled || active" @click="$emit('start')">
        {{ active ? '测速中…' : state?.test || state?.error ? '重新测速' : '开始测速' }}
      </button>
    </div>
    <template v-if="state?.test">
      <div class="speed-test-results">
        <div><span><ArrowUp :size="13"/>上传</span><strong>{{ speedRate(state.test.uploadBytesPerSecond) }}</strong>
          <small>服务器 → 远端</small><small v-if="state.test.uploadDurationMillis !== null">{{ duration(state.test.uploadDurationMillis) }}</small>
        </div>
        <div><span><ArrowDown :size="13"/>下载</span><strong>{{ speedRate(state.test.downloadBytesPerSecond) }}</strong>
          <small>远端 → 服务器</small><small v-if="state.test.downloadDurationMillis !== null">{{ duration(state.test.downloadDurationMillis) }}</small>
        </div>
      </div>
      <p class="speed-test-status" role="status">
        <span v-if="active && !state.error" class="spinner"></span>
        {{ state.error ? (active ? '测速仍在服务器后台运行' : '测速进度不可用') : stages[state.test.status] }}
        <span v-if="state.test.finishedAt"> · {{ finishedAt(state.test.finishedAt) }}</span>
      </p>
      <p class="speed-test-caption">测试数据 {{ (state.test.sampleBytes / 1024 ** 2).toFixed(0) }} MiB / 方向</p>
      <p v-if="state.test.error" class="speed-test-error" role="alert">{{ state.test.error }}</p>
      <p v-if="state.test.warning" class="speed-test-warning" role="alert">{{ state.test.warning }}</p>
    </template>
    <p v-else-if="active" class="speed-test-status" role="status"><span class="spinner"></span>正在启动测速…</p>
    <p v-else class="speed-test-caption">使用 16 MiB 临时文件测量上传和下载，结束后自动清理。</p>
    <p v-if="state?.error" class="speed-test-error" role="alert">{{ state.error }}</p>
    <button v-if="active && state?.error" class="text-button" :disabled="state.reading" @click="$emit('resume')">
      {{ state.reading ? '正在读取…' : '重新读取进度' }}
    </button>
  </section>
</template>
