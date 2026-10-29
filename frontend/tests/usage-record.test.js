import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import { compileTemplate, parse } from '@vue/compiler-sfc'
const source=await readFile(new URL('../src/components/UsageRecordSummary.vue',import.meta.url),'utf8')
async function evaluate(code,mocks,extra={}) {
  const context=vm.createContext(extra),module=new vm.SourceTextModule(code,{context})
  await module.link(specifier=>{const values=mocks[specifier];assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})})
  await module.evaluate();return module.namespace
}
async function render(record) {
  const script=source.split('<script setup>')[1].split('</script>')[0]
  const bindings=await evaluate(script+'\nexport {durationText,originLabels,reasonLabels}',{vue},{defineProps:()=>({record})})
  const {descriptor}=parse(source)
  const compiled=compileTemplate({source:descriptor.template.content,filename:'UsageRecordSummary.vue',id:'usage-record',ssr:true,ssrCssVars:[]})
  assert.equal(compiled.errors.length,0)
  const template=await evaluate(compiled.code,{'vue/server-renderer':ssr,vue})
  return ssr.renderToString(vue.createSSRApp({props:['record'],setup:()=>({...bindings}),ssrRender:template.ssrRender},{record}))
}
test('actual usage SFC renders null as absent, and ongoing nanosecond seconds as a server snapshot without client arithmetic',async()=>{
  assert.match(await render(null),/尚无使用记录/)
  const html=await render({checkin_at:'2026-10-30T08:00:00.000000001',ended_at:null,duration_seconds:'123.000000001',observed_at:'2026-10-30T08:02:03.000000002',origin:'seat_code'})
  assert.match(html,/123\.000000001秒/);assert.match(html,/截至本次服务端读取，非实时计时/);assert.match(html,/08:02:03\.000000002/);assert.match(html,/座位码签到/)
  assert.doesNotMatch(source,/checkout_at|Date\(|Date.now|setInterval|Math.round|toFixed/)
})
test('actual usage SFC distinguishes completed legacy facts from explicit simulated usage',async()=>{
  const historical=await render({checkin_at:'2026-10-29T08:00:00',ended_at:'2026-10-29T09:00:00.125',duration_seconds:3600.125,settled_at:'2026-10-30T01:00:00',end_reason:'legacy_unclassified',origin:'legacy'})
  assert.match(historical,/3600\.125秒/);assert.match(historical,/历史结束，具体方式未记录/);assert.match(historical,/历史迁移/);assert.match(historical,/结算登记/);assert.doesNotMatch(historical,/截至本次/)
  const demo=await render({checkin_at:'2026-10-29T08:00:00',ended_at:'2026-10-29T09:00:00',duration_seconds:3600,end_reason:'demo_completed',origin:'demo_seed'})
  assert.match(demo,/演示模拟（非到场证据）/);assert.match(demo,/演示模拟结束/)
  const timeout=await render({checkin_at:'2026-10-29T08:00:00',ended_at:'2026-10-29T08:40:00',duration_seconds:2400,end_reason:'temp_leave_timeout',origin:'legacy'});assert.match(timeout,/旧暂离流程结束（历史）/)
})
test('missing or invalid usage duration is never fabricated as zero or inferred from timestamps',async()=>{
  for(const duration_seconds of [null,undefined,-1,'invalid']) {
    const html=await render({checkin_at:'2026-10-30T08:00:00',ended_at:'2026-10-30T09:00:00',duration_seconds,origin:'legacy'})
    assert.match(html,/时长未登记/);assert.doesNotMatch(html,/使用时长：0秒|3600秒/)
  }
})
