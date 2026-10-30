import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { SYSTEM_NAME } from '../config/branding'

// 应用路由表(页面随迭代逐步补充)
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { guest: true }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      {path:'/room-reviews',name:'room-reviews',component:()=>import('../views/RoomReviewsView.vue'),meta:{title:'空间评价',requiresAuth:true,allowedRoles:['student','staff','admin']}},
      {
        path: '',
        name: 'home',
        component: () => import('../views/HomeView.vue'),
        meta: { title: '首页', allowedRoles: ['student', 'staff', 'admin'] }
      },
      {
        path: '/booking',
        name: 'booking',
        component: () => import('../views/BookingView.vue'),
        meta: { title: '座位预约', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/mine',
        name: 'mine',
        component: () => import('../views/MyReservationsView.vue'),
        meta: { title: '我的预约', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/mine/:id/checkin',
        name: 'checkin',
        component: () => import('../views/CheckinView.vue'),
        props: true,
        meta: { title: '扫码签到', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/complaints',
        name: 'complaints',
        component: () => import('../views/ComplaintsView.vue'),
        meta: { title: '求助与投诉', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/complaints/:id',
        name: 'complaint-detail',
        component: () => import('../views/ComplaintDetailView.vue'),
        props: true,
        meta: { title: '求助与投诉详情', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path:'/appeals/new',name:'appeal-create',component:()=>import('../views/AppealCreateView.vue'),
        meta:{title:'本人违规申诉',requiresAuth:true,allowedRoles:['student','staff']}
      },
      {
        path:'/appeals/:id',name:'appeal-detail',component:()=>import('../views/AppealDetailView.vue'),props:true,
        meta:{title:'本人申诉详情',requiresAuth:true,allowedRoles:['student','staff']}
      },
      {
        path: '/waitlist',
        name: 'waitlist',
        component: () => import('../views/WaitlistView.vue'),
        meta: { title: '历史候补', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/analytics',
        name: 'analytics',
        component: () => import('../views/AnalyticsView.vue'),
        meta: { title: '热力图与统计', requiresAuth: true, allowedRoles: ['admin'] }
      },
      {
        path: '/notifications',
        name: 'notifications',
        component: () => import('../views/NotificationsView.vue'),
        meta: { title: '消息中心', requiresAuth: true, allowedRoles: ['student', 'staff'] }
      },
      {
        path: '/profile',
        name: 'profile',
        component: () => import('../views/ProfileView.vue'),
        meta: { title: '个人中心', requiresAuth: true, allowedRoles: ['student', 'staff', 'admin'] }
      },
      {
        path: '/admin',
        name: 'admin',
        component: () => import('../views/admin/AdminView.vue'),
        meta: { title: '管理端', requiresAuth: true, allowedRoles: ['admin'] }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫: 登录校验与角色校验
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth !== false && !auth.isLoggedIn && to.name !== 'login') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (auth.isLoggedIn && !auth.isAdmin && !auth.isOrdinary) {
    auth.logout()
    return { name: 'login' }
  }
  if (to.meta.guest && auth.isLoggedIn) {
    return auth.roleHomePath
  }
  if (to.meta.allowedRoles && !to.meta.allowedRoles.includes(auth.user?.role)) {
    return auth.roleHomePath
  }
  document.title = to.meta.title
    ? `${to.meta.title} - ${SYSTEM_NAME}`
    : SYSTEM_NAME
})

export default router
