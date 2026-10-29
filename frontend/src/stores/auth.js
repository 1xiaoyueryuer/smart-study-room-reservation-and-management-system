import { defineStore } from 'pinia'

export const ROLE_LABELS = Object.freeze({ student: '学生', staff: '教职工', admin: '管理员' })

// 认证状态: 令牌与用户信息, localStorage 持久化
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'admin',
    isOrdinary: (s) => ['student', 'staff'].includes(s.user?.role),
    roleLabel: (s) => (ROLE_LABELS[s.user?.role] || '未知角色'),
    roleHomePath: (s) => (s.user?.role === 'admin' ? '/admin' : '/')
  },
  actions: {
    setLogin({ token, user }) {
      this.token = token
      this.user = user
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(user))
    },
    setUser(user) {
      this.user = user
      localStorage.setItem('user', JSON.stringify(user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
  }
})
