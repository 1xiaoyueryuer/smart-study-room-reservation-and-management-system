<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { listMyReminders } from '../api/complaint'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import SEmpty from './ui/SEmpty.vue'
const items=ref([]),loading=ref(false),loaded=ref(false),error=ref('')
let sequence=0,alive=true
async function load(){const ticket=++sequence;loading.value=true;error.value='';try{const response=await listMyReminders();if(alive&&ticket===sequence){if(!Array.isArray(response.data))throw new Error('提醒记录返回不完整');items.value=response.data;loaded.value=true}}catch(failure){if(alive&&ticket===sequence)error.value=failure.message}finally{if(alive&&ticket===sequence)loading.value=false}}
onMounted(load)
onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<template>
  <section class="card reminder-panel">
    <div class="card-title-row"><h3>本人提醒（不计违规）</h3><SButton size="sm" :loading="loading" @click="load">刷新</SButton></div>
    <p v-if="loading" role="status">正在读取本人提醒…</p>
    <SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="load">重试</SButton></SBanner>
    <div class="record-box">
      <ul v-if="loaded&&items.length" class="reminder-list">
        <li v-for="item in items" :key="item.id">
          {{ item.public_note }}
          <p>事实发生：{{ item.occurred_at }}；登记：{{ item.registered_at }}。不计违规。</p>
        </li>
      </ul>
      <SEmpty v-else-if="loaded&&!loading&&!error" description="暂无本人提醒记录" />
      <p v-else-if="!loaded&&!loading&&!error">尚未读取提醒记录。</p>
    </div>
  </section>
</template>
<style scoped>
.reminder-panel{display:grid;gap:16px;overflow-wrap:anywhere}
.record-box{border:1px solid var(--border);border-radius:var(--r-lg);padding:16px;display:grid;gap:12px;min-height:120px}
.reminder-list{display:grid;gap:12px;padding:0;margin:0;list-style:none}
.reminder-list li{display:grid;gap:4px;padding-bottom:10px;border-bottom:1px solid var(--hairline)}
.reminder-list li:last-child{border-bottom:none;padding-bottom:0}
.reminder-list p{margin:0;color:var(--text-3);font-size:var(--fs-caption)}
</style>
