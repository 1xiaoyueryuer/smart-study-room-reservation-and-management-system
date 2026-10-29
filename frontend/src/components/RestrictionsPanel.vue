<script setup>
import { computed,nextTick,onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import { listMyRestrictions,getBookingEligibility } from '../api/restriction'
import { validEligibility,eligibilityText } from '../composables/restrictionForm.js'
import RestrictionRecord from './RestrictionRecord.vue'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
const route=useRoute(),items=ref([]),loaded=ref(false),loading=ref(false),error=ref(''),eligibility=ref(null),eligibilityError=ref('')
const linkedId=computed(()=>{const text=String(route.query.restriction_id||'');return /^[1-9]\d*$/.test(text)&&Number.isSafeInteger(Number(text))?Number(text):null})
const linkedRecord=computed(()=>loaded.value?items.value.find(item=>item.id===linkedId.value):null)
let alive=true,sequence=0
async function focusLinked(){if(!linkedRecord.value||loading.value||error.value)return;await nextTick();if(!alive||typeof document==='undefined')return;const element=document.getElementById(`restriction-${linkedId.value}`);element?.scrollIntoView({block:'center'});element?.focus({preventScroll:true})}
async function refresh(){const ticket=++sequence;loading.value=true;error.value='';eligibilityError.value=''
  const [history,current]=await Promise.allSettled([listMyRestrictions(),getBookingEligibility()]);if(!alive||ticket!==sequence)return
  if(history.status==='fulfilled'&&Array.isArray(history.value.data)){items.value=history.value.data;loaded.value=true}else error.value=history.reason?.message||'本人限制列表返回不完整'
  if(current.status==='fulfilled'&&validEligibility(current.value.data))eligibility.value=current.value.data;else{eligibility.value=null;eligibilityError.value=current.reason?.message||'当前资格返回不完整'}
  loading.value=false;void focusLinked()
}
watch(linkedId,()=>{void focusLinked()});onMounted(refresh);onBeforeUnmount(()=>{alive=false;sequence++})
defineExpose({refresh})
</script>
<template><section id="restrictions" class="card restrictions-panel"><div class="card-title-row"><h3>本人预约限制与当前资格</h3><SButton size="sm" :loading="loading" @click="refresh">刷新资格和记录</SButton></div><p v-if="loading" role="status">正在读取本人限制及服务端资格…</p><SBanner v-if="eligibilityError" type="error" :title="eligibilityError" /><section v-if="eligibility&&!loading"><p>{{ eligibilityText(eligibility) }}。</p><p>服务端核对：{{ eligibility.server_now }}（{{ eligibility.campus_zone }}）；这是本次读取的资格快照，最终提交由服务端再次核对。</p><p v-if="eligibility.active_restrictions.length">当前有{{ eligibility.active_restrictions.length }}条独立有效限制；解除一条不代表其他记录也已解除。</p><RestrictionRecord v-for="record in eligibility.active_restrictions" :key="record.id" :record="record" /></section><p v-if="!eligibility&&!eligibilityError&&!loading">预约资格尚未读取。</p><SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试查询</SButton></SBanner><template v-if="loaded&&!loading&&!error"><h4>全部本人限制记录</h4><p v-if="!items.length">本次查询尚无本人限制记录；资格仍以上方服务端核对为准。</p><p v-if="linkedId&&!linkedRecord">通知关联限制未在本次本人列表中找到，请刷新核对。</p><div v-for="record in items" :key="record.id" :id="`restriction-${record.id}`" tabindex="-1" :class="{'linked-restriction':record.id===linkedId}"><RestrictionRecord :record="record" /></div></template><p v-else-if="!loaded&&!loading&&!error">本人限制记录尚未读取。</p></section></template>
<style scoped>.restrictions-panel,.restrictions-panel>section{display:grid;gap:12px;min-width:0}.linked-restriction{outline:2px solid var(--primary);outline-offset:2px;border-radius:var(--r-md)}</style>
