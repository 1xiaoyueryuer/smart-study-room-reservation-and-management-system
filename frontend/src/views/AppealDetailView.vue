<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import {getAppeal} from '../api/appeal'
import {validAppealDetail} from '../composables/appealForm.js'
import AppealRecord from '../components/AppealRecord.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
const props=defineProps({id:{type:[String,Number],required:true}}),record=ref(null),loading=ref(false),error=ref('');let alive=true,sequence=0
async function load(){const ticket=++sequence,id=props.id;loading.value=true;error.value='';record.value=null;try{const response=await getAppeal(id);if(!alive||ticket!==sequence)return;if(!validAppealDetail(response.data)||String(response.data.id)!==String(id))throw new Error('本人申诉详情返回不完整');record.value=response.data}catch(failure){if(alive&&ticket===sequence)error.value=failure.message}finally{if(alive&&ticket===sequence)loading.value=false}}
watch(()=>props.id,load,{immediate:true});onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<template><div class="page-view appeal-detail"><header class="view-heading" data-page-title><h1>本人申诉详情</h1><RouterLink to="/profile#appeals">返回本人申诉进度</RouterLink></header><section class="card"><SButton :loading="loading" @click="load">刷新申诉详情</SButton><p v-if="loading" role="status">正在鉴权读取本人申诉详情…</p><SBanner v-else-if="error" type="error" :title="error"><SButton @click="load">重试读取</SButton></SBanner><AppealRecord v-if="record&&!loading&&!error" :record="record" /></section></div></template>
<style scoped>.appeal-detail{max-width:1100px}.appeal-detail>.card{display:grid;gap:14px;min-width:0}</style>
