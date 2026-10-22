<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { clearSession, fetchProfile, getToken, login, logout, setToken } from './api'
import { ChatSocket } from './ws'

const account = ref('admin')
const password = ref('123456')
const loading = ref(false)
const error = ref('')

const profile = ref(null)
const peer = ref({ id: 2, name: '小红' })
const messages = ref([])
const draft = ref('')
const status = ref('idle')
const listEl = ref(null)

let socket = null

const statusText = {
  idle: '未连接',
  connecting: '连接中…',
  connected: '已连接',
  closed: '已断开',
  error: '连接失败'
}

function resolvePeer(me) {
  // 本地演示：库里只有两个账号，互相为对端
  if (me.id === 1) return { id: 2, name: '小红' }
  return { id: 1, name: '管理员' }
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight
    }
  })
}

function handleFrame(frame) {
  if (frame.type === 'MSG') {
    messages.value.push({
      key: `peer-${frame.seq}`,
      mine: false,
      content: frame.content,
      seq: frame.seq,
      status: 'received'
    })
    scrollToBottom()
    return
  }
  if (frame.type === 'ACK') {
    const target = messages.value.find((m) => m.key === frame.cid)
    if (target) {
      target.seq = frame.seq
      target.status = 'sent'
    }
  }
}

function connect() {
  if (socket) socket.close()
  socket = new ChatSocket({
    token: getToken(),
    onFrame: handleFrame,
    onStatus: (s) => {
      status.value = s
    }
  })
  socket.connect()
}

async function loadProfile() {
  try {
    profile.value = await fetchProfile()
    peer.value = resolvePeer(profile.value)
    connect()
  } catch (e) {
    error.value = e.message
    clearSession()
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
    messages.value = []
    await loadProfile()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function handleSend() {
  const text = draft.value.trim()
  if (!text) return
  if (status.value !== 'connected') {
    error.value = '连接未建立，稍后再试'
    return
  }
  const cid = socket.sendMessage(peer.value.id, text)
  if (!cid) {
    error.value = '发送失败，连接已断开'
    return
  }
  messages.value.push({ key: cid, mine: true, content: text, seq: null, status: 'sending' })
  draft.value = ''
  error.value = ''
  scrollToBottom()
}

async function handleLogout() {
  try {
    await logout()
  } catch (e) {
    // 精简版 logout 是空实现，本地清理才是关键
  }
  if (socket) socket.close()
  clearSession()
  profile.value = null
  messages.value = []
  status.value = 'idle'
}

onMounted(() => {
  if (getToken()) {
    loadProfile()
  }
})

onBeforeUnmount(() => {
  if (socket) socket.close()
})
</script>

<template>
  <div class="page">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

    <!-- 未登录 -->
    <main v-if="!profile" class="card">
      <header class="brand">
        <div class="logo">IM</div>
        <div>
          <h1>微服务通讯平台</h1>
          <p>Spring Cloud Alibaba + Dubbo + Netty + MQ</p>
        </div>
      </header>

      <form class="form" @submit.prevent="handleLogin">
        <label class="field">
          <span>账号</span>
          <input v-model="account" type="text" autocomplete="username" placeholder="admin 或 xiaohong" />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" placeholder="123456" />
        </label>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="primary" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
        <p class="hint">开两个标签页分别登录 admin 与 xiaohong，即可互相聊天</p>
      </form>
    </main>

    <!-- 已登录：聊天 -->
    <main v-else class="chat">
      <aside class="contacts">
        <div class="me">
          <div class="avatar">{{ (profile.nickname || profile.account).charAt(0) }}</div>
          <div class="me-info">
            <strong>{{ profile.nickname || profile.account }}</strong>
            <span>uid {{ profile.id }}</span>
          </div>
        </div>

        <div class="contact active">
          <div class="avatar small">{{ peer.name.charAt(0) }}</div>
          <div class="contact-info">
            <strong>{{ peer.name }}</strong>
            <span>uid {{ peer.id }}</span>
          </div>
          <i class="dot" :class="status"></i>
        </div>

        <button class="ghost logout" type="button" @click="handleLogout">退出登录</button>
      </aside>

      <section class="conversation">
        <header class="conv-header">
          <div>
            <strong>{{ peer.name }}</strong>
            <span class="status-text">{{ statusText[status] }}</span>
          </div>
        </header>

        <div ref="listEl" class="messages">
          <p v-if="messages.length === 0" class="empty">还没有消息，说点什么吧</p>

          <div v-for="msg in messages" :key="msg.key" class="row" :class="msg.mine ? 'mine' : 'peer'">
            <div class="bubble">{{ msg.content }}</div>
            <span class="meta">
              <template v-if="msg.mine">
                <span v-if="msg.status === 'sending'">发送中…</span>
                <span v-else>seq {{ msg.seq }}</span>
              </template>
              <template v-else>seq {{ msg.seq }}</template>
            </span>
          </div>
        </div>

        <footer class="composer">
          <p v-if="error" class="error">{{ error }}</p>
          <div class="composer-row">
            <input
              v-model="draft"
              type="text"
              placeholder="输入消息，回车发送"
              @keyup.enter="handleSend"
            />
            <button class="primary" type="button" @click="handleSend">发送</button>
          </div>
        </footer>
      </section>
    </main>
  </div>
</template>
