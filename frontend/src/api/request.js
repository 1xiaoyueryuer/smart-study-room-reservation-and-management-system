import axios from 'axios'
import { requestError } from './requestError.js'
import { message } from '../components/ui/feedback'
import router from '../router'
import { useAuthStore } from '../stores/auth'

let expiredTokenHandled = ''

// 统一 axios 实例: 注入令牌 / 拆包响应 / 统一错误提示
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (resp) => resp.data,
  async (err) => {
    // responseType=blob也会包装后端JSON错误；统一解包后仍交给requestError。
    const body = err.response?.data
    if (typeof Blob !== 'undefined' && body instanceof Blob && body.size <= 1024 * 1024) {
      try {
        const text = (await body.text()).trim()
        if (text.startsWith('{')) {
          const parsed = JSON.parse(text)
          if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) err.response.data = parsed
        }
      } catch { /* 非JSON、损坏或不可读取的Blob保留原HTTP状态与兼容错误消息。 */ }
    }
    const failure = requestError(err)
    const { status, message: errMsg } = failure
    if (status === 401 && !err.config?.skipAuthHandling) {
      const expiredToken = localStorage.getItem('token') || ''
      if (expiredToken && expiredToken !== expiredTokenHandled) {
        expiredTokenHandled = expiredToken
        useAuthStore().logout()
        message.error('登录已过期，请重新登录')
        router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
      }
    } else if (!err.config?.silent) {
      message.error(errMsg)
    }
    return Promise.reject(failure)
  }
)

export default request
