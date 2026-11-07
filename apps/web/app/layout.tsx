import type {Metadata,Viewport} from 'next'
import type {ReactNode} from 'react'
import '../src/style.css'
import './design-system.css'

export const metadata:Metadata={
  title:'Merchivra ERP · 运营工作台',
  description:'Merchivra 自托管 Amazon Seller ERP。租户会话保护的订单、采购、FBA、异常与财务工作台。'
}
export const viewport:Viewport={
  width:'device-width',initialScale:1
}
export default function RootLayout({children}:{children:ReactNode}){
  return <html lang="zh-CN"><body>{children}</body></html>
}
