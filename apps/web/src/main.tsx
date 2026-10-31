import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Root from './Root'
import './style.css'

const root = document.getElementById('app')
if (!root) throw new Error('Missing app mount node')

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <Root />
    </QueryClientProvider>
  </StrictMode>
)
