<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { listMyComplaints } from '../api/complaint'
import { complaintTypes, complaintStatuses } from '../composables/complaintForm.js'
import { useServiceTargetClock } from '../composables/useServiceTargetClock.js'
import ServiceTargetSummary from '../components/ServiceTargetSummary.vue'
import ComplaintForm from '../components/ComplaintForm.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
import SEmpty from '../components/ui/SEmpty.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'
const pager=createRecordPager(listMyComplaints),fields=recordFields('complaint')
const {items,loading,loaded,error,filters,snapshot,snapshotQuery}=pager
const route=useRoute(),lastCreated=ref(null),formComponent=ref(null)
let sequence=0,alive=true
const targetClock=useServiceTargetClock({records:items,blocked:computed(()=>loading.value||!!formComponent.value?.targetReadBlocked),onDue:()=>load()})
const load=()=>pager.refresh()
function created(id) {lastCreated.value=id;return load()}
onMounted(load)
onBeforeUnmount(()=>{alive=false;sequence++;pager.dispose()})
</script>
<template><div class="page-view complaints-page"><header class="view-heading" data-page-title><h1>求助与投诉</h1><p class="heading-sub">提交情况、保留可选材料，查看本人记录；提交后等待受理。</p></header><div class="complaints-columns"><ComplaintForm ref="formComponent" :initial-type="String(route.query.type || 'service')" :initial-reservation-id="route.query.reservation_id || null" @created="created" /><section class="card records"><div class="card-title-row"><h3>本人记录</h3><SButton :loading="loading" @click="load">刷新列表</SButton></div><RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/><RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/><p v-if="loading" role="status">正在读取本人求助与投诉…</p><SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="load">重试列表</SButton></SBanner><SEmpty v-if="loaded && !loading && !error && !items.length" description="尚无本人求助或投诉，可仅文字提交" /><p v-if="!loaded && !loading && !error">尚未读取本人记录。</p><p v-if="loaded&&error">下列保留上次成功读取的快照，请刷新核对真实进度。</p><ul v-if="loaded && !loading" class="complaint-list"><li v-for="item in items" :key="item.id"><RouterLink :to="`/complaints/${item.id}`">#{{ item.id }} · {{ complaintTypes[item.type] || item.type }} · {{ complaintStatuses[item.status] || item.status }}</RouterLink><p>{{ item.submitted_at }} · 附件{{ item.attachment_count }}张</p><p class="record-description">{{ item.description }}</p><ServiceTargetSummary :record="item" :progress="targetClock.progress(item)" compact /></li></ul></section></div></div></template>
<style scoped>.complaints-columns{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr);gap:20px;align-items:start}.records{min-width:0}.complaint-list{list-style:none;padding:0;display:grid;gap:16px}.complaint-list li{border-bottom:1px solid var(--border);padding-bottom:14px;overflow-wrap:anywhere}.record-description{white-space:pre-wrap;color:var(--text-2)}@media(max-width:900px){.complaints-columns{grid-template-columns:minmax(0,1fr)}}</style>
