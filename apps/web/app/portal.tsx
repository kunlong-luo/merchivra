'use client'
import {useCallback,useState} from 'react'
import {usePathname,useRouter} from 'next/navigation'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import Root from '../src/Root'
import type {View} from '../src/App'
import {viewFromWorkbenchPath,workbenchPath} from '../src/lib/workbench-routes'

/**
 * A real Next.js Client Component boundary around the existing operational ERP.
 * Business requests, permissions, stock and finance validations remain in the
 * established Spring Boot services. No synthetic datasets enter this path.
 */
export default function Portal({initialView}:{initialView:View}){
 const router=useRouter()
 const pathname=usePathname()
 const activeRoute=viewFromWorkbenchPath(pathname)??initialView
 const navigate=useCallback((view:View,mode:'push'|'replace'='push')=>{
  const destination=workbenchPath(view)
  if(destination===pathname)return
  if(mode==='replace')router.replace(destination,{scroll:false})
  else router.push(destination,{scroll:false})
 },[router,pathname])
 const [client]=useState(()=>new QueryClient({defaultOptions:{
  queries:{retry:1,refetchOnWindowFocus:false}
 }}))
 return <div className="next-workbench">
  <QueryClientProvider client={client}>
   <Root initialView={activeRoute} routeNavigation onNavigate={navigate}/>
  </QueryClientProvider>
 </div>
}
