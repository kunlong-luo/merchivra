<script setup lang="ts">
import { onMounted, ref } from 'vue'

const apiStatus = ref('检测中…')

onMounted(async () => {
  try {
    const response = await fetch('/api/v1/ping')
    if (!response.ok) throw new Error('API unavailable')
    const data: { service: string; status: string } = await response.json()
    apiStatus.value = data.status === 'ok' ? 'API 已连接' : 'API 异常'
  } catch {
    apiStatus.value = 'API 暂时不可用'
  }
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
      <div class="status">
        <span class="dot"></span>{{ apiStatus }}
      </div>
      <div class="notice">当前为工程初始化版本，尚无登录鉴权和真实 Amazon 数据同步。请勿公网部署。</div>
      <a href="https://github.com/kunlong-luo/merchivra" target="_blank" rel="noopener noreferrer">查看 GitHub 仓库 ↗</a>
    </section>
  </main>
</template>
