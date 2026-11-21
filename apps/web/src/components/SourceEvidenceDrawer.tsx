import type {ReactNode} from 'react'
import {Dialog} from '@base-ui/react/dialog'
import {X} from 'lucide-react'

/**
 * V179 presentation-only, controlled source-evidence drawer.
 * Base UI owns focus trapping, Escape, backdrop and focus restoration.
 * Callers pass only data from their existing tenant-scoped queries; this
 * component performs no fetches, mutations or authorization.
 */
export default function SourceEvidenceDrawer({open,title,description,onClose,children}:{
 open:boolean;title:string;description:string;onClose:()=>void;children:ReactNode
}){
 return <Dialog.Root open={open} onOpenChange={next=>{if(!next)onClose()}}>
  <Dialog.Portal>
   <Dialog.Backdrop className="v179-evidence-backdrop"/>
   <Dialog.Popup className="v179-evidence-drawer">
    <div className="v179-evidence-head">
     <div>
      <span className="v179-evidence-kicker">MERCHIVRA / AUDIT EVIDENCE</span>
      <Dialog.Title className="v179-evidence-title">{title}</Dialog.Title>
      <Dialog.Description className="v179-evidence-description">{description}</Dialog.Description>
     </div>
     <Dialog.Close className="v179-evidence-close" aria-label="关闭证据详情">
      <X size={20} aria-hidden="true"/>
     </Dialog.Close>
    </div>
    <div className="v179-evidence-body">{children}</div>
    <div className="v179-evidence-foot">
     <span>仅展示当前授权范围已读取的来源事实。查看详情不会改变任何业务或财务状态。</span>
     <Dialog.Close className="refresh-button" type="button">关闭详情</Dialog.Close>
    </div>
   </Dialog.Popup>
  </Dialog.Portal>
 </Dialog.Root>
}
