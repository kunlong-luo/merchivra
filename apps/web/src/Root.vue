<script setup lang="ts">
import { ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import App from './App.vue'
import { fetchViewer, loginSession, logoutSession } from './lib/auth'
import { acceptTeamInvitation } from './lib/team'

const client = useQueryClient()
const username = ref('')
const password = ref('')
const activating = ref(false)
const inviteMode = ref(false)
const inviteToken = ref('')
const invitePassword = ref('')
const activationMessage = ref('')
const errorMessage = ref('')
const authenticating = ref(false)
const signingOut = ref(false)
const { data: viewer, isPending, isError, error, refetch } = useQuery({
  queryKey: ['auth', 'me'],
  queryFn: fetchViewer,
  retry: false,
  staleTime: 0
})

async function signIn() {
  errorMessage.value = ''
  authenticating.value = true
  try {
    await loginSession(username.value, password.value)
    password.value = ''
    // Invalidate any anonymous or previous-user cache before mounting ERP.
    client.removeQueries({ queryKey: ['api', 'demo'] })
    client.removeQueries({ queryKey: ['core'] })
    client.removeQueries({ queryKey: ['setup'] })
    client.removeQueries({ queryKey: ['sandbox'] })
    client.removeQueries({ queryKey: ['operations'] })
    client.removeQueries({ queryKey: ['team'] })
    await refetch()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败'
    password.value = ''
  } finally {
    authenticating.value = false
  }
}

async function activateInvite() {
  errorMessage.value=''
  activating.value=true
  try {
    await acceptTeamInvitation(inviteToken.value,invitePassword.value)
    inviteToken.value=''
    invitePassword.value=''
    activationMessage.value='账号已激活。现在可以使用邀请中的登录名和新密码登录。'
    inviteMode.value=false
  } catch (error) {
    errorMessage.value=error instanceof Error ? error.message : '邀请激活失败'
    invitePassword.value=''
  } finally { activating.value=false }
}

async function signOut() {
  errorMessage.value = ''
  signingOut.value = true
  try {
    await logoutSession()
    client.removeQueries({ queryKey: ['api', 'demo'] })
    client.removeQueries({ queryKey: ['core'] })
    client.removeQueries({ queryKey: ['setup'] })
    client.removeQueries({ queryKey: ['sandbox'] })
    client.removeQueries({ queryKey: ['operations'] })
    await refetch()
  } catch (error) {
    // A failed logout must not be presented as a successful sign-out.
    errorMessage.value = error instanceof Error ? error.message : '退出失败'
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <div v-if="isPending" class="auth-shell">
    <section class="auth-card" aria-live="polite">
      <div class="auth-symbol">M</div>
      <h1>Merchivra</h1>
      <p>正在校验登录会话…</p>
    </section>
  </div>
  <div v-else-if="isError" class="auth-shell">
    <section class="auth-card" role="alert">
      <div class="auth-symbol">M</div>
      <h1>无法验证身份</h1>
      <p>{{ error instanceof Error ? error.message : '请检查数据库与 API 服务。' }}</p>
      <button class="auth-submit" type="button" @click="refetch()">重试</button>
    </section>
  </div>
  <div v-else-if="!viewer" class="auth-shell">
    <form v-if="!inviteMode" class="auth-card" @submit.prevent="signIn">
      <div class="auth-symbol">M</div>
      <div class="auth-kicker">SELF-HOSTED COMMERCE ERP</div>
      <h1>登录 Merchivra</h1>
      <p>管理员由部署环境首次初始化；没有默认密码。当前仅支持本地账号与 Session 登录。</p>
      <label for="auth-username">用户名</label>
      <input id="auth-username" v-model.trim="username" autocomplete="username"
        minlength="3" maxlength="64" required placeholder="输入初始化用户名" />
      <label for="auth-password">密码</label>
      <input id="auth-password" v-model="password" type="password"
        autocomplete="current-password" required placeholder="输入管理员密码" />
      <div v-if="errorMessage" class="auth-error" role="alert">{{ errorMessage }}</div>
      <div v-if="activationMessage" role="status" class="setup-hint">{{ activationMessage }}</div>
      <button class="auth-submit" :disabled="authenticating" type="submit">
        {{ authenticating ? '正在登录…' : '登录工作台' }}
      </button>
      <div class="auth-footnote">首次运行前，在本地 <code>.env</code> 设置
        <code>MERCHIVRA_BOOTSTRAP_USERNAME</code> 和
        <code>MERCHIVRA_BOOTSTRAP_PASSWORD</code>（至少 16 位），然后启动或重启服务。</div>
      <button type="button" class="text-button" @click="inviteMode=true;errorMessage=''">使用邀请令牌激活新管理员</button>
      <a href="https://github.com/kunlong-luo/merchivra/blob/main/docs/security/local-auth.md"
        target="_blank" rel="noopener noreferrer">部署与登录说明</a>
    </form>
    <form v-else class="auth-card" @submit.prevent="activateInvite">
      <div class="auth-symbol">M</div>
      <h1>激活管理员邀请</h1>
      <p>邀请令牌仅使用一次，有效期 24 小时。请从管理员通过可信渠道提供的令牌激活；密码由你自己设置。</p>
      <label for="activation-token">一次性邀请令牌</label>
      <input id="activation-token" v-model.trim="inviteToken" required autocomplete="off" minlength="43" maxlength="43"
        placeholder="粘贴邀请令牌" />
      <label for="activation-password">设置新密码（至少 16 位）</label>
      <input id="activation-password" v-model="invitePassword" type="password" autocomplete="new-password"
        minlength="16" maxlength="128" required placeholder="设置独立登录密码" />
      <p v-if="errorMessage" class="auth-error" role="alert">{{ errorMessage }}</p>
      <button type="submit" class="auth-submit" :disabled="activating">{{ activating?'激活中…':'激活账号' }}</button>
      <button type="button" class="text-button" @click="inviteMode=false;inviteToken='';invitePassword='';errorMessage=''">返回登录</button>
    </form>
  </div>
  <App v-else :viewer="viewer" :signing-out="signingOut"
    @logout="signOut" />
</template>
