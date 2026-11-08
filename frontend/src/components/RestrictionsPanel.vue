<script setup>
import { computed,nextTick,onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import { listMyRestrictions,getBookingEligibility,getMyRestriction } from '../api/restriction'
import { validEligibility } from '../composables/restrictionForm.js'
import RestrictionRecord from './RestrictionRecord.vue'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import SEmpty from './ui/SEmpty.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from '../components/RecordFilters.vue'
import RecordPageControls from '../components/RecordPageControls.vue'
const route=useRoute(),pager=createRecordPager(listMyRestrictions),fields=recordFields('restriction')
const {items,loaded,loading,error,filters,snapshot,snapshotQuery}=pager
const eligibility=ref(null),eligibilityError=ref(''),linked=ref(null),linkedError=ref('')
const linkedId=computed(()=>{const text=String(route.query.restriction_id||'');return /^[1-9]\d*$/.test(text)&&Number.isSafeInteger(Number(text))?Number(text):null})
const linkedRecord=computed(()=>linked.value)
let alive=true,sequence=0
async function focusLinked(){if(!linkedRecord.value)return;await nextTick();if(!alive||typeof document==='undefined')return;const element=document.getElementById(`restriction-${linkedId.value}`);element?.scrollIntoView({block:'center'});element?.focus({preventScroll:true})}
async function readLinked(){const id=linkedId.value,ticket=++sequence;linked.value=null;linkedError.value='';if(!id)return;try{const {data}=await getMyRestriction(id);if(!alive||ticket!==sequence)return;if(data?.id!==id)throw new Error('关联限制返回不完整');linked.value=data}catch(e){if(alive&&ticket===sequence)linkedError.value=e.message}}
async function refresh(){await Promise.all([pager.refresh(),readLinked(),(async()=>{eligibilityError.value='';try{const {data}=await getBookingEligibility();if(!alive)return;if(!validEligibility(data))throw new Error('当前资格返回不完整');eligibility.value=data}catch(e){if(alive)eligibilityError.value=e.message}})()]);await focusLinked()}
watch(linkedId,async()=>{await readLinked();await focusLinked()});onMounted(refresh);onBeforeUnmount(()=>{alive=false;sequence++;pager.dispose()})
defineExpose({refresh})
</script>
<template>
  <section id="restrictions" class="card restrictions-panel">
    <div class="card-title-row"><h3>本人预约限制与当前资格</h3><SButton size="sm" :loading="loading" @click="refresh">刷新</SButton></div>
    <RecordFilters v-model="filters" :fields="fields" :disabled="loading" @apply="pager.apply"/>
    <SBanner v-if="linkedError" type="error" :title="linkedError"/>
    <section v-if="linkedRecord" :id="`restriction-${linkedRecord.id}`" tabindex="-1" class="record-box">
      <h4>通知关联限制（独立读取）</h4>
      <RestrictionRecord :record="linkedRecord"/>
    </section>
    <p v-if="loading" role="status">正在读取本人限制及服务端资格…</p>
    <SBanner v-if="eligibilityError" type="error" :title="eligibilityError"/>
    <section v-if="eligibility&&!loading&&eligibility.active_restrictions.length" class="record-box eligibility-box">
      <p>当前有 {{ eligibility.active_restrictions.length }} 条独立有效限制；解除一条不代表其他记录也已解除。</p>
      <RestrictionRecord v-for="record in eligibility.active_restrictions" :key="record.id" :record="record"/>
    </section>
    <p v-if="!eligibility&&!eligibilityError&&!loading">预约资格尚未读取。</p>
    <SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="refresh">重试查询</SButton></SBanner>
    <h4 v-if="loaded">全部本人限制记录</h4>
    <div v-if="loaded" class="record-box">
      <div v-for="record in items" :key="record.id" :id="record.id===linkedId?`restriction-page-${record.id}`:`restriction-${record.id}`" tabindex="-1" :class="{'linked-restriction':record.id===linkedId}">
        <RestrictionRecord :record="record"/>
      </div>
      <SEmpty v-if="!items.length&&!error&&!loading" description="本次查询尚无本人限制记录" />
    </div>
    <p v-else-if="!loading&&!error">本人限制记录尚未读取。</p>
    <RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="loading" @page="pager.turn"/>
  </section>
</template>
<style scoped>
.restrictions-panel{display:grid;gap:16px;min-width:0}
.record-box{border:1px solid var(--border);border-radius:var(--r-lg);padding:16px;display:grid;gap:12px;min-width:0;min-height:120px}
.eligibility-box{background:var(--surface-2)}
.linked-restriction{outline:2px solid var(--primary);outline-offset:2px;border-radius:var(--r-md)}
</style>
