<script setup>
import { ref, shallowRef, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { gsap } from 'gsap'
import SButton from '../ui/SButton.vue'
import { feedbackState } from '../ui/feedback'
import { seatType, seatState, stateLabels, stateColors, zoneName } from './seatPresentation'

const props = defineProps({ origin: Object, room: Object, seats: Array, selectedId: Number, date: String, start: String, end: String, loading: Boolean, submitting: Boolean, submitError: String, policyReady: Boolean })
const emit = defineEmits(['select', 'submit', 'closed'])
const panel = ref(null), backdrop = ref(null), content = ref(null), snapshot = ref(null)
const Scene = shallowRef(null), error = ref(''), ready = ref(false), closing = ref(false), opening = ref(true), view = ref('overview')
const selected = computed(() => props.seats.find(s => s.id === props.selectedId))
const sceneRef = ref(null)
const canExperience = computed(() => ready.value && props.room?.enabled && !error.value && selected.value?.status === 'available' && selected.value?.bookable_for_slot === true && !selected.value?.occupied)
watch(canExperience, valid => { if (!valid && view.value === 'firstPerson') view.value = 'overview' })
const busy = computed(() => props.loading || props.submitting || opening.value || closing.value || !!feedbackState.box)
let sceneLoadFrame = 0
let animation, ctx, alive = true, restoreFocus, previousOverflow, originalVisibility, background, wasInert, clone
const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
// Measure the untransformed layout once; never fit/revert the live panel mid-animation.
function originTransform() {
  const source = props.origin.getBoundingClientRect()
  const width = panel.value.offsetWidth, height = panel.value.offsetHeight
  const overlay = panel.value.parentElement.getBoundingClientRect()
  return { x: source.left - (overlay.left + (overlay.width - width) / 2),
    y: source.top - (overlay.top + (overlay.height - height) / 2),
    scaleX: source.width / width, scaleY: source.height / height }
}
function refreshSnapshot() {
  const width = props.origin.offsetWidth, height = props.origin.offsetHeight
  clone = props.origin.cloneNode(true)
  clone.removeAttribute('id'); clone.querySelectorAll('[id]').forEach(el => el.removeAttribute('id'))
  // Preserve the source card's line wrapping and grid layout at both handoff points.
  Object.assign(clone.style, { width: `${width}px`, height: `${height}px`, maxWidth: 'none',
    boxSizing: 'border-box', margin: '0', visibility: 'visible', overflow: 'hidden',
    transformOrigin: '0 0', transform: `scale(${panel.value.clientWidth / width}, ${panel.value.clientHeight / height})` })
  clone.inert = true
  snapshot.value.replaceChildren(clone)
}
// Keep module evaluation, model construction and the first WebGL render off the opening tween.
function finishOpening() {
  opening.value = false
  gsap.set(panel.value, { willChange: 'auto' })
  sceneLoadFrame = requestAnimationFrame(() => {
    sceneLoadFrame = requestAnimationFrame(async () => {
      if (!alive || closing.value) return
      try {
        const module = await import('./SeatScene.vue')
        if (alive && !closing.value) Scene.value = module.default
      } catch { if (alive && !closing.value) error.value = '3D 资源加载失败，请返回平面图后重试。' }
    })
  })
}
function close(force = false) {
  if (closing.value || (!force && props.submitting)) return
  closing.value = true
  cancelAnimationFrame(sceneLoadFrame)
  gsap.set(panel.value, { willChange: 'transform' })
  animation?.kill()
  gsap.killTweensOf([panel.value, content.value, snapshot.value, backdrop.value])
  opening.value = false
  refreshSnapshot()
  const duration = reduced() ? .12 : .45
  ctx.add(() => {
    gsap.to(content.value, { opacity: 0, duration: duration * .55 })
    gsap.to(snapshot.value, { autoAlpha: 1, duration: duration * .65 })
    gsap.to(backdrop.value, { opacity: 0, duration })
    if (props.origin?.isConnected && !reduced()) {
      animation = gsap.to(panel.value, { ...originTransform(), duration, ease: 'power3.inOut', overwrite: 'auto', onComplete: () => emit('closed') })
    } else animation = gsap.to(panel.value, { opacity: 0, duration, onComplete: () => emit('closed') })
  })
}
defineExpose({ close: () => close(true) })
function focusables(root) { return [...root.querySelectorAll('button:not(:disabled), select:not(:disabled), [tabindex="0"]')].filter(el => el.getClientRects().length) }
function onKey(e) {
  const top = document.querySelector('.msgbox__panel')
  const root = top || panel.value
  if (!root) return
  if (e.key === 'Escape') {
    if (feedbackState.box) return
    e.preventDefault(); e.stopPropagation(); close()
  }
  if (e.key === 'Tab') {
    const items = focusables(root), first = items[0], last = items.at(-1)
    if (!first) { e.preventDefault(); root.focus(); return }
    if (e.shiftKey && (document.activeElement === first || !root.contains(document.activeElement))) { e.preventDefault(); last.focus() }
    else if (!e.shiftKey && (document.activeElement === last || !root.contains(document.activeElement))) { e.preventDefault(); first.focus() }
  }
}
watch(() => feedbackState.box, async box => {
  await nextTick()
  if (!alive) return
  const root = box ? document.querySelector('.msgbox__panel') : panel.value
  if (root) (focusables(root)[0] || root).focus({ preventScroll: true })
})
function resized() {
  // A resize must not force an unfinished tween to its endpoint.
  if (opening.value || closing.value) return
  panel.value.style.width = ''
  panel.value.style.height = ''
}
function chooseFromList(e) { const s = props.seats.find(s => String(s.id) === e.target.value); if (s) emit('select', s) }
onMounted(() => {
  restoreFocus = document.activeElement
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  background = document.getElementById('app')
  wasInert = background?.inert
  if (background) background.inert = true
  originalVisibility = props.origin.style.visibility
  // Freeze layout dimensions while the visual shell travels between the two rectangles.
  const bounds = panel.value.getBoundingClientRect()
  panel.value.style.width = `${bounds.width}px`
  panel.value.style.height = `${bounds.height}px`
  refreshSnapshot()
  props.origin.style.visibility = 'hidden'
  ctx = gsap.context(() => {}, panel.value)
  ctx.add(() => {
    const duration = reduced() ? .12 : .55
    gsap.set(panel.value, { willChange: 'transform' })
    gsap.set(content.value, { opacity: 0 })
    if (!reduced()) {
      gsap.set(panel.value, originTransform())
      animation = gsap.to(panel.value, { x: 0, y: 0, scaleX: 1, scaleY: 1,
        duration, ease: 'power3.inOut', onComplete: finishOpening })
    } else animation = gsap.from(panel.value, { opacity: 0, duration, onComplete: finishOpening })
    gsap.fromTo(backdrop.value, { opacity: 0 }, { opacity: 1, duration })
    gsap.to(snapshot.value, { autoAlpha: 0, delay: duration * .25, duration: duration * .65 })
    gsap.to(content.value, { opacity: 1, delay: duration * .3, duration: duration * .7 })
  })
  panel.value.focus({ preventScroll: true })
  document.addEventListener('keydown', onKey, true)
  window.addEventListener('resize', resized)
})
onBeforeUnmount(() => {
  alive = false; cancelAnimationFrame(sceneLoadFrame); animation?.kill(); ctx?.revert()
  document.removeEventListener('keydown', onKey, true); window.removeEventListener('resize', resized)
  document.body.style.overflow = previousOverflow
  if (background) background.inert = wasInert
  if (props.origin) props.origin.style.visibility = originalVisibility
  restoreFocus?.isConnected && restoreFocus.focus({ preventScroll: true })
})
</script>

<template>
  <Teleport to="body">
    <div class="seat-explorer">
      <div ref="backdrop" class="explorer-backdrop" @click="close()" />
      <section ref="panel" class="explorer-panel" role="dialog" aria-modal="true" aria-labelledby="explorer-title" tabindex="-1">
        <div ref="snapshot" class="explorer-snapshot" aria-hidden="true" />
        <div ref="content" class="explorer-content">
          <header class="explorer-head">
            <div><div class="explorer-eyebrow">SPACE / 3D SEATING</div><h3 id="explorer-title">{{ room.name }} <span>3D 选座</span></h3><p>{{ date }} · {{ start }} – {{ end }} <span class="layout-note">· 座位布局示意</span></p></div>
            <button class="explorer-close" aria-label="关闭 3D 选座" :disabled="submitting" @click="close()">×</button>
          </header>
          <div class="explorer-toolbar">
            <div class="view-switch" aria-label="切换视角">
              <button :class="{ active: view === 'overview' }" :disabled="busy" @click="view = 'overview'">◈ 总览</button>
              <button :class="{ active: view === 'top' }" :disabled="busy" @click="view = 'top'">▦ 俯视</button>
              <button :class="{ active: view === 'firstPerson' }" :disabled="busy || !canExperience" :title="canExperience ? '从所选座位环视室内' : '请先选择可预约的座位'" @click="view = 'firstPerson'">坐席体验</button>
            </div>
            <div class="explorer-legend"><span v-for="(label, state) in stateLabels" :key="state"><i :style="{ background: stateColors[state] }" />{{ label }}</span></div>
          </div>
          <div class="explorer-stage" :aria-busy="!ready || loading">
            <component :is="Scene" v-if="Scene && !error" ref="sceneRef" :room="room" :seats="seats" :selected-id="selectedId" :view="view" :blocked="busy || !room?.enabled" @select="emit('select', $event)" @ready="ready = true" @error="error = $event" @manual-view="view = ''" @exit-first-person="view = 'overview'" />
            <div v-if="error || !ready || loading" class="scene-message" role="status"><strong>{{ error ? '暂时无法显示 3D' : loading ? '正在更新座位' : '正在搭建你的自习空间' }}</strong><p>{{ error || '窗户、桌椅和座位正在就位…' }}</p><SButton v-if="error" @click="close()">返回座位平面图</SButton></div>
            <div v-if="ready && !error && view === 'firstPerson'" class="seated-tools"><span aria-live="polite">正在体验 {{ selected?.seat_no }}</span><button :disabled="busy" @click="sceneRef?.resetLook()">回正视线</button></div>
            <div v-if="ready && !error" class="scene-hint">{{ view === 'firstPerson' ? '拖动 / 方向键环视 · 点击总览退出体验' : '拖动旋转 · 滚轮 / 双指缩放 · 点击桌椅选座' }}</div>
          </div>
          <p v-if="submitError" role="alert" class="explorer-submit-error">{{ submitError }}</p>
          <footer class="explorer-footer">
            <div class="explorer-selection" aria-live="polite"><strong>{{ selected ? `已选择 ${selected.seat_no}` : '找一个喜欢的位置' }}</strong><span>{{ selected ? `${seatType(selected)} · ${zoneName[selected.zone]}${selected.near_window ? ' · 靠窗' : ''}` : '选择可预约的桌椅，或使用座位列表' }}</span></div>
            <label class="seat-list"><span class="sr-only">按编号选择座位</span><select :value="selectedId ?? ''" :disabled="busy || !!error" @change="chooseFromList"><option value="" disabled>按编号选座</option><option v-for="s in seats" :key="s.id" :value="s.id" :disabled="s.status !== 'available' || s.bookable_for_slot !== true || s.occupied">{{ s.seat_no }} · {{ stateLabels[seatState(s, selectedId)] }}</option></select></label>
            <SButton variant="primary" size="lg" :disabled="!policyReady || !room?.enabled || !selected || selected.bookable_for_slot !== true || busy || !!error" :loading="submitting" @click="emit('submit')">提交预约</SButton>
          </footer>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.explorer-submit-error { color:var(--red-strong); padding:10px 20px; font-size:var(--fs-caption); }
.seat-explorer { position: fixed; inset: 0; z-index: var(--z-dialog); display: grid; place-items: center; }
.explorer-backdrop { position: absolute; inset: 0; background: rgb(20 29 43 / 35%); }
.explorer-panel { position: relative; width: min(1280px, calc(100vw - 48px)); height: min(850px, calc(100dvh - 48px)); background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-xl); box-shadow: var(--shadow-4); overflow: hidden; outline: none; box-sizing: border-box; transform-origin: 0 0; transition: none; }
.explorer-content { position: absolute; inset: 0; display: flex; flex-direction: column; min-height: 0; }
.explorer-snapshot { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.explorer-head { padding: 22px 26px 16px; display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.explorer-eyebrow { font-size: 10px; color: var(--text-3); letter-spacing: .17em; margin-bottom: 6px; }
.explorer-head h3 { margin: 0; font-size: 21px; color: var(--text-1); }
.explorer-head h3 span { font-size: 12px; font-weight: 500; padding: 4px 8px; border: 1px solid var(--border); border-radius: 6px; margin-left: 8px; white-space: nowrap; }
.explorer-head p { color: var(--text-3); font-size: 12px; margin: 7px 0 0; }
.explorer-close { flex-shrink: 0; border: 1px solid var(--border); width: 36px; height: 36px; border-radius: 50%; background: var(--surface); color: var(--text-2); font-size: 25px; cursor: pointer; }
.explorer-toolbar { padding: 0 26px 14px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.view-switch { display: flex; padding: 3px; background: var(--surface-2); border: 1px solid var(--border); border-radius: 10px; }
.view-switch button { border: 0; background: transparent; color: var(--text-3); border-radius: 7px; padding: 7px 14px; font: inherit; font-size: 12px; cursor: pointer; }
.view-switch button.active { background: var(--surface); color: var(--text-1); box-shadow: var(--shadow-1); }
.explorer-legend { display: flex; gap: 12px; flex-wrap: wrap; color: var(--text-3); font-size: 11px; }
.explorer-legend span { display: flex; align-items: center; gap: 5px; }
.explorer-legend i { width: 8px; height: 8px; border-radius: 3px; }
.explorer-stage { flex: 1; min-height: 120px; position: relative; overflow: hidden; border-block: 1px solid var(--border); background: var(--surface-2); }
.scene-message { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 20px; text-align: center; background: var(--surface-2); color: var(--text-2); z-index: 3; }
.scene-message p { color: var(--text-3); font-size: 13px; }
.scene-hint { position: absolute; bottom: 15px; left: 50%; transform: translateX(-50%); padding: 7px 12px; border: 1px solid var(--border); border-radius: 20px; background: var(--surface); color: var(--text-3); font-size: 11px; white-space: nowrap; pointer-events: none; }
.seated-tools { position: absolute; top: 12px; left: 14px; right: 14px; display: flex; align-items: center; justify-content: space-between; pointer-events: none; gap: 8px; }
.seated-tools span, .seated-tools button { padding: 8px 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 9px; color: var(--text-2); font: inherit; font-size: 12px; }
.seated-tools button { pointer-events: auto; cursor: pointer; }
.explorer-footer { display: flex; align-items: center; gap: 16px; padding: 18px 26px; }
.explorer-selection { flex: 1; min-width: 0; display: grid; gap: 5px; color: var(--text-1); font-size: 14px; }
.explorer-selection span { color: var(--text-3); font-size: 12px; }
.seat-list select { max-width: 170px; border: 1px solid var(--border); border-radius: 8px; padding: 9px; background: var(--surface); color: var(--text-2); font: inherit; font-size: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
button:disabled { opacity: .5; cursor: default; }
@media (max-width: 640px) {
  .explorer-panel { width: calc(100vw - 24px); height: calc(100dvh - 24px); }
  .explorer-head { padding: 16px; }.explorer-head h3 { font-size: 17px; }.layout-note { display: none; }
  .explorer-toolbar { padding: 0 16px 12px; gap: 9px; }.explorer-legend { gap: 8px; }
  .explorer-footer { padding: 12px 16px; flex-wrap: wrap; gap: 10px; }.explorer-selection { flex-basis: 100%; }
  .seat-list { flex: 1; }.seat-list select { width: 100%; max-width: none; }.scene-hint { font-size: 10px; }
}
</style>
