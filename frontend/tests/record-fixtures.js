import * as recordPage from '../src/composables/recordPage.js'
export function pageFixture(items,query={}){return {items,total:items.length,page:query.page||1,page_size:query.page_size||20,server_now:'2026-10-30T12:00+08:00'}}
// Existing small list fixtures remain their original domain objects; wrap only the
// explicitly migrated HTTP list mock exports in the S26 page envelope.
const migrated=new Set(['listMyReservations','listAdminReservations','listMyViolations','listAdminViolations','listMyRestrictions','listAdminRestrictions','listMyComplaints','listAdminComplaints','listMyAppeals','listAdminAppeals'])
export function recordTestImport(specifier,mock){if(specifier.endsWith('/recordPage.js'))return recordPage;if(!mock||!specifier.includes('/api/'))return mock;return Object.fromEntries(Object.entries(mock).map(([key,value])=>[key,migrated.has(key)?async(...args)=>{const response=await value(...args);return Array.isArray(response?.data)?{...response,data:pageFixture(response.data,args[0])}:response}:value]))}
