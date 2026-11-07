import {notFound} from 'next/navigation'
import Portal from '../portal'
import {isWorkbenchView} from '../../src/lib/workbench-routes'

export default async function BusinessModule({
 params
}:{params:Promise<{view:string}>}){
 const {view}=await params
 if(!isWorkbenchView(view))notFound()
 return <Portal initialView={view}/>
}
