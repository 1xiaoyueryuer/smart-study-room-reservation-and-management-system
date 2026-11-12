<script setup>
import SInput from './ui/SInput.vue'
import SSelect from './ui/SSelect.vue'
import SButton from './ui/SButton.vue'
const props=defineProps({modelValue:{type:Object,required:true},fields:{type:Array,required:true},disabled:Boolean})
const emit=defineEmits(['update:modelValue','apply'])
function change(key,value){emit('update:modelValue',{...props.modelValue,[key]:value})}
</script>
<template><div class="record-filters" role="group" @keydown.enter.stop.prevent="emit('apply')"><label v-for="field in fields" :key="field.key">{{field.label}}<SSelect v-if="field.options" :model-value="modelValue[field.key]??''" :options="field.options" :disabled="disabled" @update:model-value="change(field.key,$event)"/><SInput v-else :model-value="modelValue[field.key]??''" :type="field.type||'text'" :disabled="disabled" @update:model-value="change(field.key,$event)"/></label><SButton type="button" :disabled="disabled" @click="emit('apply')">应用筛选</SButton></div></template>
<style scoped>.record-filters{display:flex;flex-wrap:wrap;gap:12px;align-items:end;min-width:0}.record-filters label{display:grid;gap:7px;flex:1 1 150px;min-width:0;max-width:260px}</style>
