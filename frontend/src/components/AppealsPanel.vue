<script setup>
import {onBeforeUnmount,onMounted,ref} from 'vue'
import {listMyAppeals} from '../api/appeal'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
const items=ref([]),loaded=ref(false),loading=ref(false),error=ref('');let alive=true,sequence=0
async function refresh(){const ticket=++sequence;loading.value=true;error.value='';try{const response=await listMyAppeals();if(!alive||ticket!==sequence)return;if(!Array.isArray(response.data))throw new Error('本人申诉列表返回不完整');items.value=response.data;loaded.value=true}catch(failure){if(alive&&ticket===sequence)error.value=failure.message}finally{if(alive&&ticket===sequence)loading.value=false}}
onMounted(refresh);onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<template><section id="appeals" class="card appeals-panel"><div class="card-title-row"><h3>本人申诉进度</h3><SButton size="sm" :loading="loading" @click="refresh">刷新申诉进度</SButton></div><p>请从本人违规记录查看申诉条件；真实通知时间未留存时不推算期限。提交申诉不自动撤销违规或解除限制。</p><p v-if="loading" role="status">正在读取本人申诉…</p><SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试查询</SButton></SBanner><p v-else-if="!loaded">尚未读取本人申诉。</p><template v-else><p v-if="!items.length">本次查询尚无本人申诉。</p><ul><li v-for="item in items" :key="item.id"><RouterLink :to="`/appeals/${item.id}`">申诉#{{item.id}} · 违规#{{item.violation_id}} · {{item.status==='submitted'?'待审核':item.status}}</RouterLink><p>{{item.submitted_at}}</p></li></ul></template></section></template>
<style scoped>.appeals-panel{display:grid;gap:12px;min-width:0;overflow-wrap:anywhere}.appeals-panel ul{display:grid;gap:12px;padding-left:22px}</style>
