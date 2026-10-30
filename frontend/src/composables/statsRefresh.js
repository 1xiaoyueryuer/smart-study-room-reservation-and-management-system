// Scheduling only: StatsService and each view remain responsible for facts and DTO validation.
export function createStatsRefresh({resources,onVisibility=()=>{},documentRef=globalThis.document,
 setIntervalFn=globalThis.setInterval,clearIntervalFn=globalThis.clearInterval}) {
 const slots=new Map(Object.entries(resources).map(([name,callbacks])=>[name,{...callbacks,
  query:null,key:null,revision:0,inFlight:false,wanted:false,waiters:[],lastValue:null}]))
 let active=false,disposed=false,visible=documentRef?.visibilityState!=='hidden',timer=null
 const keyOf=query=>query===null?null:JSON.stringify(Object.keys(query).sort().map(key=>[key,query[key]]))
 function settle(slot,revision,value){const remaining=[];for(const waiter of slot.waiters){if(waiter.revision===revision)waiter.resolve(value);else remaining.push(waiter)}slot.waiters=remaining}
 function invalidate(slot){for(const waiter of slot.waiters)waiter.resolve(null);slot.waiters=[];++slot.revision;slot.lastValue=null}
 const current=(slot,revision)=>!disposed&&active&&visible&&slot.query!==null&&revision===slot.revision
 function wait(slot){if(disposed||slot.query===null)return Promise.resolve(null);if(!slot.inFlight&&!slot.wanted)return Promise.resolve(slot.lastValue);return new Promise(resolve=>slot.waiters.push({revision:slot.revision,resolve}))}
 async function execute(slot){
  const revision=slot.revision,query={...slot.query};let value=null
  slot.inFlight=true;slot.wanted=false;slot.loading?.(true)
  try{
   const response=await slot.read(query)
   if(current(slot,revision))value=await slot.success(response,query)
   if(current(slot,revision))slot.lastValue=value
  }catch(error){if(current(slot,revision))slot.failure?.(error,query)}
  finally{
   // Never release this flag on hide, filter change or disposal: the real request must settle.
   slot.inFlight=false
   if(!disposed)slot.loading?.(false)
   settle(slot,revision,current(slot,revision)?value:null)
   pump(slot)
  }
 }
 function pump(slot){if(!disposed&&active&&visible&&slot.query!==null&&slot.wanted&&!slot.inFlight)void execute(slot)}
 function select(name,query,{refresh=true}={}){
  const slot=slots.get(name);if(!slot)throw new Error('Unknown statistics resource')
  if(disposed)return Promise.resolve(null)
  const key=keyOf(query),changed=slot.key!==key
  if(changed){invalidate(slot);slot.key=key;slot.query=query===null?null:{...query};slot.wanted=query!==null}
  else if(refresh&&query!==null&&!slot.inFlight)slot.wanted=true
  if(query===null){slot.wanted=false;return Promise.resolve(null)}
  const completion=wait(slot);pump(slot);return completion
 }
 function refresh(name){
  const targets=name?[slots.get(name)]:[...slots.values()]
  if(targets.some(slot=>!slot))throw new Error('Unknown statistics resource')
  return Promise.all(targets.map(slot=>{if(!disposed&&slot.query!==null&&!slot.inFlight)slot.wanted=true;const completion=wait(slot);pump(slot);return completion}))
 }
 function clearTimer(){if(timer!==null){clearIntervalFn(timer);timer=null}}
 function tick(){for(const slot of slots.values())if(slot.query!==null&&!slot.inFlight){slot.wanted=true;pump(slot)}}
 function armTimer(){clearTimer();if(active&&!disposed&&visible)timer=setIntervalFn(tick,10000)}
 function visibilityChanged(){
  const next=documentRef?.visibilityState!=='hidden'
  if(next===visible||disposed)return
  visible=next;onVisibility(visible)
  if(!visible){clearTimer();for(const slot of slots.values()){invalidate(slot);slot.wanted=false}}
  else{armTimer();for(const slot of slots.values())if(slot.query!==null){slot.wanted=true;pump(slot)}}
 }
 function start(){
  if(active||disposed)return
  active=true;visible=documentRef?.visibilityState!=='hidden';onVisibility(visible)
  documentRef?.addEventListener('visibilitychange',visibilityChanged)
  armTimer();for(const slot of slots.values())pump(slot)
 }
 function stop(){
  if(disposed)return
  disposed=true;active=false;clearTimer();documentRef?.removeEventListener('visibilitychange',visibilityChanged)
  for(const slot of slots.values()){invalidate(slot);slot.wanted=false}
 }
 return{select,refresh,start,stop}
}
