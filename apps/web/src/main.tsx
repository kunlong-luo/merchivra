import React from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Root from './Root'
import './style.css'
const element=document.getElementById('app')
if(!element)throw new Error('Missing React root')
const client=new QueryClient({defaultOptions:{queries:{retry:1,refetchOnWindowFocus:false}}})
createRoot(element).render(<React.StrictMode><QueryClientProvider client={client}><Root/></QueryClientProvider></React.StrictMode>)
