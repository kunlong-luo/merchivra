import {notFound} from 'next/navigation'
import {isWorkbenchView} from '../../src/lib/workbench-routes'

export default async function BusinessModule({
 params
}:{params:Promise<{view:string}>}){
 const {view}=await params
 if(!isWorkbenchView(view))notFound()
 // Validated route marker; never mount another session or QueryClient here.
 return null
}
