<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listMyViolations } from '../api/violation'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import STag from './ui/STag.vue'
import SEmpty from './ui/SEmpty.vue'
const props = defineProps({ autoLoad: { type:Boolean, default:true } })
const route = useRoute()
const items = ref([]), loading = ref(false), error = ref(''), loaded = ref(false)
const activeCount = computed(() => items.value.filter(item => item.status === 'active').length)
const typeLabels = { abandon:'开始后主动放弃', no_show:'超时未签到', occupation_refusal:'核实拒不配合占座' }
const linkedId = computed(() => {const value=String(route.query.violation_id||'');return /^[1-9]\d*$/.test(value)&&Number.isSafeInteger(Number(value))?Number(value):null})
const linkedRecord = computed(() => loaded.value ? items.value.find(item=>item.id===linkedId.value) : null)
async function focusLinked() {if(!linkedRecord.value||loading.value||error.value)return;await nextTick();if(!alive||typeof document==='undefined')return;const element=document.getElementById(`violation-${linkedId.value}`);element?.scrollIntoView({block:'center'});element?.focus({preventScroll:true})}
watch(linkedId,()=>{void focusLinked()})
let sequence = 0, alive = true
async function refresh() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const response = await listMyViolations()
    if (!alive || request !== sequence) return
    if (!Array.isArray(response.data)) throw new Error('违规列表返回不完整')
    items.value = response.data
    loaded.value = true
  } catch (failure) { if (alive && request === sequence) error.value = failure.message }
  finally { if (alive && request === sequence) { loading.value = false; void focusLinked() } }
}
onMounted(() => { if (props.autoLoad) void refresh() })
onBeforeUnmount(()=>{alive=false;sequence++})
defineExpose({ refresh })
</script>
<template>
  <section id="violations" class="card">
    <div class="card-title-row"><h3>本人独立违规记录</h3><SButton size="sm" :loading="loading" @click="refresh">刷新违规记录</SButton></div>
    <p class="muted">按独立事件记录，不由预约终态或历史信用分换算。时间按校园时区Asia/Shanghai展示。</p>
    <p v-if="loading" role="status">正在读取本人违规记录…</p>
    <SBanner v-else-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试违规查询</SButton></SBanner>
    <p v-else-if="!loaded" role="status" class="muted">尚未读取本人独立违规记录，请等待预约状态核对或点击刷新。</p>
    <template v-else>
      <p v-if="linkedId && !linkedRecord" class="muted">通知关联记录未在本次本人可见列表中找到，请刷新核对。</p>
      <p class="muted">已登记{{ items.length }}条，其中有效{{ activeCount }}条；历史信用流水另行只读保留。</p>
      <div class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="本人独立违规明细，可左右滑动"><table class="table violation-table"><thead><tr><th>预约</th><th>事件类型</th><th>发生时间</th><th>登记时间</th><th>有效性 / 来源</th><th>登记人</th><th>站内通知生成时间</th><th>公开事实依据</th><th>申诉条件与进度</th></tr></thead><tbody><tr v-for="item in items" :key="item.id" :id="`violation-${item.id}`" tabindex="-1" :class="{'linked-violation':item.id===linkedId}"><td><span v-if="item.source==='manual'">关联预约涉及他人，不公开编号</span><span v-else-if="item.reservation_id">#{{ item.reservation_id }}</span><span v-else>本人预约编号未留存</span><div>违规#{{ item.id }}</div></td><td>{{ typeLabels[item.type] || item.type }}</td><td>{{ item.occurred_at }}</td><td>{{ item.registered_at }}</td><td><STag :type="item.status === 'active' ? 'danger' : 'neutral'">{{ item.status === 'active' ? '有效' : item.status === 'revoked' ? '已撤销' : item.status }}</STag><div>{{ item.source === 'automatic' ? '系统自动事件' : item.source === 'manual' ? '管理员核实后独立登记' : item.source }}</div></td><td>{{ item.registrant_display_name || (item.source==='automatic'?'系统自动登记':'登记人显示名未留存') }}</td><td><span v-if="item.notified_at">{{ item.notified_at }}<br />表示站内通知已生成，不代表已阅读</span><span v-else>历史未留存可靠通知时间</span></td><td class="violation-reason">{{ item.reason }}</td><td><RouterLink :to="{path:'/appeals/new',query:{violation_id:item.id}}">查看申诉条件/进度</RouterLink></td></tr></tbody></table><SEmpty v-if="!items.length" description="尚无已登记的独立违规记录" /></div></div>
    </template>
  </section>
</template>
<style scoped>
.violation-table { min-width:1420px; }.violation-reason{white-space:pre-wrap;overflow-wrap:anywhere}.linked-violation{outline:2px solid var(--primary);outline-offset:-2px}
</style>
