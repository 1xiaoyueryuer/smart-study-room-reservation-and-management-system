import test from 'node:test'
import assert from 'node:assert/strict'
import {createStatsRefresh} from '../src/composables/statsRefresh.js'
import {statsClock,deferred} from './helpers/statsClock.js'

test('visible statistics refresh immediately and every ten seconds for three actual timer rounds',async()=>{
 const clock=statsClock(),reads=[],accepted=[],poll=createStatsRefresh({...clock.options,resources:Object.fromEntries(['overview','heat'].map(name=>[name,{read:async query=>{reads.push([name,{...query}]);return reads.length},success:value=>{accepted.push([name,value]);return value}}]))})
 const first=poll.select('overview',{}),second=poll.select('heat',{room_id:1,date:'2026-10-30'});assert.equal(reads.length,0);poll.start();await Promise.all([first,second]);await clock.advance(30000);assert.equal(reads.filter(([name])=>name==='overview').length,4);assert.equal(reads.filter(([name])=>name==='heat').length,4);assert.equal(accepted.length,8);assert.equal(clock.timers.size,1);poll.stop();assert.equal(clock.timers.size,0);assert.equal(clock.listeners.size,0)
})

test('busy resource never overlaps while the other resource continues its timer refreshes',async()=>{
 const clock=statsClock(),pending=deferred(),reads=[],loading=[],poll=createStatsRefresh({...clock.options,resources:{overview:{read:()=>{reads.push('overview');return pending.promise},success:value=>value,loading:value=>loading.push(value)},heat:{read:async()=>{reads.push('heat');return 1},success:value=>value}}})
 const first=poll.select('overview',{});poll.select('heat',{room_id:1});poll.start();await clock.flush();await clock.advance(30000);assert.equal(reads.filter(name=>name==='overview').length,1);assert.equal(reads.filter(name=>name==='heat').length,4);assert.deepEqual(loading,[true]);pending.resolve(7);assert.equal(await first,7);await clock.flush();assert.deepEqual(loading,[true,false]);poll.stop()
})

test('rapid A B C selection waits for the real A request, discards its result, and sends only C next',async()=>{
 const clock=statsClock(),pending=[],accepted=[],failures=[],loading=[],poll=createStatsRefresh({...clock.options,resources:{overview:{read:query=>{const task=deferred();pending.push({query,...task});return task.promise},success:(data,query)=>{accepted.push([data,query.room_id]);return data},failure:error=>failures.push(error),loading:value=>loading.push(value)}}})
 poll.start();const a=poll.select('overview',{room_id:1}),b=poll.select('overview',{room_id:2}),c=poll.select('overview',{room_id:3});await clock.advance(20000);assert.equal(pending.length,1);assert.deepEqual(loading,[true]);assert.equal(await a,null);assert.equal(await b,null);pending[0].reject(new Error('old 503'));await clock.flush();assert.equal(pending.length,2);assert.equal(pending[1].query.room_id,3);assert.equal(failures.length,0);assert.equal(accepted.length,0);pending[1].resolve('current');assert.equal(await c,'current');await clock.flush();assert.deepEqual(accepted,[['current',3]]);poll.stop()
})

test('hidden pause keeps the real request occupied; visible resume queues one fresh read after it settles',async()=>{
 const clock=statsClock(),pending=[],accepted=[],loading=[],poll=createStatsRefresh({...clock.options,resources:{overview:{read:()=>{const task=deferred();pending.push(task);return task.promise},success:value=>{accepted.push(value);return value},loading:value=>loading.push(value)}}})
 poll.select('overview',{});poll.start();await clock.visibility('hidden');assert.equal(clock.timers.size,0);await clock.advance(30000);assert.equal(pending.length,1);assert.deepEqual(loading,[true]);await clock.visibility('visible');assert.equal(pending.length,1);assert.equal(clock.timers.size,1);pending[0].resolve('hidden stale');await clock.flush();assert.equal(pending.length,2);assert.equal(accepted.length,0);pending[1].resolve('resumed');await clock.flush();assert.deepEqual(accepted,['resumed']);poll.stop()
})

test('unmount releases callers, cancels timer/listener and never accepts a late success or launches queued work',async()=>{
 const clock=statsClock(),pending=deferred(),reads=[],accepted=[],loading=[],poll=createStatsRefresh({...clock.options,resources:{overview:{read:query=>{reads.push(query);return pending.promise},success:value=>accepted.push(value),loading:value=>loading.push(value)}}})
 poll.start();const first=poll.select('overview',{room_id:1}),last=poll.select('overview',{room_id:2});poll.stop();assert.equal(await first,null);assert.equal(await last,null);pending.resolve('late');await clock.flush();await clock.advance(30000);assert.equal(reads.length,1);assert.equal(accepted.length,0);assert.deepEqual(loading,[true]);assert.equal(clock.timers.size,0);assert.equal(clock.listeners.size,0);poll.start();assert.equal(clock.timers.size,0)
})

test('first failure remains an error and next real read recovers; manual retries join active reads without duplication',async()=>{
 const clock=statsClock(),pending=[],errors=[],accepted=[],poll=createStatsRefresh({...clock.options,resources:{overview:{read:()=>{const task=deferred();pending.push(task);return task.promise},success:value=>{accepted.push(value);return value},failure:error=>errors.push(error.message)}}})
 poll.start();const first=poll.select('overview',{});pending[0].reject(new Error('503 controlled'));assert.equal(await first,null);assert.deepEqual(errors,['503 controlled']);assert.equal(accepted.length,0);const retry=poll.refresh('overview'),joined=poll.refresh('overview');assert.equal(pending.length,2);await clock.advance(30000);assert.equal(pending.length,2);pending[1].resolve({snapshot_at:'real-server-time'});await Promise.all([retry,joined]);assert.equal(accepted.length,1);assert.deepEqual(accepted[0],{snapshot_at:'real-server-time'});poll.stop()
})

test('initially hidden page sends no request, and selecting or disabling a resource never reads an unselected scope',async()=>{
 const clock=statsClock('hidden'),reads=[],poll=createStatsRefresh({...clock.options,resources:{heat:{read:async query=>{reads.push(query);return query},success:value=>value}}});poll.select('heat',{room_id:1});poll.start();await clock.advance(30000);assert.equal(reads.length,0);poll.select('heat',{room_id:2});await clock.visibility('visible');assert.equal(reads.length,1);assert.equal(reads[0].room_id,2);await poll.select('heat',null);await clock.advance(30000);assert.equal(reads.length,1);poll.stop()
})
