import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// Public, synthetic GitHub Pages only. No authenticated SPA or API proxy.
export default defineConfig({
  base:'/merchivra/',
  plugins:[react()],
})
