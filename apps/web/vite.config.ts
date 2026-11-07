import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// Static GitHub Pages publish uses repo-relative asset paths. Normal local
// Nginx/Compose builds keep '/' and the same-origin /api proxy unchanged.
export default defineConfig({
  base: process.env.VITE_PUBLIC_DEMO==='true'?'/merchivra/':'/',
  plugins: [react()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } }
})
