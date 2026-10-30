<script setup>
import SButton from './ui/SButton.vue'
const props=defineProps({snapshot:Object,query:Object,fields:Array,disabled:Boolean})
const emit=defineEmits(['page'])
function label(key,value){const field=props.fields?.find(f=>f.key===key);return field?(field.label+'：'+(field.options?.find(o=>o.value===value)?.label||value)):null}
</script>
<template><section v-if="snapshot" class="record-pages"><p>最近成功读取：{{snapshot.server_now}}；共{{snapshot.total}}条，第{{snapshot.page}}页，每页{{snapshot.page_size}}条。</p><p>该快照条件：<template v-for="(value,key) in query" :key="key"><span v-if="label(key,value)">{{label(key,value)}}；</span></template>未填写项为全部范围。</p><div><SButton :disabled="disabled||snapshot.page<=1" @click="emit('page',snapshot.page-1)">上一页</SButton><SButton :disabled="disabled||snapshot.page*snapshot.page_size>=snapshot.total" @click="emit('page',snapshot.page+1)">下一页</SButton></div></section></template>
<style scoped>.record-pages{display:grid;gap:8px;min-width:0;overflow-wrap:anywhere}.record-pages div{display:flex;gap:12px;flex-wrap:wrap}</style>
