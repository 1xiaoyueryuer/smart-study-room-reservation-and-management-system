<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getComplaintAttachment } from '../api/complaint'
import SButton from './ui/SButton.vue'
const props = defineProps({ attachment: {type:Object,required:true} })
const url = ref(''), loading = ref(false), error = ref('')
let sequence = 0, alive = true
function release() { if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
async function load() {
  const ticket = ++sequence
  loading.value = true; error.value = ''; release()
  try { const blob = await getComplaintAttachment(props.attachment.id); if (alive && ticket === sequence) url.value = URL.createObjectURL(blob) }
  catch (failure) { if (alive && ticket === sequence) error.value = failure.message }
  finally { if (alive && ticket === sequence) loading.value = false }
}
async function download() {
  if (!url.value) await load()
  if (!url.value || !alive) return
  const link = document.createElement('a'); link.href = url.value; link.download = props.attachment.display_name; document.body.append(link); link.click(); link.remove()
}
watch(() => props.attachment.id, load, {immediate:true})
onBeforeUnmount(() => { alive = false; sequence++; release() })
</script>
<template><article class="protected-attachment"><p>{{ attachment.display_name }} · {{ attachment.size_bytes }}字节 · {{ attachment.width }}×{{ attachment.height }}</p><p v-if="attachment.expires_at" class="muted">临时材料有效至{{ attachment.expires_at }}；过期或读取失败时请回表单重新选择或上传，不会自动延期。</p><p v-else class="muted">已关联的历史材料。</p><p v-if="loading" role="status">正在鉴权读取照片…</p><p v-if="error" role="alert">{{ error }}</p><img v-if="url" :src="url" :alt="attachment.display_name" @error="error='照片无法显示，请重试读取'" /><div class="attachment-actions"><SButton size="sm" :loading="loading" @click="load">重新读取</SButton><SButton size="sm" :disabled="loading" @click="download">下载材料</SButton></div></article></template>
<style scoped>.protected-attachment{display:grid;gap:8px;border:1px solid var(--border);padding:12px;border-radius:var(--r-md);min-width:0;overflow-wrap:anywhere}.protected-attachment img{width:100%;max-height:320px;object-fit:contain}.attachment-actions{display:flex;gap:8px;flex-wrap:wrap}</style>
