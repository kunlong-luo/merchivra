'use client'
import {useState} from 'react'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import Root from '../src/Root'
import type {View} from '../src/App'

/**
 * A real Next.js Client Component boundary around the existing operational ERP.
 * Business requests, permissions, stock and finance validations remain in the
 * established Spring Boot services. No synthetic datasets enter this path.
 */
export default function Portal({initialView}:{initialView:View}){
 const [client]=useState(()=>new QueryClient({defaultOptions:{
  queries:{retry:1,refetchOnWindowFocus:false}
 }}))
 return <div className="next-workbench">
  <QueryClientProvider client={client}>
   <Root initialView={initialView} routeNavigation/>
  </QueryClientProvider>
 </div>
}
