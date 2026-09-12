import { defineStore } from 'pinia'
import { frontLogin, frontLogout, frontMe } from '@/api/auth'
import { tokenStore } from '@/api/http'

/**
 * 前台用户状态（用户端）
 * Token 单独存放于 culture_front_token，与后台管理端完全隔离。
 */
export const useFrontUserStore = defineStore('frontUser', {
  state: () => ({
    token: tokenStore.get('front'),
    account: tokenStore.getAccount('front'),
    profile: null
  }),
  getters: {
    isLogin: state => !!state.token
  },
  actions: {
    async login(account, password) {
      const data = await frontLogin({ account, password })
      this.token = data.token
      this.account = data.email || account
      return data
    },
    async fetchProfile() {
      if (!this.token) return null
      try {
        this.profile = await frontMe()
      } catch (e) {
        this.profile = null
      }
      return this.profile
    },
    async logout() {
      await frontLogout()
      this.token = ''
      this.account = ''
      this.profile = null
    }
  }
})
