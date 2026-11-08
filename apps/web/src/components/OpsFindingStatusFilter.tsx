import {Select} from '@base-ui/react/select'
import {Check,ChevronDown} from 'lucide-react'
import type {FindingFilter} from '../lib/operations'

const options=[
 {value:'ACTIVE',label:'所有活跃（含暂缓）'},
 {value:'ACTIONABLE',label:'当前需要处理（不含暂缓）'},
 {value:'ALL',label:'全部状态'}
] as const satisfies ReadonlyArray<{value:FindingFilter;label:string}>

export default function OpsFindingStatusFilter({value,onChange}:{
 value:FindingFilter
 onChange:(next:FindingFilter)=>void
}){
 return <Select.Root items={options} value={value}
  onValueChange={next=>{
   if(next==='ACTIVE'||next==='ACTIONABLE'||next==='ALL')
    onChange(next)
  }}>
  <Select.Label className="v105-filter-label">查看范围</Select.Label>
  <Select.Trigger className="v105-filter-trigger"
   aria-label="筛选运营待办状态">
   <Select.Value/>
   <Select.Icon><ChevronDown size={16} aria-hidden="true"/></Select.Icon>
  </Select.Trigger>
  <Select.Portal>
   <Select.Positioner className="v105-filter-positioner"
    sideOffset={6} alignItemWithTrigger={false}>
    <Select.Popup className="v105-filter-popup">
     <Select.List className="v105-filter-list">
      {options.map(option=>
       <Select.Item key={option.value} value={option.value}
        className="v105-filter-item" data-filter={option.value}>
        <Select.ItemText>{option.label}</Select.ItemText>
        <Select.ItemIndicator className="v105-filter-check">
         <Check size={16} aria-hidden="true"/>
        </Select.ItemIndicator>
       </Select.Item>)}
     </Select.List>
    </Select.Popup>
   </Select.Positioner>
  </Select.Portal>
 </Select.Root>
}
