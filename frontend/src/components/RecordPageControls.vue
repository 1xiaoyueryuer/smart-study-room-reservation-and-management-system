<script setup>
import SButton from './ui/SButton.vue'
const props=defineProps({snapshot:Object,query:Object,fields:Array,disabled:Boolean})
const emit=defineEmits(['page'])
</script>
<template><section v-if="snapshot" class="record-pages"><p class="record-count">共 {{ snapshot.total }} 条</p><p class="record-page">第 {{ snapshot.page }} / {{ Math.max(1, Math.ceil(snapshot.total / snapshot.page_size)) }} 页</p><div class="record-actions"><SButton :disabled="disabled||snapshot.page<=1" @click="emit('page',snapshot.page-1)">上一页</SButton><SButton :disabled="disabled||snapshot.page*snapshot.page_size>=snapshot.total" @click="emit('page',snapshot.page+1)">下一页</SButton></div></section></template>
<style scoped>
.record-pages{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:12px;margin-top:12px}
.record-count{margin:0;color:var(--text-3);font-size:var(--fs-caption)}
.record-page{margin:0;color:var(--text-3);font-size:var(--fs-caption);text-align:center;white-space:nowrap}
.record-actions{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end}
@media(max-width:640px){.record-pages{grid-template-columns:1fr auto}.record-page{grid-column:1 / -1;grid-row:2;text-align:left}}
</style>
