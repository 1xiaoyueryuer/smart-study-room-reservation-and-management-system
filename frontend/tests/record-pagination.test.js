import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {readFile} from 'node:fs/promises'
import * as vue from 'vue'
import * as records from '../src/composables/recordPage.js'
import {validEligibility,eligibilityText,restrictionStatuses,restrictionPayload,offsetInstant} from '../src/composables/restrictionForm.js'
const rows=(start,count)=>Array.from({length:count},(_,n)=>({id:start+n,status:'active',type:'no_show',source:'automatic',reason:'工程记录'+(start+n)}))
const envelope=(items,query,total=45)=>({items,total,page:query.page,page_size:query.page_size,server_now:'2026-10-30T20:00+08:00'})
async function component(file,mocks,exports,extra={}){const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0],cleanup=[],context=vm.createContext({defineProps:()=>({autoLoad:false}),defineExpose(){},crypto,...extra}),module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context});await module.link(specifier=>{const values=mocks[specifier]||(specifier==='vue'?{...vue,onMounted(){},watch(){},onBeforeUnmount:fn=>cleanup.push(fn)}:specifier.endsWith('/recordPage.js')?records:specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[k,v]of Object.entries(values))this.setExport(k,v)},{context})});await module.evaluate();return {...module.namespace,cleanup}}
test('record filters keep exact domain fields and admin user scope; normalized requests always include real server page',()=>{
 for(const domain of ['reservation','violation','restriction','complaint','appeal']){const own=records.recordFields(domain),admin=records.recordFields(domain,true);assert.equal(own.some(f=>f.key==='user_id'),false);assert.equal(admin.some(f=>f.key==='user_id'),true);assert.ok(own.some(f=>f.key==='date_from'));assert.ok(own.some(f=>f.key==='date_to'))}
 assert.deepEqual(records.recordQuery({date_from:'2026-10-01',date_to:'2026-10-30',room_id:'2',seat_no:' A-01 ',status:'completed',record_type:'usage'},2),{page:2,page_size:20,date_from:'2026-10-01',date_to:'2026-10-30',room_id:2,seat_no:'A-01',status:'completed',record_type:'usage'})
 for(const source of [{room_id:0},{user_id:'1.5'},{date_from:'2026-10-31',date_to:'2026-10-01'}])assert.throws(()=>records.recordQuery(source))
})
test('server filtering and pagination preserve authoritative totals, apply resets page1, turn and refresh retain applied query',async()=>{
 const calls=[],p=records.createRecordPager(async query=>{calls.push({...query});return{data:envelope(rows(query.page===1?1:21,20),query)}})
 p.filters.value={status:'active',date_from:'2026-10-01'};await p.apply();assert.equal(p.snapshot.value.total,45);assert.equal(p.items.value.length,20);await p.turn(2);assert.equal(p.items.value[0].id,21);assert.equal(p.snapshot.value.total,45);p.filters.value.status='revoked';await p.refresh();assert.equal(calls.at(-1).status,'active');assert.equal(calls.at(-1).page,2);await p.apply();assert.equal(calls.at(-1).page,1);assert.equal(calls.at(-1).status,'revoked')
})
test('failed new query leaves last successful rows, total, timestamp and query; next page uses successful applied scope',async()=>{
 let fail=false;const calls=[],p=records.createRecordPager(async query=>{calls.push({...query});if(fail)throw new Error('HTTP503');return{data:envelope(rows(1,20),query)}});p.filters.value={status:'active'};await p.apply();const old=p.snapshot.value,query=p.snapshotQuery.value;fail=true;p.filters.value={status:'revoked'};await p.apply();assert.equal(p.snapshot.value,old);assert.equal(p.snapshotQuery.value,query);assert.equal(p.applied.value.status,'active');assert.match(p.error.value,/503/);fail=false;await p.turn(2);assert.equal(calls.at(-1).status,'active')
})
test('newer query wins, legacy array response never becomes verified data, disposed response does not update',async()=>{
 const pending=[],p=records.createRecordPager(query=>new Promise(resolve=>pending.push({query,resolve})));const first=p.refresh(),second=p.turn(2);pending[1].resolve({data:envelope(rows(21,20),pending[1].query)});await second;pending[0].resolve({data:envelope(rows(1,20),pending[0].query)});await first;assert.equal(p.snapshot.value.page,2);const q=records.createRecordPager(async()=>({data:[]}));await q.refresh();assert.equal(q.loaded.value,false);assert.match(q.error.value,/不完整/);const third=p.turn(3);p.dispose();pending[2].resolve({data:envelope(rows(41,5),pending[2].query)});await third;assert.equal(p.snapshot.value.page,2)
})
test('empty beyond-last server page preserves full total and no client slice or fabricated category counts',async()=>{
 const p=records.createRecordPager(async query=>({data:envelope([],query,45)}));await p.turn(4);assert.equal(p.items.value.length,0);assert.equal(p.snapshot.value.total,45);assert.equal(p.snapshot.value.page,4);const source=await readFile(new URL('../src/views/MyReservationsView.vue',import.meta.url),'utf8');assert.doesNotMatch(source,/usePagination\(|statusBuckets\.(pending|checked_in|unused_ended)|list.value.filter/);assert.match(source,/snapshot\?\.total/)
})
test('notification violation target outside page is fetched independently and stays available even list query fails',async()=>{
 let fail=false;const ids=[],single={...rows(99,1)[0],notified_at:'2026-10-30T10:00+08:00'};const p=await component('../src/components/ViolationsPanel.vue',{'vue-router':{useRoute:()=>({query:{violation_id:'99'}})},'../api/violation':{listMyViolations:async query=>{if(fail)throw new Error('page unavailable');return{data:envelope(rows(1,20),query)}},getMyViolation:async id=>{ids.push(id);return{data:single}}}},'items,linkedRecord,linkedError,error,refresh');await p.refresh();assert.equal(p.items.value.some(r=>r.id===99),false);assert.equal(p.linkedRecord.value.id,99);fail=true;await p.refresh();assert.equal(p.linkedRecord.value.id,99);assert.match(p.error.value,/unavailable/);assert.deepEqual(ids,[99,99]);const source=await readFile(new URL('../src/components/ViolationsPanel.vue',import.meta.url),'utf8');assert.doesNotMatch(source,/通知关联记录未在.*列表中找到/)
})
test('notification restriction target outside first page uses safe single endpoint independently of current eligibility error',async()=>{
 const p=await component('../src/components/RestrictionsPanel.vue',{'vue-router':{useRoute:()=>({query:{restriction_id:'99'}})},'../api/restriction':{listMyRestrictions:async query=>({data:envelope(rows(1,20),query)}),getMyRestriction:async id=>({data:{id,status:'released',reason:'本人安全单条'}}),getBookingEligibility:async()=>{throw new Error('eligibility unavailable')}},'../composables/restrictionForm.js':{validEligibility,eligibilityText}},'items,linkedRecord,eligibility,eligibilityError,refresh');await p.refresh();assert.equal(p.items.value.some(r=>r.id===99),false);assert.equal(p.linkedRecord.value.id,99);assert.equal(p.eligibility.value,null);assert.match(p.eligibilityError.value,/unavailable/)
})
test('admin restriction private evidence is read from single detail, never assumed present on paginated list',async()=>{
 const listRow={id:90,status:'active',version:1,subject:{id:2,role:'student'},source:'manual',reason:'公开原因'},requests=[]
 const p=await component('../src/components/admin/RestrictionManagement.vue',{'../../api/admin':{listUsers:async()=>({data:[]})},'../../api/restriction':{listAdminRestrictions:async query=>({data:envelope([listRow],query,1)}),getAdminRestriction:async id=>{requests.push(id);return{data:{...listRow,verification_note:'ADMIN_PRIVATE',release_verification_note:null}}},createRestriction:async()=>{},releaseRestriction:async()=>{}},'../../composables/restrictionForm.js':{restrictionStatuses,restrictionPayload,validEligibility,eligibilityText,offsetInstant},'../ui/feedback':{confirmDialog:async()=>{}}},'items,selectedDetails,selected,choose,loadList');await p.loadList();assert.equal('verification_note'in p.items.value[0],false);await p.choose(p.items.value[0]);assert.deepEqual(requests,[90]);assert.equal(p.selectedDetails.value.verification_note,'ADMIN_PRIVATE');assert.equal(p.selected.value.unverified,undefined)
})


test('shared filters can be embedded in complaint submission without nested forms or native submit buttons',async()=>{
 const source=await readFile(new URL('../src/components/RecordFilters.vue',import.meta.url),'utf8')
 assert.doesNotMatch(source,/<form|type="submit"/);assert.match(source,/type="button"/);assert.match(source,/@keydown.enter.stop.prevent/)
 const complaint=await readFile(new URL('../src/components/ComplaintForm.vue',import.meta.url),'utf8');assert.match(complaint,/choicesPager.snapshot.value/);assert.match(complaint,/getReservation\(id\)/)
})
