'use client'
import {useCallback,useState} from 'react'
import {usePathname,useRouter} from 'next/navigation'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import Root from '../src/Root'
import type {View} from '../src/App'
import {viewFromWorkbenchPath,workbenchPath} from '../src/lib/workbench-routes'

/**
 * A single persistent Next layout boundary around the existing operational ERP.
 * Cached route segments are marker pages only: they cannot mount independent
 * QueryClients, mirror the active route or retain a separate authenticated root.
 * Business requests, permissions, stock and finance validations remain in the
 * established Spring Boot services. No synthetic datasets enter this path.
 */
export default function Portal(){
 const router=useRouter()
 const pathname=usePathname()
 const activeRoute=viewFromWorkbenchPath(pathname)
 const navigate=useCallback((view:View,mode:'push'|'replace'='push')=>{
  const destination=workbenchPath(view)
  if(destination===pathname)return
  if(mode==='replace')router.replace(destination,{scroll:false})
  else router.push(destination,{scroll:false})
 },[router,pathname])
 const [client]=useState(()=>new QueryClient({defaultOptions:{
  queries:{retry:1,refetchOnWindowFocus:false}
 }}))
 // Unknown routes retain Next's notFound UI rather than a privileged fallback.
 if(activeRoute===null)return null
 return <div className="next-workbench">
  <QueryClientProvider client={client}>
   <Root initialView={activeRoute} routeNavigation onNavigate={navigate}/>
  </QueryClientProvider>
 </div>
}
