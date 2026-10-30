import {setImmediate as nextTurn} from 'node:timers/promises'

export function statsClock(initialVisibility='visible'){
 let now=0,sequence=0
 const timers=new Map(),listeners=new Map()
 const documentRef={visibilityState:initialVisibility,addEventListener(name,fn){listeners.set(name,fn)},removeEventListener(name,fn){if(listeners.get(name)===fn)listeners.delete(name)}}
 const options={documentRef,setIntervalFn(fn,period){const id=++sequence;timers.set(id,{fn,period,next:now+period});return id},clearIntervalFn:id=>timers.delete(id)}
 async function advance(milliseconds=10000){const target=now+milliseconds;for(;;){const due=[...timers.entries()].filter(([,timer])=>timer.next<=target).sort((a,b)=>a[1].next-b[1].next)[0];if(!due)break;now=due[1].next;due[1].next+=due[1].period;due[1].fn();await nextTurn()}now=target;await nextTurn()}
 async function visibility(value){documentRef.visibilityState=value;listeners.get('visibilitychange')?.();await nextTurn()}
 return{options,timers,listeners,advance,visibility,flush:nextTurn}
}

export function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}}
