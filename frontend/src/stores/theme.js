import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const savedMode = typeof localStorage !== 'undefined' ? localStorage.getItem('studyroom-theme-mode') : null
  // 只保留浅色/深色；历史保存的 system 一律迁移为浅色。
  const mode = ref(savedMode === 'dark' ? 'dark' : 'light')
  if (typeof localStorage !== 'undefined' && savedMode !== mode.value) {
    localStorage.setItem('studyroom-theme-mode', mode.value)
  }

  const resolvedTheme = computed(() => mode.value)
  const isDark = computed(() => resolvedTheme.value === 'dark')

  function applyTheme() {
    if (typeof document === 'undefined') return
    const theme = resolvedTheme.value
    document.documentElement.setAttribute('data-theme', theme)
    if (theme === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  function setMode(newMode) {
    if (['light', 'dark'].includes(newMode)) {
      mode.value = newMode
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('studyroom-theme-mode', newMode)
      }
      applyTheme()
    }
  }

  function initTheme() {
    applyTheme()
  }

  return {
    mode,
    resolvedTheme,
    isDark,
    setMode,
    initTheme,
    applyTheme
  }
})
