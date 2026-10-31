<script setup lang="ts">
import { ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { listTeamMembers, listTeamInvites, createTeamInvite,
  revokeTeamInvite, setTeamReviewer, revokeTeamOwner } from './lib/team'
import type { IssuedInvite } from './lib/team'

const props=defineProps<{ tenantId:string; currentUserId:string }>()
const client=useQueryClient()
const login=ref('')
const result=ref<IssuedInvite|null>(null)
const message=ref('')
const error=ref('')
const members=useQuery({
  queryKey:['team',props.tenantId,'members'],queryFn:listTeamMembers,
  retry:false,staleTime:10000
})
const invites=useQuery({
  queryKey:['team',props.tenantId,'invites'],queryFn:listTeamInvites,
  retry:false,staleTime:10000
})
function refresh() {
  void client.invalidateQueries({queryKey:['team',props.tenantId]})
}
const issuing=useMutation({
  mutationFn:()=>createTeamInvite(login.value),
  onSuccess:issued=>{
    result.value=issued;login.value='';message.value='邀请已生成。请通过可信渠道单独交给受邀者。'
    error.value='';refresh()
  },
  onError:(e:Error)=>{error.value=e.message}
})
const action=useMutation({
  mutationFn:async(args:{kind:'reviewer'|'owner'|'invite';id:string;enabled?:boolean})=>{
    if(args.kind==='reviewer') return setTeamReviewer(args.id,Boolean(args.enabled))
    if(args.kind==='owner') return revokeTeamOwner(args.id)
    return revokeTeamInvite(args.id)
  },
  onSuccess:()=>{message.value='变更已提交并写入审计日志';error.value='';refresh()},
  onError:(e:Error)=>{error.value=e.message}
})
async function copyToken() {
  if(!result.value)return
  try {
    await navigator.clipboard.writeText(result.value.token)
    message.value='邀请令牌已复制。发送后请清除剪贴板。'
  } catch {
    error.value='浏览器无法使用剪贴板，请手动复制令牌'
  }
}
function removeOwner(id:string) {
  if(!window.confirm('确认撤销此管理员对当前租户的访问权限？此操作会使其会话立即失效。'))return
  action.mutate({kind:'owner',id})
}
</script>

<template>
  <div class="team-workspace">
    <section class="panel full-panel">
      <div class="panel-heading">
        <div><h2>管理员与安全审核员</h2>
          <p>当前只支持多位 OWNER 管理员。安全审核员需由另一位管理员显式授权。</p></div>
        <button type="button" class="refresh-button" @click="refresh()">刷新</button>
      </div>
      <div v-if="members.isPending.value" class="empty-state">读取成员…</div>
      <p v-else-if="members.isError.value" class="setup-hint" role="alert">
        {{ members.error.value instanceof Error ? members.error.value.message : '无法读取团队' }}
      </p>
      <div v-else class="table-scroll" role="region" tabindex="0" aria-label="团队成员与角色">
        <table>
          <thead><tr><th>登录名</th><th>成员状态</th><th>Orders 角色审核权限</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="member in members.data.value ?? []" :key="member.userId">
              <td class="strong-cell">{{ member.loginName }} <span v-if="member.userId===currentUserId" class="secondary-cell">（当前账号）</span></td>
              <td><span class="status-chip" :class="member.status==='ACTIVE'?'chip-green':'chip-muted'">{{ member.status==='ACTIVE'?'有效':'已撤销' }}</span></td>
              <td><span class="status-chip" :class="member.reviewer?'chip-green':'chip-muted'">{{ member.reviewer?'已授权':'未授权' }}</span></td>
              <td>
                <div class="sandbox-actions">
                  <button class="refresh-button" type="button" :disabled="action.isPending.value || member.status!=='ACTIVE' || (member.userId===currentUserId && !member.reviewer)"
                    @click="action.mutate({kind:'reviewer',id:member.userId,enabled:!member.reviewer})">{{ member.reviewer?'撤销审核员':'授予审核员' }}</button>
                  <button class="text-button" type="button" :disabled="action.isPending.value || member.status!=='ACTIVE' || member.userId===currentUserId"
                    @click="removeOwner(member.userId)">移除管理员</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel full-panel">
      <div class="panel-heading">
        <div><h2>邀请另一位管理员</h2>
          <p>生成 24 小时有效的一次性令牌，由受邀者自行设置密码。邀请不会自动授予安全审核员权限。</p></div>
      </div>
      <form class="team-invite-form" @submit.prevent="issuing.mutate()">
        <label for="invite-login">新管理员登录名</label>
        <div class="sandbox-actions">
          <input id="invite-login" v-model.trim="login" required
            pattern="[A-Za-z0-9._-]{3,64}" minlength="3" maxlength="64"
            autocomplete="off" placeholder="例如 ops-reviewer" />
          <button type="submit" class="primary-button" :disabled="issuing.isPending.value || !login">生成邀请</button>
        </div>
      </form>
      <div v-if="result" class="team-invite-secret" role="status">
        <strong>仅本次显示：一次性邀请令牌</strong>
        <p>让受邀者在登录页选择“使用邀请令牌激活”，粘贴此令牌并设定独立密码。请勿将令牌贴入公开 Issue 或聊天记录。</p>
        <code>{{ result.token }}</code>
        <div class="sandbox-actions">
          <button type="button" class="refresh-button" @click="copyToken()">复制令牌</button>
          <button type="button" class="text-button" @click="result=null">清除显示</button>
        </div>
      </div>
      <p v-if="message" role="status" class="setup-hint">{{ message }}</p>
      <p v-if="error" role="alert" class="setup-error">{{ error }}</p>
      <div class="table-scroll" role="region" tabindex="0" aria-label="最近的管理员邀请">
        <table><thead><tr><th>登录名</th><th>状态</th><th>失效时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="invite in invites.data.value ?? []" :key="invite.id">
            <td>{{ invite.loginName }}</td><td>{{ invite.status }}</td>
            <td>{{ new Date(invite.expiresAt).toLocaleString('zh-CN') }}</td>
            <td><button type="button" class="text-button"
              :disabled="invite.status!=='PENDING' || action.isPending.value"
              @click="action.mutate({kind:'invite',id:invite.id})">撤销邀请</button></td>
          </tr></tbody>
        </table>
      </div>
      <p class="setup-hint">权限变更将立即影响后续服务端校验。当前不提供普通员工访问，避免未配置细粒度权限时过度授权。</p>
    </section>
  </div>
</template>
