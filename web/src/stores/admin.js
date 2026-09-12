import { defineStore } from 'pinia'
import { adminLogin, adminLogout, adminMe } from '@/api/auth'
import { tokenStore } from '@/api/http'

/**
 * 后台管理员状态（管理端）
 * Token 单独存放于 culture_admin_token，权限只认后台 Token。
 */
export const useAdminStore = defineStore('admin', {
  state: () => ({
    token: tokenStore.get('admin'),
    account: tokenStore.getAccount('admin'),
    profile: null,
    admin: false
  }),
  getters: {
    isLogin: state => !!state.token
  },
  actions: {
    async login(account, password) {
      const data = await adminLogin({ account, password })
      this.token = data.token
      this.account = data.email || account
      this.admin = !!data.admin
      return data
    },
    async fetchProfile() {
      if (!this.token) return null
      const data = await adminMe()
      this.profile = data
      this.admin = !!data.admin
      return data
    },
    async logout() {
      await adminLogout()
      this.token = ''
      this.account = ''
      this.profile = null
      this.admin = false
    }
  }
})
