import { nextTick, onScopeDispose, ref, watch } from 'vue'

/**
 * 弹层定位：Teleport 到 body 的 fixed 定位 popover
 * 跟随触发元素位置，滚动/缩放时重新计算，向下空间不足时自动翻转到上方。
 */
export function usePopover(triggerRef, popupRef) {
  const open = ref(false)
  const popupStyle = ref({})

  function update() {
    const el = triggerRef.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    // Teleport后的弹层不再继承触发器所在对话框的层叠上下文。
    const dialog = el.closest('[role="dialog"]')
    const dialogLayer = dialog ? Number.parseInt(window.getComputedStyle(dialog).zIndex, 10) : null
    const spaceBelow = window.innerHeight - rect.bottom
    const above = spaceBelow < 300 && rect.top > spaceBelow
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - Math.max(rect.width, 180) - 8))
    popupStyle.value = {
      position: 'fixed',
      ...(dialog ? { zIndex: Number.isFinite(dialogLayer) ? dialogLayer + 1 : 'calc(var(--z-dialog) + 1)' } : {}),
      left: `${Math.round(left)}px`,
      minWidth: `${Math.round(rect.width)}px`,
      ...(above
        ? { bottom: `${Math.round(window.innerHeight - rect.top + 6)}px` }
        : { top: `${Math.round(rect.bottom + 6)}px` })
    }
  }

  function show() {
    open.value = true
    nextTick(update)
  }

  function hide() {
    open.value = false
  }

  function toggle() {
    open.value ? hide() : show()
  }

  function onDocPointerDown(e) {
    if (!open.value) return
    const t = triggerRef.value
    const p = popupRef.value
    if (t?.contains(e.target) || p?.contains(e.target)) return
    hide()
  }

  function onUiReposition() {
    if (open.value) update()
  }

  function cleanup() {
    document.removeEventListener('pointerdown', onDocPointerDown, true)
    window.removeEventListener('scroll', onUiReposition, true)
    window.removeEventListener('resize', onUiReposition)
  }

  onScopeDispose(cleanup)

  watch(open, (value) => {
    if (value) {
      document.addEventListener('pointerdown', onDocPointerDown, true)
      window.addEventListener('scroll', onUiReposition, true)
      window.addEventListener('resize', onUiReposition)
    } else {
      cleanup()
    }
  })

  return { open, popupStyle, show, hide, toggle, update }
}
