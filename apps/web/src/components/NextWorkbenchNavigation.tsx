import {useState} from 'react'
import {Dialog} from '@base-ui/react/dialog'
import {
  AlertTriangle, Boxes, Database, ExternalLink, KeyRound,
  LayoutDashboard, Menu, Package, RefreshCw, ShoppingCart,
  Truck, Users, Wallet, X
} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'
import type {WorkbenchView} from '../lib/workbench-routes'
import {workbenchPath} from '../lib/workbench-routes'

/**
 * V103: Next-only enterprise navigation shell.
 *
 * Base UI owns the mobile modal's focus trap, Escape handling, backdrop and
 * focus return. Browser-native links provide real deep-link semantics.
 * Permissions are still enforced by Spring Boot; this is presentation only.
 */
const modules:{view:WorkbenchView;label:string;Icon:LucideIcon}[]=[
  {view:'overview',label:'经营总览',Icon:LayoutDashboard},
  {view:'core',label:'核心业务数据',Icon:Database},
  {view:'orders',label:'订单管理',Icon:ShoppingCart},
  {view:'catalog',label:'商品与 Listing',Icon:Package},
  {view:'inventory',label:'FBA 库存',Icon:Boxes},
  {view:'purchasing',label:'采购与供应商',Icon:Truck},
  {view:'ops',label:'运营自动化',Icon:AlertTriangle},
  {view:'finance',label:'财务与对账',Icon:Wallet},
  {view:'connections',label:'卖家授权',Icon:KeyRound},
  {view:'team',label:'团队与权限',Icon:Users},
  {view:'sync',label:'模拟同步',Icon:RefreshCw}
]
const groups=[
  {title:'经营与商品',views:['overview','core','orders','catalog','inventory']},
  {title:'供应链与对账',views:['purchasing','ops','finance']},
  {title:'协作与设置',views:['connections','team','sync']}
] as const

type Props={
  active:WorkbenchView;
  isOwner:boolean;
  isDemo:boolean;
  onNavigate:(view:WorkbenchView)=>void;
}

export default function NextWorkbenchNavigation({active,isOwner,isDemo,onNavigate}:Props){
  const [mobileOpen,setMobileOpen]=useState(false)
  const allowed=modules.filter(item=>isOwner||item.view==='core')

  function links(closeAfterNavigate:boolean){
    return <nav className="nav-links" aria-label="主导航">
      {groups.map(group=>{
        const entries=allowed.filter(item=>
          (group.views as readonly string[]).includes(item.view))
        if(!entries.length)return null
        return <div className="nav-group" key={group.title}>
          <div className="nav-group-label">{group.title}</div>
          {entries.map(({view,label,Icon})=><a
            href={workbenchPath(view)} key={view}
            aria-current={active===view?'page':undefined}
            className={`nav-link ${active===view?'active':''}`}
            onClick={event=>{
              // Preserve native link behavior for opening a new browser tab.
              if(event.metaKey||event.ctrlKey||event.shiftKey||event.altKey
                ||event.button!==0)return
              event.preventDefault()
              onNavigate(view)
              if(closeAfterNavigate)setMobileOpen(false)
            }}>
            <Icon className="nav-icon" aria-hidden="true" size={19} strokeWidth={1.8}/>
            <span className="nav-link-label">{label}</span>
          </a>)}
        </div>
      })}
    </nav>
  }

  return <>
    <aside className="sidebar next-ui-sidebar" aria-label="工作空间侧边栏">
      <div className="brand-wrap"><div className="brand-icon">M</div>
        <div className="brand-copy"><strong>merchivra</strong>
          <small>SELLER OPERATIONS</small></div></div>
      {links(false)}
      <div className="workbench-source-note" role="note">
        API 连通不代表 Amazon 已授权；财务与库存保留来源状态。
      </div>
      <div className="sidebar-spacer"/>
      <div className="sidebar-info"><span className="local-indicator"/>
        <div><strong>{isDemo?'本地演示环境':'核心业务工作台'}</strong>
          <small>{isDemo?'纯虚构数据 · 无真实连接':'会话租户隔离 · 数据记录标注来源'}</small></div>
      </div>
      <a className="sidebar-repo" href="https://github.com/kunlong-luo/merchivra"
        target="_blank" rel="noopener noreferrer">
        GitHub 源代码 <ExternalLink size={14} aria-hidden="true"/>
      </a>
    </aside>

    <Dialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
      <Dialog.Trigger className="next-ui-mobile-trigger" aria-label="打开主导航">
        <Menu size={19} strokeWidth={2} aria-hidden="true"/>
        <span>菜单</span>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Backdrop className="next-ui-nav-backdrop"/>
        <Dialog.Popup className="next-ui-nav-sheet">
          <div className="next-ui-nav-sheet-head">
            <div><Dialog.Title>Merchivra 工作空间</Dialog.Title>
              <Dialog.Description>选择要打开的业务模块</Dialog.Description></div>
            <Dialog.Close className="next-ui-nav-close" aria-label="关闭主导航">
              <X size={20} aria-hidden="true"/>
            </Dialog.Close>
          </div>
          {links(true)}
          <div className="next-ui-nav-foot">仅显示当前角色可访问的模块</div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  </>
}
