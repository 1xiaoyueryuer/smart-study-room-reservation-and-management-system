<script setup>
import { ref,nextTick } from 'vue'
import AdminAudit from '../../components/admin/AdminAudit.vue'
import AdminRecords from '../../components/admin/AdminRecords.vue'
import ReviewManagement from '../../components/admin/ReviewManagement.vue'
import CheckinCorrection from '../../components/admin/CheckinCorrection.vue'
import AppealManagement from '../../components/admin/AppealManagement.vue'
import RestrictionManagement from '../../components/admin/RestrictionManagement.vue'
import ComplaintManagement from '../../components/admin/ComplaintManagement.vue'
import UserManagement from '../../components/admin/UserManagement.vue'
import ResourceManagement from '../../components/admin/ResourceManagement.vue'
import PageHelp from '../../components/PageHelp.vue'
import AppIcon from '../../components/AppIcon.vue'
import SButton from '../../components/ui/SButton.vue'
import STag from '../../components/ui/STag.vue'
const audit=ref(null),auditMounted=ref(false)
const recordsMounted=ref(false),violationsMounted=ref(false),correction=ref(null)
const reviews=ref(null),reviewsMounted=ref(false)
const correctionMounted=ref(false)
const activeTab = ref('rooms')
const resources = ref(null), users = ref(null), complaints = ref(null), complaintsMounted = ref(false), restrictions = ref(null), restrictionsMounted = ref(false), appeals=ref(null), appealsMounted=ref(false)
function selectTab(key) { activeTab.value = key;if(key==='audit')auditMounted.value=true; if(key==='records')recordsMounted.value=true;if(key==='violations')violationsMounted.value=true; if(key==='reviews')reviewsMounted.value=true; if(key==='correction')correctionMounted.value=true; if (key === 'complaints') complaintsMounted.value = true; if(key==='restrictions')restrictionsMounted.value=true; if(key==='appeals')appealsMounted.value=true }
const roomStats = ref(null), userStats = ref(null)
const tabs = [{key:'audit',label:'审计查询',icon:'ticket'},{key:'records',label:'预约与使用记录',icon:'reservations'},{key:'violations',label:'违规记录',icon:'warning'},{key:'reviews',label:'评价管理',icon:'ticket'},{key:'correction',label:'核实补签',icon:'ticket'},{ key: 'rooms', label: '自习室管理', icon: 'building' }, { key: 'users', label: '用户管理', icon: 'users' }, {key:'complaints',label:'投诉管理',icon:'ticket'}, {key:'restrictions',label:'预约限制',icon:'warning'},{key:'appeals',label:'申诉待办',icon:'ticket'}]
async function openCorrection(id){selectTab('correction');await nextTick();await correction.value?.openReservation(id)}
function refreshAll() { resources.value?.loadRooms(); users.value?.loadUsers(); complaints.value?.loadList(); restrictions.value?.loadList(); appeals.value?.loadList(); reviews.value?.loadList();audit.value?.refresh() }
</script>
<template>
  <div v-reveal class="page-view">
    <header class="view-heading has-page-help"><div class="heading-copy" data-page-title><h1>管理端</h1><p class="heading-sub">自习室、座位、账号、投诉与预约限制管理</p></div><PageHelp title="管理须知"><ul class="tips"><li>空间位置、布局范围与实际座位容量分别维护。</li><li>资源变更先预览影响，确认后立即管理取消受影响预约；使用中须管理员自报已现场协调，历史仍保留。</li><li>资源原因公开展示，请勿填写个人信息。</li><li>当前管理员不能自行禁用或降级，账号变更须填写理由。</li></ul></PageHelp></header>
    <div class="split admin-split"><div class="split-left">
      <section class="card responsive-compact"><div class="card-title-row"><h3>管理面板</h3><STag type="danger">管理员</STag></div><nav v-active-track class="side-nav"><button v-for="tab in tabs" :key="tab.key" class="side-nav-item" :class="{active:activeTab===tab.key}" @click="selectTab(tab.key)"><span class="side-nav-icon"><AppIcon :name="tab.icon" :size="17" /></span><span class="side-nav-label">{{ tab.label }}</span></button></nav></section>
      <section class="card responsive-compact"><div class="card-title-row"><h3>资源与账号</h3></div><div class="kpi-grid"><div class="kpi"><div class="kpi-num">{{ roomStats?.total ?? '—' }}</div><div class="kpi-label">空间数量</div></div><div class="kpi"><div class="kpi-num">{{ roomStats?.seats ?? '—' }}</div><div class="kpi-label">实际座位数量</div></div><div class="kpi"><div class="kpi-num">{{ roomStats?.capacity ?? '—' }}</div><div class="kpi-label">启用空间有效容量</div></div><div class="kpi"><div class="kpi-num">{{ userStats?.total ?? '—' }}</div><div class="kpi-label">校园账号</div></div></div></section>
      <section class="card responsive-compact"><h3>快速操作</h3><div class="quick-actions"><SButton v-if="activeTab==='rooms'" variant="primary" block @click="resources?.openCreate()">新建自习室</SButton><SButton block @click="refreshAll">刷新数据</SButton></div></section>
    </div><div class="split-right"><AdminAudit v-if="auditMounted" v-show="activeTab==='audit'" ref="audit"/><AdminRecords v-if="recordsMounted" v-show="activeTab==='records'" kind="reservation" @correct="openCorrection"/><AdminRecords v-if="violationsMounted" v-show="activeTab==='violations'" kind="violation"/><ReviewManagement v-if="reviewsMounted" v-show="activeTab==='reviews'" ref="reviews"/><CheckinCorrection ref="correction" v-if="correctionMounted" v-show="activeTab==='correction'"/><ResourceManagement ref="resources" v-show="activeTab==='rooms'" @summary="roomStats=$event" /><UserManagement ref="users" v-show="activeTab==='users'" @summary="userStats=$event" /><ComplaintManagement v-if="complaintsMounted" v-show="activeTab==='complaints'" ref="complaints" /><RestrictionManagement v-if="restrictionsMounted" v-show="activeTab==='restrictions'" ref="restrictions" /><AppealManagement v-if="appealsMounted" v-show="activeTab==='appeals'" ref="appeals" /></div></div>
  </div>
</template>
<style scoped>
.quick-actions { display:flex; flex-direction:column; gap:10px; margin-top:14px; }
</style>
