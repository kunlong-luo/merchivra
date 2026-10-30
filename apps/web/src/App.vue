<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { fetchPing } from './lib/api'

const { data, isPending, isError } = useQuery({
  queryKey: ['api', 'ping'],
  queryFn: fetchPing,
  staleTime: 30_000,
  retry: 1
})

const apiStatus = computed(() => {
  if (isPending.value) return '检测中…'
  if (isError.value) return 'API 暂时不可用'
  return data.value?.status === 'ok' ? 'API 已连接' : 'API 响应异常'
})
</script>

<template>
  <main class="page">
    <header>
      <span class="brand">◉ Merchivra</span>
      <span class="stage">M0 · Foundation</span>
    </header>
    <section class="hero">
      <span class="eyebrow">Open Source Commerce Operations</span>
      <h1>Merchivra ERP</h1>
      <p>从亚马逊 Seller 店铺开始，建立可扩展、可自托管、可追溯的跨境电商运营平台。</p>
      <div class="status" role="status" aria-live="polite">
        <span class="dot" aria-hidden="true"></span>{{ apiStatus }}
      </div>
      <div class="notice">当前为工程初始化版本，尚无登录鉴权和真实 Amazon 数据同步。请勿公网部署。</div>
      <a href="https://github.com/kunlong-luo/merchivra" target="_blank" rel="noopener noreferrer">查看 GitHub 仓库 ↗</a>
    </section>
  </main>
</template>
