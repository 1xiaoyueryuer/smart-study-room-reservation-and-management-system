<script setup>
import ProtectedAttachment from './ProtectedAttachment.vue'
import SButton from './ui/SButton.vue'
defineProps({items:{type:Array,required:true},disabled:Boolean,uploading:Boolean,loadContent:Function})
const emit=defineEmits(['select','retry','remove'])
</script>
<template><section class="evidence-queue"><label>可选照片（最多3张）<input type="file" accept="image/png,image/jpeg" multiple :disabled="disabled||uploading" @change="emit('select',$event)" /></label><p class="muted">PNG/JPEG，每张不超过5MiB、单边10000像素、总像素2000万。所选材料必须全部上传成功，或由您明确移除；本地移除不删除已保存历史材料。</p><article v-for="item in items" :key="item.key" class="file-item"><p>{{item.file.name}} · {{item.file.size}}字节 · {{({queued:'等待上传',uploading:'上传中',uploaded:'已上传',error:'上传失败或待核对'})[item.phase]}}</p><p v-if="item.error" role="alert">{{item.error}}</p><ProtectedAttachment v-if="item.metadata" :attachment="item.metadata" :load-content="loadContent" /><div class="actions"><SButton size="sm" :disabled="disabled||uploading" @click="emit('retry',item)">{{item.phase==='uploaded'?'重新上传此照片':'重试上传此照片'}}</SButton><SButton size="sm" :disabled="disabled||uploading" @click="emit('remove',item)">从本次提交移除</SButton></div></article></section></template>
<style scoped>.evidence-queue{display:grid;gap:10px;min-width:0}.evidence-queue label{display:grid;gap:7px}.file-item{border:1px solid var(--border);padding:12px;border-radius:var(--r-md);display:grid;gap:8px;overflow-wrap:anywhere}.actions{display:flex;flex-wrap:wrap;gap:8px}</style>
