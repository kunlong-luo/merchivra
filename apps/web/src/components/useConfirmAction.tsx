import {useState} from 'react'
import {AlertDialog} from '@base-ui/react/alert-dialog'
import {TriangleAlert} from 'lucide-react'

export type ConfirmRequest={
  title:string
  description:string
  confirmLabel:string
  onConfirm:()=>void
  danger?:boolean
}

/**
 * Shared V104 confirmation primitive for financially/operationally sensitive
 * local actions. Base UI handles the modal focus trap, accessible names,
 * dismissal and focus restoration. There is intentionally no window.confirm.
 *
 * Call request() only after validation, and capture a complete immutable
 * request body in onConfirm so a delayed approval cannot submit changed data.
 * Server-side tenant, role, expected-stock and CSRF checks remain authoritative.
 */
export function useConfirmAction(){
  const [pending,setPending]=useState<ConfirmRequest|null>(null)
  function request(next:ConfirmRequest){setPending(next)}
  const dialog=<AlertDialog.Root open={pending!==null}
    onOpenChange={open=>{if(!open)setPending(null)}}>
    <AlertDialog.Portal>
      <AlertDialog.Backdrop className="v104-confirm-backdrop"/>
      <AlertDialog.Popup className="v104-confirm-popup">
        <div className="v104-confirm-heading">
          <span className="v104-confirm-symbol" aria-hidden="true">
            <TriangleAlert size={20} strokeWidth={1.9}/>
          </span>
          <div>
            <AlertDialog.Title className="v104-confirm-title">
              {pending?.title??'确认操作'}
            </AlertDialog.Title>
            <AlertDialog.Description className="v104-confirm-description">
              {pending?.description??'请核对操作。'}
            </AlertDialog.Description>
          </div>
        </div>
        <div className="v104-confirm-actions">
          <AlertDialog.Close className="v104-confirm-cancel" type="button">
            返回检查
          </AlertDialog.Close>
          <AlertDialog.Close type="button"
            className={pending?.danger?
              'v104-confirm-approve v104-confirm-danger':'v104-confirm-approve'}
            onClick={()=>{
              const action=pending?.onConfirm
              setPending(null)
              action?.()
            }}>
            {pending?.confirmLabel??'确认'}
          </AlertDialog.Close>
        </div>
      </AlertDialog.Popup>
    </AlertDialog.Portal>
  </AlertDialog.Root>
  return {request,dialog}
}
