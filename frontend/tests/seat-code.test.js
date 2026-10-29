import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { BrowserQRCodeReader } from '@zxing/browser'
import { QRCodeWriter, BarcodeFormat } from '@zxing/library'
import { createSeatScanner, scanFailure } from '../src/composables/seatScanner.js'
const code='studyroom-seat:v1:'+'a'.repeat(43)
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{resolve,promise}}
const tick=()=>new Promise(resolve=>setImmediate(resolve))
function scanner(options={}) {
  const decoded=[],errors=[],revoked=[],states=[]
  const urls={createObjectURL:()=> 'blob:test-image',revokeObjectURL:url=>revoked.push(url)}
  class Image { naturalWidth=320;naturalHeight=320;set src(value){queueMicrotask(()=>this.onload())} }
  const instance=createSeatScanner({onCode:async text=>{decoded.push(text)},onError:error=>errors.push(error),onCamera:active=>states.push(active),reader:{decodeFromImageUrl:async()=>({getText:()=>code})},urls,ImageClass:Image,...options})
  return {instance,decoded,errors,revoked,states}
}

test('installed BrowserQRCodeReader genuinely decodes QR pixels using actual ZXing implementation',()=>{
  const matrix=new QRCodeWriter().encode(code,BarcodeFormat.QR_CODE,320,320,new Map())
  const data=new Uint8ClampedArray(320*320*4)
  for(let y=0;y<320;y++) for(let x=0;x<320;x++) {const offset=(y*320+x)*4;data.fill(matrix.get(x,y)?0:255,offset,offset+3);data[offset+3]=255}
  const result=new BrowserQRCodeReader().decodeFromCanvas({width:320,height:320,getContext:()=>({getImageData:()=>({data})})})
  assert.equal(result.getText(),code)
})
test('uploaded image goes through decodeFromImageUrl and revokes its object URL after delivery',async()=>{
  const page=scanner()
  await page.instance.file({type:'image/png',size:100})
  assert.deepEqual(page.decoded,[code]);assert.deepEqual(page.revoked,['blob:test-image'])
})
test('invalid image type, size, dimensions and decoding errors stay visible, valid next file recovers',async()=>{
  let invalid=true
  const page=scanner({reader:{decodeFromImageUrl:async()=>{if(invalid)throw new Error('码图不完整');return{getText:()=>code}}}})
  await page.instance.file({type:'text/plain',size:1});assert.match(page.errors.at(-1),/PNG/)
  await page.instance.file({type:'image/jpeg',size:6*1024*1024});assert.match(page.errors.at(-1),/5MiB/)
  await page.instance.file({type:'image/png',size:100});assert.match(page.errors.at(-1),/码图不完整/)
  invalid=false;await page.instance.file({type:'image/png',size:100});assert.deepEqual(page.decoded,[code])
  class HugeImage {naturalWidth=4097;naturalHeight=1;set src(value){queueMicrotask(()=>this.onload())}}
  const huge=scanner({ImageClass:HugeImage});await huge.instance.file({type:'image/png',size:100});assert.match(huge.errors[0],/4096/)
})
test('leaving while camera permission is pending stops late stream and never starts decoding',async()=>{
  const pending=deferred();let stopped=0,started=0
  const page=scanner({mediaDevices:{getUserMedia:()=>pending.promise},reader:{decodeFromStream:async()=>{started++;return{stop(){}}}}})
  const active=page.instance.camera({});page.instance.stop()
  pending.resolve({getTracks:()=>[{stop(){stopped++}}]});await active
  assert.equal(stopped,1);assert.equal(started,0)
})
test('camera duplicate callbacks submit once and release stream plus late decoder controls',async()=>{
  const hold=deferred();let callback,tracks=0,controls=0,submits=0
  const page=scanner({onCode:async()=>{submits++;await hold.promise},mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){tracks++}}]})},reader:{decodeFromStream:async(stream,video,handler)=>{callback=handler;handler({getText:()=>code});handler({getText:()=>code});return{stop(){controls++}}}}})
  const video={srcObject:null};await page.instance.camera(video)
  callback({getText:()=>code});hold.resolve();await tick()
  assert.equal(submits,1);assert.equal(tracks,1);assert.equal(controls,1);assert.equal(video.srcObject,null)
})
test('camera permission errors persist and explicit retry can start camera and then stop owned controls',async()=>{
  let denied=true,stopped=0
  const page=scanner({mediaDevices:{getUserMedia:async()=>{if(denied)throw{name:'NotAllowedError'};return{getTracks:()=>[{stop(){stopped++}}]}}},reader:{decodeFromStream:async()=>({stop(){stopped++}})}})
  await page.instance.camera({});assert.match(page.errors[0],/权限被拒绝/)
  denied=false;await page.instance.camera({});page.instance.stop();assert.equal(stopped,2)
  assert.match(scanFailure({name:'NotFoundError'}),/未找到摄像头/)
  assert.match(scanFailure({name:'NotReadableError'}),/占用/)
})
async function moduleFrom(source,mocks,extra={}) {
  const context=vm.createContext(extra)
  const m=new vm.SourceTextModule(source,{context})
  await m.link(specifier=>{const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})})
  await m.evaluate();return m.namespace
}
async function view(file,mocks,exports,extra={}) {const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0];return moduleFrom(source+`\nexport {${exports}}`,mocks,extra)}
async function checkin(overrides={}) {
  const calls=[],cleanup=[],props={id:'7'};let scan,routeChanged
  const page=await view('../src/views/CheckinView.vue',{
    vue:{ref,computed,onBeforeUnmount:handler=>cleanup.push(handler),watch:(source,handler)=>{routeChanged=handler;handler()}},
    '../api/reservation':{getReservation:async id=>{calls.push(['detail',id]);return{data:{id,allowed_actions:['checkin']}}},checkinReservation:async(id,text)=>{calls.push(['checkin',id,text])},...overrides},
    '../composables/seatScanner.js':{createSeatScanner:options=>{scan=options;return{stop(){calls.push(['stop'])},file:async()=>{},camera:async()=>{}}}}
  },'load,submitCode,reservation,success,scanError,scanNotice,detailError,submitting,canCheckin', {defineProps:()=>props})
  await tick();return{...page,calls,scan,cleanup,changeId(id){props.id=id;routeChanged()}}
}
test('independent scan page loads own detail, submits original decoded code once and re-reads true state',async()=>{
  const pending=deferred();let submits=0
  const page=await checkin({checkinReservation:async(id,text)=>{assert.equal(id,'7');assert.equal(text,code);submits++;await pending.promise}})
  assert.ok(page.calls.some(call=>call[0]==='detail'))
  const first=page.submitCode(code);await page.submitCode(code);assert.equal(submits,1)
  pending.resolve();await first;assert.equal(page.success.value,true);assert.equal(page.calls.filter(call=>call[0]==='detail').length,2)
  page.cleanup[0]();assert.equal(page.calls.at(-1)[0],'stop')
})
test('deadline conflict refreshes true no_show and network uncertainty requires deliberate retry',async()=>{
  let details=0
  const page=await checkin({getReservation:async()=>({data:{allowed_actions:++details===1?['checkin']:[],status:details===1?'pending':'unused_ended'}}),checkinReservation:async()=>{throw{status:409,details:{termination_reason:'no_show'}}}})
  await page.submitCode(code);assert.match(page.scanError.value,/已超过签到期限/);assert.equal(page.canCheckin.value,false)
  const network=await checkin({checkinReservation:async()=>{throw{status:null}}});await network.submitCode(code);assert.match(network.scanError.value,/结果尚未确认/);assert.equal(network.success.value,false);assert.equal(network.submitting.value,false)
})
test('API adapters use explicit code body, own reservation detail and Bearer blob request configuration',async()=>{
  const calls=[];const request={get:(...args)=>calls.push(['get',...args]),post:(...args)=>calls.push(['post',...args]),patch:(...args)=>calls.push(['patch',...args])}
  const api=await moduleFrom(await readFile(new URL('../src/api/reservation.js',import.meta.url),'utf8'),{'./request':{default:request}})
  api.getReservation(7);api.checkinReservation(7,code)
  assert.equal(calls[0][1],'/reservations/7');assert.equal(calls[1][2].seat_code,code)
  const admin=await moduleFrom(await readFile(new URL('../src/api/admin.js',import.meta.url),'utf8'),{'./request':{default:request}})
  admin.getSeatCodePng(3,2);assert.equal(calls.at(-1)[2].responseType,'blob');assert.equal(calls.at(-1)[2].params.expected_version,2)
  admin.rotateSeatCode(3,{version:2,reason:'更换打印件'});assert.equal(calls.at(-1)[1],'/admin/seats/3/code/rotate')
})
test('mine navigates to scan route instead of old bodyless checkin; printing uses safe DOM and loaded image',async()=>{
  const mine=await readFile(new URL('../src/views/MyReservationsView.vue',import.meta.url),'utf8')
  assert.match(mine,/router.push\(\{ name: 'checkin'/);assert.doesNotMatch(mine,/checkinReservation/)
  const routes=await readFile(new URL('../src/router/index.js',import.meta.url),'utf8');assert.match(routes,/path: '\/mine\/:id\/checkin'/)
  const admin=await readFile(new URL('../src/components/admin/SeatCodePanel.vue',import.meta.url),'utf8')
  assert.doesNotMatch(admin,/document.write|innerHTML/);assert.match(admin,/image.onload = .*popup.print/);assert.match(admin,/if \(!imageReady.value/)
})


test('seat code panel uses code version for writes, preserves reasons on conflict, and revokes replaced PNG URLs',async()=>{
  const calls=[],revoked=[];let version=0,configured=false,fail=false,pngFailure=false
  const api={
    getSeatCode:async()=>({data:{seat_id:3,configured,enabled:true,version}}),
    getSeatCodePng:async(id,expectedVersion)=>{assert.equal(expectedVersion,version);if(pngFailure)throw{status:409,message:'码版本已变更'};return{type:'image/png'}},
    createSeatCode:async(id,body)=>{calls.push(['create',id,body]);configured=true;version=1},
    rotateSeatCode:async(id,body)=>{calls.push(['rotate',id,body]);if(fail)throw{status:409,message:'码已轮换'};version++},
    updateSeatCode:async(id,body)=>{calls.push(['toggle',id,body]);version++}
  }
  let cleanup
  const page=await view('../src/components/admin/SeatCodePanel.vue',{
    vue:{ref,onMounted(){},onBeforeUnmount:handler=>{cleanup=handler}},'../../api/admin':api,
    './userForm.js':{operationReason:text=>{if(!text.trim())throw new Error('请填写操作理由');return text.trim()}}
  },'load,mutate,reason,metadata,imageUrl,imageReady,pngError,printCode,error,formError,saving',{defineProps:()=>({seat:{id:3,version:99,seat_no:'A1'},roomName:'测试空间'}),defineEmits:()=>()=>{},URL:{createObjectURL:()=> 'blob:png',revokeObjectURL:url=>revoked.push(url)}})
  await page.load();assert.equal(page.metadata.value.configured,false)
  await page.mutate('create');assert.match(page.formError.value,/理由/);assert.equal(calls.length,0)
  page.reason.value=' 初次打印 ';await page.mutate('create');assert.equal(calls[0][2].version,0);assert.equal(page.metadata.value.version,1)
  page.reason.value='旧件丢失';fail=true;await page.mutate('rotate');assert.equal(calls[1][2].version,1);assert.equal(page.reason.value,'旧件丢失');assert.match(page.error.value,/刷新当前码状态/)
  fail=false;await page.load();await page.mutate('rotate');assert.equal(page.metadata.value.version,2);assert.ok(revoked.length>0)
  page.imageReady.value=true;pngFailure=true;await page.load();assert.equal(page.imageUrl.value,'');assert.equal(page.imageReady.value,false);assert.match(page.pngError.value,/版本不一致/);page.printCode()
  cleanup();assert.equal(page.imageUrl.value,'')
})

test('a prior reservation submission cannot mark another route as successful or reload after unmount',async()=>{
  const pending=deferred()
  const page=await checkin({checkinReservation:async()=>pending.promise})
  const attempt=page.submitCode(code);page.changeId('8');await tick();pending.resolve();await attempt
  assert.equal(page.success.value,false);assert.equal(page.calls.filter(call=>call[0]==='detail').length,2)
  const leavePending=deferred();const leaving=await checkin({checkinReservation:async()=>leavePending.promise})
  const leavingAttempt=leaving.submitCode(code);leaving.cleanup[0]();leavePending.resolve();await leavingAttempt
  assert.equal(leaving.success.value,false);assert.equal(leaving.calls.filter(call=>call[0]==='detail').length,1)
})
test('network result uncertainty is resolved only by true checked-in detail, including a later explicit retry',async()=>{
  let reads=0
  const page=await checkin({getReservation:async()=>{reads++;if(reads===2)throw new Error('查询断网');return{data:{status:reads===1?'pending':'checked_in',allowed_actions:reads===1?['checkin']:[]}}},checkinReservation:async()=>{throw{status:null}}})
  await page.submitCode(code);assert.match(page.scanError.value,/结果尚未确认/);assert.match(page.detailError.value,/查询断网/);assert.equal(page.success.value,false)
  await page.load();assert.equal(page.scanError.value,'');assert.match(page.scanNotice.value,/查询确认当前已签到，无需重试/);assert.equal(page.canCheckin.value,false);assert.equal(page.success.value,false)
})
