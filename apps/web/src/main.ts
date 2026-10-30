import { createApp } from 'vue'
import { VueQueryPlugin } from '@tanstack/vue-query'
import Root from './Root.vue'
import './style.css'

const app = createApp(Root)
app.use(VueQueryPlugin)
app.mount('#app')
