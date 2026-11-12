<script setup>
import { computed, ref } from 'vue'
import { userImportTemplate, previewUserImport, confirmUserImport } from '../../api/admin'
import { operationReason, validatePassword } from './userForm.js'
import { ROLE_LABELS } from '../../stores/auth'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SBanner from '../ui/SBanner.vue'

const emit = defineEmits(['imported', 'refresh'])
const file = ref(null)
const preview = ref(null)
const previewing = ref(false)
const confirming = ref(false)
const downloading = ref(false)
const initialPassword = ref('')
const reason = ref('')
const error = ref('')
const conflictRows = ref([])
const result = ref(null)
const unknownResult = ref(false)
const busy = computed(() => previewing.value || confirming.value)
const canConfirm = computed(() => !!file.value && !!preview.value && preview.value.total > 0 && preview.value.invalid_count === 0 && !busy.value && !unknownResult.value && !result.value)

function selectFile(event) {
  if (busy.value) return
  const selected = event.target.files?.[0] || null
  preview.value = null
  result.value = null
  conflictRows.value = []
  error.value = ''
  initialPassword.value = ''
  unknownResult.value = false
  if (selected && selected.size > 1024 * 1024) {
    file.value = null
    event.target.value = ''
    error.value = 'CSV文件不能超过1MiB'
    return
  }
  file.value = selected
}
async function downloadTemplate() {
  if (downloading.value) return
  downloading.value = true
  error.value = ''
  try {
    const blob = await userImportTemplate()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'campus-users-template.csv'
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (failure) {
    error.value = `模板下载失败：${failure.message}`
  } finally {
    downloading.value = false
  }
}
async function inspectFile() {
  if (!file.value || busy.value) return
  previewing.value = true
  preview.value = null
  error.value = ''
  conflictRows.value = []
  result.value = null
  initialPassword.value = ''
  unknownResult.value = false
  try {
    const response = await previewUserImport(file.value)
    preview.value = response.data
  } catch (failure) {
    error.value = failure.message
  } finally {
    previewing.value = false
  }
}
async function confirmImport() {
  if (!canConfirm.value) return
  error.value = ''
  conflictRows.value = []
  let password, operation
  try {
    password = validatePassword(initialPassword.value)
    operation = operationReason(reason.value)
  } catch (failure) {
    error.value = failure.message
    return
  }
  confirming.value = true
  try {
    const response = await confirmUserImport(file.value, preview.value.file_sha256, password, operation)
    result.value = response.data
    preview.value = null
    emit('imported', response.data)
  } catch (failure) {
    if (failure.status === null) {
      unknownResult.value = true
      error.value = '连接中断或请求超时，导入结果尚未确认。请刷新账号列表核对后重新预检，系统不会自动重发。'
    } else {
      error.value = failure.message
    }
    conflictRows.value = failure.details?.rows || []
    preview.value = null
  } finally {
    initialPassword.value = ''
    confirming.value = false
  }
}
</script>

<template>
  <section class="import-section" aria-label="校园账号CSV导入">
    <div class="card-title-row"><h3>CSV批量导入</h3><SButton size="sm" :loading="downloading" @click="downloadTemplate">下载CSV模板</SButton></div>
    <p class="muted">按模板表头填写CSV，文件中不要填密码；上传后先预检，再在下方设置初始密码。</p>
    <div class="import-actions"><label class="file-label">选择CSV文件<input type="file" accept=".csv,text/csv" :disabled="busy" @change="selectFile" /></label><SButton :disabled="!file || busy" :loading="previewing" @click="inspectFile">预检文件</SButton></div>
    <p v-if="file" class="muted">当前文件：{{ file.name }}（{{ file.size }}字节）</p>
    <SBanner v-if="error" type="error" :title="error"><SButton v-if="unknownResult" size="sm" @click="emit('refresh')">刷新账号列表核对</SButton></SBanner>
    <SBanner v-if="result" type="success" :title="`已成功导入${result.imported_count}个账号，请在账号列表核对`" />
    <p v-if="previewing" role="status">正在预检文件，尚未写入账号…</p>
    <template v-if="preview">
      <p role="status">共{{ preview.total }}条：有效{{ preview.valid_count }}条，有错误{{ preview.invalid_count }}条。预检不会创建账号。</p>
      <p v-if="preview.invalid_count" class="muted">请修正CSV中的错误后重新选择并预检，不能跳过错误记录导入。</p>
      <div class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="CSV预检逐记录结果，可左右滑动"><table class="table import-table"><thead><tr><th>记录号（含表头）</th><th>学号</th><th>用户名</th><th>姓名</th><th>角色</th><th>联系方式</th><th>预检结果</th></tr></thead><tbody><tr v-for="row in preview.rows" :key="row.row_number"><td>{{ row.row_number }}</td><td>{{ row.campus_id }}</td><td>{{ row.username }}</td><td>{{ row.real_name }}</td><td>{{ ROLE_LABELS[row.role] || row.role }}</td><td>{{ row.phone || '—' }}<br />{{ row.email || '—' }}</td><td>{{ row.errors.length ? row.errors.join('；') : '有效' }}</td></tr></tbody></table></div></div>
      <form class="import-confirm" @submit.prevent="confirmImport">
        <p class="muted">请通过安全渠道交付初始密码；首次登录强制改密暂未支持。</p>
        <label>本批初始密码<SInput v-model="initialPassword" type="password" show-password required minlength="6" maxlength="64" autocomplete="new-password" :disabled="busy || preview.invalid_count > 0" /></label>
        <label>导入操作理由<SInput v-model="reason" type="textarea" required maxlength="1000" :disabled="busy" /></label>
        <SButton type="submit" variant="primary" :loading="confirming" :disabled="!canConfirm">确认整批导入</SButton>
        <p v-if="confirming" role="status">正在导入，请勿重复提交；账号及审计会整批提交或整批回滚。</p>
      </form>
    </template>
    <ul v-if="conflictRows.length" class="conflict-list" aria-label="确认导入失败记录"><li v-for="row in conflictRows" :key="row.row_number">第{{ row.row_number }}条记录（{{ row.campus_id }} / {{ row.username }}）：{{ row.errors.join('；') }}</li></ul>
  </section>
</template>

<style scoped>
.import-section { border-top: 1px solid var(--border); margin-top: 24px; padding-top: 20px; }
.import-section > .muted { margin-bottom: 14px; }
.import-actions { display: flex; gap: 14px; flex-wrap: wrap; align-items: end; }
.file-label, .import-confirm label { display: grid; gap: 7px; font-size: var(--fs-caption); color: var(--text-2); }
.file-label input { max-width: 100%; }
.import-table { min-width: 800px; }
.import-confirm { display: grid; gap: 14px; margin-top: 16px; max-width: 600px; }
.conflict-list { padding-left: 20px; margin-top: 14px; color: var(--red-strong); }
@media (max-width: 560px) { .import-actions { align-items: stretch; flex-direction: column; } }
</style>
