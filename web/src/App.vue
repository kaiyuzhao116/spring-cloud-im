<script setup>
import { onMounted, ref } from 'vue'
import { clearSession, fetchProfile, getToken, login, logout, setToken } from './api'

const account = ref('admin')
const password = ref('123456')
const loading = ref(false)
const error = ref('')
const profile = ref(null)
const token = ref(getToken())
const showToken = ref(false)

function formatTime(epochMillis) {
  if (!epochMillis) return '-'
  return new Date(epochMillis).toLocaleString('zh-CN')
}

async function loadProfile() {
  try {
    profile.value = await fetchProfile()
  } catch (e) {
    error.value = e.message
    clearSession()
    token.value = ''
  }
}

async function handleLogin() {
  error.value = ''
  if (!account.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    const data = await login({
      account: account.value.trim(),
      password: password.value,
      deviceType: 'WEB'
    })
    setToken(data.accessToken)
    token.value = data.accessToken
    await loadProfile()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 精简版 logout 是服务端空实现，本地清理才是关键
  }
  clearSession()
  token.value = ''
  profile.value = null
  showToken.value = false
}

onMounted(() => {
  if (token.value) {
    loadProfile()
  }
})
</script>

<template>
  <div class="page">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

    <main class="card">
      <header class="brand">
        <div class="logo">IM</div>
        <div>
          <h1>微服务通讯平台</h1>
          <p>Spring Cloud Alibaba + Dubbo + JWT</p>
        </div>
      </header>

      <template v-if="!profile">
        <form class="form" @submit.prevent="handleLogin">
          <label class="field">
            <span>账号</span>
            <input v-model="account" type="text" autocomplete="username" placeholder="请输入账号"/>
          </label>
          <label class="field">
            <span>密码</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码"/>
          </label>

          <p v-if="error" class="error">{{ error }}</p>

          <button class="primary" type="submit" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
          <p class="hint">测试账号 admin / 123456，登录请求经 api-gateway 转发到 auth</p>
        </form>
      </template>

      <template v-else>
        <section class="profile">
          <div class="avatar">{{ (profile.nickname || profile.account).charAt(0) }}</div>
          <div class="profile-main">
            <h2>{{ profile.nickname || profile.account }}</h2>
            <p class="muted">@{{ profile.account }} · 用户 ID {{ profile.id }}</p>
          </div>
          <span class="badge" :class="profile.status === 1 ? 'ok' : 'off'">
            {{ profile.status === 1 ? '正常' : '已禁用' }}
          </span>
        </section>

        <dl class="info">
          <div>
            <dt>注册时间</dt>
            <dd>{{ formatTime(profile.createdAt) }}</dd>
          </div>
          <div>
            <dt>token 有效期</dt>
            <dd>30 分钟（精简版：sub / iat / exp）</dd>
          </div>
        </dl>

        <section class="token-box">
          <button class="toggle" type="button" @click="showToken = !showToken">
            {{ showToken ? '收起 accessToken' : '查看 accessToken' }}
          </button>
          <pre v-if="showToken" class="token">{{ token }}</pre>
        </section>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="ghost" type="button" @click="handleLogout">退出登录</button>
      </template>
    </main>
  </div>
</template>
