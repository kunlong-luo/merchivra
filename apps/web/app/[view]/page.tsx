import {notFound} from 'next/navigation'
import Portal from '../portal'
import type {View} from '../../src/App'

const supported=new Set<string>([
 'core','ops','team','connections','purchasing','finance',
 'overview','orders','catalog','inventory','sync'
])
export default async function BusinessModule({
 params
}:{params:Promise<{view:string}>}){
 const {view}=await params
 if(!supported.has(view))notFound()
 return <Portal initialView={view as View}/>
}
