<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { listMyReminders } from '../api/complaint'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
const items=ref([]),loading=ref(false),loaded=ref(false),error=ref('')
let sequence=0,alive=true
async function load(){const ticket=++sequence;loading.value=true;error.value='';try{const response=await listMyReminders();if(alive&&ticket===sequence){if(!Array.isArray(response.data))throw new Error('提醒记录返回不完整');items.value=response.data;loaded.value=true}}catch(failure){if(alive&&ticket===sequence)error.value=failure.message}finally{if(alive&&ticket===sequence)loading.value=false}}
onMounted(load)
onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<template><section class="card reminder-panel"><div class="card-title-row"><h3>本人提醒（不计违规）</h3><SButton size="sm" :loading="loading" @click="load">刷新提醒</SButton></div><p>提醒与违规分别记录，本面板不用于计算违规次数。</p><p v-if="loading" role="status">正在读取本人提醒…</p><SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="load">重试</SButton></SBanner><p v-if="!loaded&&!loading&&!error">尚未读取提醒记录。</p><p v-if="loaded&&!loading&&!error&&!items.length">暂无本人提醒记录。</p><ul v-if="loaded&&!loading&&!error"><li v-for="item in items" :key="item.id">{{ item.public_note }}<p>事实发生：{{ item.occurred_at }}；登记：{{ item.registered_at }}。不计违规。</p></li></ul></section></template>
<style scoped>.reminder-panel{display:grid;gap:12px;overflow-wrap:anywhere}.reminder-panel ul{padding-left:18px}</style>
