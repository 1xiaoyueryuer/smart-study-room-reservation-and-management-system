<script setup>
import {onBeforeUnmount,onMounted} from 'vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'

import {appealStatuses} from '../composables/appealForm.js'
import {listMyAppeals} from '../api/appeal'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import SEmpty from './ui/SEmpty.vue'
const pager=createRecordPager(listMyAppeals),fields=recordFields('appeal')
const {items,loaded,loading,error,filters,snapshot,snapshotQuery}=pager
const refresh=()=>pager.refresh()
onMounted(refresh);onBeforeUnmount(pager.dispose)
</script>
<template>
  <section id="appeals" class="card appeals-panel">
    <div class="card-title-row"><h3>本人申诉进度</h3><SButton size="sm" :loading="loading" @click="refresh">刷新</SButton></div>
    <RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/>
    <p v-if="loading" role="status">正在读取本人申诉…</p>
    <SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试查询</SButton></SBanner>
    <div class="record-box">
      <ul v-if="loaded&&items.length" class="appeal-list">
        <li v-for="item in items" :key="item.id">
          <RouterLink :to="`/appeals/${item.id}`">申诉#{{item.id}} · 违规#{{item.violation_id}} · {{appealStatuses[item.status]||item.status}}</RouterLink>
          <p>{{item.submitted_at}}</p>
        </li>
      </ul>
      <SEmpty v-else-if="loaded&&!error&&!loading" description="本次查询尚无本人申诉" />
      <p v-else-if="!loaded&&!loading&&!error">尚未读取本人申诉。</p>
    </div>
    <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/>
  </section>
</template>
<style scoped>
.appeals-panel{display:grid;gap:16px;min-width:0;overflow-wrap:anywhere}
.record-box{border:1px solid var(--border);border-radius:var(--r-lg);padding:16px;display:grid;gap:12px;min-height:120px}
.appeal-list{display:grid;gap:12px;padding:0;margin:0;list-style:none}
.appeal-list li{display:grid;gap:4px;padding-bottom:10px;border-bottom:1px solid var(--hairline)}
.appeal-list li:last-child{border-bottom:none;padding-bottom:0}
.appeal-list a{color:var(--primary);text-decoration:none;font-size:var(--fs-body-sm)}
.appeal-list a:hover{text-decoration:underline}
.appeal-list p{margin:0;color:var(--text-3);font-size:var(--fs-caption)}
</style>
