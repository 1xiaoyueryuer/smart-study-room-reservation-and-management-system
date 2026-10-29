<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getComplaint } from '../api/complaint'
import ComplaintRecord from '../components/ComplaintRecord.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
const props=defineProps({id:{type:[String,Number],required:true}})
const record=ref(null),loading=ref(false),error=ref('')
let sequence=0,alive=true
async function load(){const ticket=++sequence,id=props.id;loading.value=true;error.value='';record.value=null
  try{const response=await getComplaint(id);if(alive&&ticket===sequence){if(String(response.data?.id)!==String(id)||!Array.isArray(response.data?.attachments)||!Array.isArray(response.data?.events))throw new Error('详情返回不完整');record.value=response.data}}
  catch(failure){if(alive&&ticket===sequence)error.value=failure.message}
  finally{if(alive&&ticket===sequence)loading.value=false}
}
watch(()=>props.id,load,{immediate:true})
onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<template><div class="page-view complaint-detail"><header class="view-heading" data-page-title><h1>求助与投诉详情</h1><RouterLink to="/complaints">返回本人列表和提交表单</RouterLink></header><section class="card"><SButton :loading="loading" @click="load">刷新详情</SButton><p v-if="loading" role="status">正在鉴权读取本人详情…</p><SBanner v-if="error" type="error" :title="error"><SButton @click="load">重试详情</SButton></SBanner><ComplaintRecord v-if="record&&!loading&&!error" :record="record" /></section></div></template>
<style scoped>.complaint-detail{max-width:1100px}.complaint-detail>.card{display:grid;gap:16px;min-width:0}</style>
