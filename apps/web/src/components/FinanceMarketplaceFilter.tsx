import {Select} from '@base-ui/react/select'
import {Check,ChevronDown} from 'lucide-react'

type MarketplaceOption={
 id:string;marketplaceId:string;region:string;currency:string
}

/**
 * Select only from marketplaces returned by the scoped Spring Session API.
 * UI selection is not authorization: all finance reads/writes are still
 * independently guarded on the backend.
 */
export default function FinanceMarketplaceFilter({options,value,onChange}:{
 options:MarketplaceOption[]
 value:string
 onChange:(marketplaceId:string)=>void
}){
 const items=options.map(m=>({
  value:m.id,label:`${m.marketplaceId} · ${m.region} · ${m.currency}`
 }))
 return <div className="v106-finance-filter">
  <Select.Root items={items} value={value}
   onValueChange={next=>{
    if(next&&options.some(m=>m.id===next))onChange(next)
   }}>
   <Select.Label className="v105-filter-label">Marketplace（授权站点）</Select.Label>
   <Select.Trigger className="v105-filter-trigger v106-market-trigger"
    aria-label="切换财务 Marketplace">
    <Select.Value/>
    <Select.Icon><ChevronDown size={16} aria-hidden="true"/></Select.Icon>
   </Select.Trigger>
   <Select.Portal>
    <Select.Positioner className="v105-filter-positioner"
     sideOffset={6} alignItemWithTrigger={false}>
     <Select.Popup className="v105-filter-popup">
      <Select.List className="v105-filter-list">
       {items.map(item=>
        <Select.Item key={item.value} value={item.value}
         className="v105-filter-item" data-marketplace={item.value}>
         <Select.ItemText>{item.label}</Select.ItemText>
         <Select.ItemIndicator className="v105-filter-check">
          <Check size={16} aria-hidden="true"/>
         </Select.ItemIndicator>
        </Select.Item>)}
      </Select.List>
     </Select.Popup>
    </Select.Positioner>
   </Select.Portal>
  </Select.Root>
 </div>
}
