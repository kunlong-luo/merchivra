import React from 'react'
import {createRoot} from 'react-dom/client'
import './style.css'

const element=document.getElementById('app')
if(!element)throw new Error('Missing React root')
const root=createRoot(element)

// The Pages build never loads Root or any authenticated ERP module; it has no
// API calls, login, OAuth or writes. The regular Vite build retains Root.
if(import.meta.env.VITE_PUBLIC_DEMO==='true'){
  void import('./PagesDemo').then(({default:PagesDemo})=>{
    root.render(<React.StrictMode><PagesDemo/></React.StrictMode>)
  })
}else{
  void Promise.all([import('./Root'),import('@tanstack/react-query')]).then(
    ([{default:Root},{QueryClient,QueryClientProvider}])=>{
      const client=new QueryClient({defaultOptions:{
        queries:{retry:1,refetchOnWindowFocus:false}
      }})
      root.render(<React.StrictMode>
        <QueryClientProvider client={client}><Root/></QueryClientProvider>
      </React.StrictMode>)
    }
  )
}
