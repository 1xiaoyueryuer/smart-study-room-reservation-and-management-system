import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { effectScope, ref, nextTick, onScopeDispose, watch } from 'vue'

async function popover(dialogLayer = null) {
  const listeners = new Map()
  let dialog = dialogLayer === null ? null : { layer: dialogLayer }
  const trigger = { getBoundingClientRect: () => ({ left: 100, top: 100, bottom: 134, width: 220 }), closest: selector => { assert.equal(selector, '[role="dialog"]'); return dialog } }
  const target = prefix => ({ addEventListener: (event, callback) => listeners.set(`${prefix}:${event}`, callback), removeEventListener: event => listeners.delete(`${prefix}:${event}`) })
  const context = vm.createContext({ window: { innerWidth: 1024, innerHeight: 768, getComputedStyle: element => ({ zIndex: element.layer }), ...target('window') }, document: target('document') })
  const source = await readFile(new URL('../src/components/ui/usePopover.js', import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(source, { context })
  await module.link(() => new vm.SyntheticModule(['ref', 'nextTick', 'onScopeDispose', 'watch'], function () {
    this.setExport('ref', ref); this.setExport('nextTick', nextTick); this.setExport('onScopeDispose', onScopeDispose); this.setExport('watch', watch)
  }, { context }))
  await module.evaluate()
  const scope = effectScope()
  const popup = scope.run(() => module.namespace.usePopover(ref(trigger), ref(null)))
  return { popup, scope, listeners, setDialog: layer => { dialog = layer === null ? null : { layer } } }
}
test('dialog dropdown floats above the owning modal rather than the ordinary popover layer', async () => {
  const state = await popover('400')
  try {
    state.popup.show()
    await nextTick()
    assert.equal(state.popup.popupStyle.value.zIndex, 401)
    state.setDialog('800')
    state.popup.update()
    assert.equal(state.popup.popupStyle.value.zIndex, 801)
  } finally { state.scope.stop() }
})
test('ordinary dropdown retains CSS layer and moving out of modal removes inline override', async () => {
  const state = await popover(null)
  try {
    state.popup.show()
    await nextTick()
    assert.equal('zIndex' in state.popup.popupStyle.value, false)
    assert.equal(state.popup.popupStyle.value.position, 'fixed')
    state.setDialog('400')
    state.popup.update()
    assert.equal(state.popup.popupStyle.value.zIndex, 401)
    state.setDialog(null)
    state.popup.update()
    assert.equal('zIndex' in state.popup.popupStyle.value, false)
  } finally { state.scope.stop() }
})
test('disposing an open dropdown releases document and window listeners', async () => {
  const state = await popover('400')
  state.popup.show()
  await nextTick()
  assert.equal(state.listeners.size, 3)
  state.scope.stop()
  assert.equal(state.listeners.size, 0)
})
