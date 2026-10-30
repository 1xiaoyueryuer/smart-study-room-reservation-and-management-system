<script setup>
import {onBeforeUnmount,onMounted} from 'vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'

import {appealStatuses} from '../composables/appealForm.js'
import {listMyAppeals} from '../api/appeal'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
const pager=createRecordPager(listMyAppeals),fields=recordFields('appeal')
const {items,loaded,loading,error,filters,snapshot,snapshotQuery}=pager
const refresh=()=>pager.refresh()
onMounted(refresh);onBeforeUnmount(pager.dispose)
</script>
<template><section id="appeals" class="card appeals-panel"><div class="card-title-row"><h3>本人申诉进度</h3><SButton size="sm" :loading="loading" @click="refresh">刷新申诉进度</SButton></div><RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/><RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/><p>请从本人违规记录查看申诉条件；真实通知时间未留存时不推算期限。提交申诉不自动撤销违规或解除限制。</p><p v-if="loading" role="status">正在读取本人申诉…</p><SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试查询</SButton></SBanner><p v-else-if="!loaded">尚未读取本人申诉。</p><template v-if="loaded"><p v-if="!items.length&&!error&&!loading">本次查询尚无本人申诉。</p><ul><li v-for="item in items" :key="item.id"><RouterLink :to="`/appeals/${item.id}`">申诉#{{item.id}} · 违规#{{item.violation_id}} · {{appealStatuses[item.status]||item.status}}</RouterLink><p>{{item.submitted_at}}</p></li></ul></template></section></template>
<style scoped>.appeals-panel{display:grid;gap:12px;min-width:0;overflow-wrap:anywhere}.appeals-panel ul{display:grid;gap:12px;padding-left:22px}</style>
