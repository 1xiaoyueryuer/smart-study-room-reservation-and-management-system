<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { gsap } from 'gsap'
import { buildRoom } from './models'
import { seatType, seatState, stateLabels, zoneName, seatPosition } from './seatPresentation'
import { useThemeStore } from '../../stores/theme'

const props = defineProps({ room: Object, seats: Array, selectedId: Number, view: String, blocked: Boolean })
const emit = defineEmits(['select', 'error', 'ready', 'manual-view', 'exit-first-person'])
const host = ref(null)
const tip = ref(null)
const theme = useThemeStore()
const transitioning = ref(false)
let seated = false, yaw = 0, pitch = -.12, fadeTween
const lookDirection = new THREE.Vector3()
let renderer, scene, camera, controls, model, observer, frame = 0, cameraTween, targetTween
let renderWidth = 0, renderHeight = 0
let down = null, disposed = false, pointerCount = 0
const ray = new THREE.Raycaster()
const pointer = new THREE.Vector2()
const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
function invalidate() {
  if (disposed || frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    if (!renderer || !model) return
    if (!seated) controls.update()
    model.updateWalls(camera, seated); renderer.render(scene, camera)
  })
}
function resize() {
  if (!renderer || !host.value) return false
  // CSS transforms change visual bounds, not the canvas's drawing-buffer size.
  const width = Math.max(host.value.clientWidth, 1), height = Math.max(host.value.clientHeight, 1)
  if (width === renderWidth && height === renderHeight) return false
  renderWidth = width; renderHeight = height
  renderer.setSize(width, height)
  camera.aspect = width / Math.max(height, 1); camera.updateProjectionMatrix(); invalidate()
  return true
}
function setView(view = 'overview', animate = true) {
  if (!model) return
  if (view === 'firstPerson') { enterSeat(animate); return }
  if (seated) { fadeSwitch(() => { seated = false; setView(view, false) }, animate); return }
  if (transitioning.value && animate) {
    fadeTween?.kill(); transitioning.value = false
    gsap.set(renderer.domElement, { opacity: 1 })
  }
  cameraTween?.kill(); targetTween?.kill()
  camera.fov = 38; camera.near = .1; camera.updateProjectionMatrix()
  const halfFov = THREE.MathUtils.degToRad(camera.fov / 2)
  const span = Math.hypot(model.width, model.depth)
  const distance = span / (2 * Math.tan(halfFov) * Math.min(1, camera.aspect)) * 1.14
  const direction = view === 'top' ? new THREE.Vector3(0, 1, .001) : new THREE.Vector3(.8, 1.05, 1).normalize()
  const destination = direction.multiplyScalar(distance)
  controls.maxDistance = distance * 2.5
  camera.far = Math.max(500, distance * 5); camera.updateProjectionMatrix()
  const duration = animate && !reduced() ? .7 : 0
  controls.enabled = false
  cameraTween = gsap.to(camera.position, { x: destination.x, y: destination.y, z: destination.z, duration, ease: 'power2.inOut', onUpdate: invalidate, onComplete: () => { controls.enabled = !props.blocked; invalidate() } })
  targetTween = gsap.to(controls.target, { x: 0, y: 0, z: 0, duration, ease: 'power2.inOut', onUpdate: invalidate })
}
function applyLook() {
  pitch = THREE.MathUtils.clamp(pitch, -Math.PI * 65 / 180, Math.PI / 3)
  yaw %= Math.PI * 2
  lookDirection.set(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch))
  camera.lookAt(lookDirection.add(camera.position)); invalidate()
}
function syncControls() { if (controls) controls.enabled = !props.blocked && !seated && !transitioning.value }
function fadeSwitch(change, animate = true) {
  fadeTween?.kill(); cameraTween?.kill(); targetTween?.kill(); cancel()
  transitioning.value = true; syncControls()
  const duration = animate && !reduced() ? .18 : 0
  fadeTween = gsap.timeline({ onComplete: () => { transitioning.value = false; syncControls() } })
    .to(renderer.domElement, { opacity: 0, duration })
    .call(() => { change(); model.updateWalls(camera, seated); renderer.render(scene, camera) })
    .to(renderer.domElement, { opacity: 1, duration })
}
function enterSeat(animate = true) {
  const seat = props.seats.find(s => s.id === props.selectedId && s.status === 'available' && s.matching_windows?.length > 0)
  if (!seat) { emit('exit-first-person'); return }
  fadeSwitch(() => {
    seated = true
    const { x, z } = seatPosition(props.room, seat)
    camera.position.set(x, 1.2, z + .69)
    camera.fov = 65; camera.near = .025; camera.updateProjectionMatrix()
    yaw = 0; pitch = -.12; applyLook()
    renderer.domElement.style.cursor = 'grab'
    host.value?.focus({ preventScroll: true })
  }, animate)
}
function resetLook() { if (seated && !props.blocked && !transitioning.value) { yaw = 0; pitch = -.12; applyLook(); host.value?.focus({ preventScroll: true }) } }
defineExpose({ resetLook })
function onKey(e) {
  if (!seated || props.blocked || transitioning.value) return
  const step = .06
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return
  e.preventDefault(); e.stopPropagation()
  if (e.key === 'ArrowLeft') yaw -= step
  if (e.key === 'ArrowRight') yaw += step
  if (e.key === 'ArrowUp') pitch += step
  if (e.key === 'ArrowDown') pitch -= step
  applyLook()
}
function rebuild() {
  if (!renderer) return
  if (model) { scene.remove(model.root); model.dispose() }
  model = buildRoom(props.room, props.seats, theme.isDark)
  scene.add(model.root)
  scene.background = new THREE.Color(theme.isDark ? '#1c2632' : '#f1f3f0')
  model.updateSelection(props.selectedId)
  tip.value = null
  if (!seated) setView(props.view, false)
  invalidate()
}
function hit(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1)
  ray.setFromCamera(pointer, camera)
  const item = ray.intersectObjects(model.pickables, false)[0]
  const seat = item?.object.userData.seats?.[item.instanceId]
  tip.value = seat ? { seat, x: Math.max(8, Math.min(event.clientX - rect.left + 14, rect.width - 225)), y: Math.max(8, Math.min(event.clientY - rect.top + 14, rect.height - 100)) } : null
  renderer.domElement.style.cursor = seat?.status === 'available' && seat.matching_windows?.length ? 'pointer' : 'grab'
  return seat
}
function onDown(e) {
  if (props.blocked || transitioning.value || (e.pointerType === 'mouse' && e.button !== 0)) return
  pointerCount++; down = pointerCount === 1 ? { x: e.clientX, y: e.clientY, id: e.pointerId } : null
  if (seated) { host.value.focus({ preventScroll: true }); host.value.setPointerCapture(e.pointerId) }
}
function onMove(e) {
  if (props.blocked || transitioning.value) return
  if (seated) {
    if (down?.id === e.pointerId) {
      yaw -= (e.clientX - down.x) * .005; pitch += (e.clientY - down.y) * .005
      down.x = e.clientX; down.y = e.clientY; applyLook()
    }
    return
  }
  if (!pointerCount) hit(e); else tip.value = null
}
function onUp(e) {
  pointerCount = Math.max(0, pointerCount - 1)
  if (host.value?.hasPointerCapture(e.pointerId)) host.value.releasePointerCapture(e.pointerId)
  if (!seated && !transitioning.value && down?.id === e.pointerId && !props.blocked && Math.hypot(e.clientX - down.x, e.clientY - down.y) < 6) {
    const seat = hit(e); if (seat?.status === 'available' && seat.matching_windows?.length) emit('select', seat)
  }
  down = null
}
function cancel() { down = null; pointerCount = 0; tip.value = null }
function contextLost(e) { e.preventDefault(); emit('error', '3D 显示已中断，请返回平面图后重试。') }
function manual() { tip.value = null; if (!seated) emit('manual-view') }
watch(() => props.selectedId, id => { model?.updateSelection(id); if (props.view === 'firstPerson') enterSeat(); invalidate() })
watch(() => [props.room, props.seats, theme.isDark], rebuild)
watch(() => props.view, value => { if (value) setView(value) })
watch(() => props.blocked, () => { cancel(); syncControls() })
onMounted(() => {
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.75))
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.domElement.setAttribute('aria-label', '三维座位模型，拖动旋转，滚轮缩放；也可使用下方座位列表选择')
    host.value.prepend(renderer.domElement)
    scene = new THREE.Scene()
    scene.add(new THREE.HemisphereLight('#ffffff', '#738392', 2.5))
    const light = new THREE.DirectionalLight('#fff2df', 3); light.position.set(-8, 16, 8); scene.add(light)
    camera = new THREE.PerspectiveCamera(38, 1, .1, 500)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = false; controls.enablePan = false
    controls.minDistance = 2; controls.maxPolarAngle = Math.PI / 2 - .08
    controls.addEventListener('change', invalidate); controls.addEventListener('start', manual)
    renderer.domElement.addEventListener('webglcontextlost', contextLost)
    resize(); rebuild()
    observer = new ResizeObserver(() => { if (resize() && props.view && !seated && !transitioning.value) setView(props.view, false) })
    observer.observe(host.value)
    emit('ready')
  } catch { emit('error', '当前设备无法启动 3D 场景，可返回平面图继续选座。') }
})
onBeforeUnmount(() => {
  disposed = true; cancelAnimationFrame(frame); observer?.disconnect(); cameraTween?.kill(); targetTween?.kill(); fadeTween?.kill()
  controls?.dispose(); model?.dispose()
  renderer?.domElement.removeEventListener('webglcontextlost', contextLost)
  renderer?.dispose(); renderer?.forceContextLoss(); renderer?.domElement.remove()
})
</script>

<template>
  <div ref="host" class="scene-host" tabindex="0" aria-label="三维座位场景，坐席体验中可拖动或使用方向键环视" @keydown="onKey" @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointercancel="cancel" @pointerleave="cancel">
    <div v-if="tip" class="model-tip" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">
      <strong>{{ tip.seat.seat_no }} · {{ stateLabels[seatState(tip.seat, selectedId)] }}</strong>
      <span>{{ seatType(tip.seat) }} · {{ zoneName[tip.seat.zone] }}</span>
      <span v-if="tip.seat.near_window">靠窗座位</span>
      <span>全部空闲时间：{{ tip.seat.all_windows?.map(window => `${window.available_start}–${window.available_end}`).join('、') || '无' }}</span>
    </div>
  </div>
</template>

<style scoped>
.scene-host { position: absolute; inset: 0; overflow: hidden; touch-action: none; }
.scene-host :deep(canvas) { display: block; width: 100%; height: 100%; }
.model-tip { position: absolute; z-index: 2; display: grid; gap: 5px; max-width: 220px; padding: 12px 15px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); color: var(--text-1); box-shadow: var(--shadow-3); pointer-events: none; font-size: 12px; }
.model-tip span { color: var(--text-3); }
</style>
