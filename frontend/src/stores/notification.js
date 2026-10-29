import { defineStore } from 'pinia'
import { ref } from 'vue'
import { unreadCount } from '../api/notification'
import { useAuthStore } from './auth'

export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  const auth = useAuthStore()

  async function refresh() {
    if (!auth.isLoggedIn || !auth.isOrdinary) {
      unread.value = 0
      return
    }
    try {
      const resp = await unreadCount()
      unread.value = resp.data?.count || 0
    } catch {
      // 忽略轮询或网络失败
    }
  }

  function decrement(count = 1) {
    unread.value = Math.max(0, unread.value - count)
  }

  function clear() {
    unread.value = 0
  }

  return {
    unread,
    refresh,
    decrement,
    clear
  }
})
