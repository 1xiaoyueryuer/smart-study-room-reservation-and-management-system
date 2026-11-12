import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './auth'

// 头像后端接口未接入前，按用户保存在本机浏览器；导航和正文共用同一份状态。
export const useAvatarStore = defineStore('avatar', () => {
  const auth = useAuthStore()
  const data = ref('')
  const key = computed(() => auth.user?.id ? `studyroom-avatar:${auth.user.id}` : '')

  function load() {
    data.value = key.value && typeof localStorage !== 'undefined'
      ? (localStorage.getItem(key.value) || '')
      : ''
  }

  function setAvatar(value) {
    data.value = value
    if (key.value && typeof localStorage !== 'undefined') localStorage.setItem(key.value, value)
  }

  function reset() {
    data.value = ''
    if (key.value && typeof localStorage !== 'undefined') localStorage.removeItem(key.value)
  }

  return { data, key, load, setAvatar, reset }
})
