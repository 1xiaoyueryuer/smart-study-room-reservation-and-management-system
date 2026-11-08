<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listMyViolations,getMyViolation } from '../api/violation'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import STag from './ui/STag.vue'
import SEmpty from './ui/SEmpty.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'
const props = defineProps({ autoLoad: { type:Boolean, default:true } })
const route = useRoute()
const pager=createRecordPager(listMyViolations),fields=recordFields('violation')
const {items,loading,error,loaded,filters,snapshot,snapshotQuery}=pager
const linked=ref(null),linkedError=ref('')
const typeLabels = { abandon:'开始后主动放弃', no_show:'超时未签到', occupation_refusal:'核实拒不配合占座' }
const linkedId = computed(() => {const value=String(route.query.violation_id||'');return /^[1-9]\d*$/.test(value)&&Number.isSafeInteger(Number(value))?Number(value):null})
const linkedRecord=computed(()=>linked.value)
const displayItems=computed(()=>items.value)
async function focusLinked() {if(!linkedRecord.value)return;await nextTick();if(!alive||typeof document==='undefined')return;const element=document.getElementById(`violation-${linkedId.value}`);element?.scrollIntoView({block:'center'});element?.focus({preventScroll:true})}
let sequence=0,alive=true
async function readLinked(){const id=linkedId.value,ticket=++sequence;linked.value=null;linkedError.value='';if(!id)return;try{const {data}=await getMyViolation(id);if(!alive||ticket!==sequence)return;if(data?.id!==id)throw new Error('关联违规返回不完整');linked.value=data}catch(e){if(alive&&ticket===sequence)linkedError.value=e.message}}
watch(linkedId,async()=>{await readLinked();await focusLinked()})
async function refresh(){await Promise.all([pager.refresh(),readLinked()]);await focusLinked()}
onMounted(() => { if (props.autoLoad) void refresh() })
onBeforeUnmount(()=>{alive=false;sequence++;pager.dispose()})
defineExpose({ refresh })
</script>
<template>
  <section id="violations" class="card">
    <div class="card-title-row"><h3>本人独立违规记录</h3><SButton size="sm" :loading="loading" @click="refresh">刷新</SButton></div>
    <RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/><SBanner v-if="linkedError" type="error" :title="linkedError"/><section v-if="linkedRecord" :id="`violation-${linkedRecord.id}`" tabindex="-1"><h4>通知关联违规#{{linkedRecord.id}}（独立读取，不计入本页）</h4><p>{{typeLabels[linkedRecord.type]||linkedRecord.type}} · {{linkedRecord.status==='active'?'有效':'已撤销'}} · {{linkedRecord.occurred_at}}</p><p class="violation-reason">{{linkedRecord.reason}}</p><p v-if="linkedRecord.revocation">公开撤销原因：{{linkedRecord.revocation.reason}} · {{linkedRecord.revocation.revoked_at}}</p><RouterLink :to="{path:'/appeals/new',query:{violation_id:linkedRecord.id}}">查看关联违规申诉条件/进度</RouterLink></section>
    <p v-if="loading" role="status">正在读取本人违规记录…</p>
    <SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试违规查询</SButton></SBanner>
    <p v-else-if="!loaded" role="status" class="muted">尚未读取本人独立违规记录，请等待预约状态核对或点击刷新。</p>
    <template v-if="loaded">
      <div class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="本人独立违规明细，可左右滑动"><table class="table violation-table"><thead><tr><th>预约</th><th>事件类型</th><th>发生时间</th><th>登记时间</th><th>有效性 / 来源</th><th>登记人</th><th>站内通知生成时间</th><th>公开事实依据</th><th>申诉条件与进度</th></tr></thead><tbody><tr v-for="item in displayItems" :key="item.id" :id="item.id===linkedId?`violation-page-${item.id}`:`violation-${item.id}`" tabindex="-1" :class="{'linked-violation':item.id===linkedId}"><td><span v-if="item.source==='manual'">关联预约涉及他人，不公开编号</span><span v-else-if="item.reservation_id">#{{ item.reservation_id }}</span><span v-else>本人预约编号未留存</span><div>违规#{{ item.id }}</div></td><td>{{ typeLabels[item.type] || item.type }}</td><td>{{ item.occurred_at }}</td><td>{{ item.registered_at }}</td><td><STag :type="item.status === 'active' ? 'danger' : 'neutral'">{{ item.status === 'active' ? '有效' : item.status === 'revoked' ? '已撤销' : item.status }}</STag><div>{{ item.source === 'automatic' ? '系统自动事件' : item.source === 'manual' ? '管理员核实后独立登记' : item.source }}</div></td><td>{{ item.registrant_display_name || (item.source==='automatic'?'系统自动登记':'登记人显示名未留存') }}</td><td><span v-if="item.notified_at">{{ item.notified_at }}<br />表示站内通知已生成，不代表已阅读</span><span v-else>历史未留存可靠通知时间</span></td><td class="violation-reason">{{ item.reason }}<p v-if="item.revocation">公开撤销原因：{{item.revocation.reason}}<br />撤销登记：{{item.revocation.revoked_at}} · {{item.revocation.revoker_display_name}}</p></td><td><RouterLink :to="{path:'/appeals/new',query:{violation_id:item.id}}">查看申诉条件/进度</RouterLink></td></tr></tbody></table><SEmpty v-if="!items.length&&!error&&!loading" description="尚无已登记的独立违规记录" /></div></div>
    </template>
    <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/>
  </section>
</template>
<style scoped>
.violation-table { min-width:1420px; }.violation-reason{white-space:pre-wrap;overflow-wrap:anywhere}.linked-violation{outline:2px solid var(--primary);outline-offset:-2px}
.table-wrap { margin-top: 20px; }
</style>
