import {describe,expect,it} from 'vitest'
import {WORKBENCH_VIEWS,workbenchPath,viewFromWorkbenchPath,
 isWorkbenchView,coreSectionForView} from './workbench-routes'

describe('V100: route canonicalization and live-data boundaries',()=>{
 it('supports every known Next module with a stable deep link',()=>{
  const seen=new Set(WORKBENCH_VIEWS.map(workbenchPath))
  expect(seen.size).toBe(WORKBENCH_VIEWS.length)
  for(const view of WORKBENCH_VIEWS){
   expect(isWorkbenchView(view)).toBe(true)
   expect(viewFromWorkbenchPath(workbenchPath(view))).toBe(view)
   expect(viewFromWorkbenchPath(workbenchPath(view)+'/')).toBe(view)
  }
 })
 it('treats the root path as real business overview, never a dashboard mock',()=>{
  expect(workbenchPath('overview')).toBe('/')
  expect(viewFromWorkbenchPath('/')).toBe('overview')
  expect(viewFromWorkbenchPath(null)).toBeNull()
 })
 it('rejects nested/unrecognized routes for the server-side 404 boundary',()=>{
  for(const path of ['/finance/secret','/not-found','/api/v1/auth/me',
   '//inventory','/orders/details','/seller-accounts']){
   expect(viewFromWorkbenchPath(path)).toBeNull()
  }
  expect(isWorkbenchView('admin')).toBe(false)
 })
 it('binds Orders, Listing and FBA URLs to true tenant-scoped Core tables',()=>{
  expect(coreSectionForView('orders')).toBe('orders')
  expect(coreSectionForView('catalog')).toBe('listings')
  expect(coreSectionForView('inventory')).toBe('inventory')
  expect(coreSectionForView('finance')).toBeNull()
  expect(coreSectionForView('overview')).toBeNull()
  expect(coreSectionForView('ops')).toBeNull()
 })
})
