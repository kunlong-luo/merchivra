import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {describe,expect,it} from 'vitest'

// V287: inspect the actual JSX instead of testing an unused key helper.
// Named business panel keys must have distinct stable component namespaces;
// bare marketplace/order/event identifiers are unsafe among siblings.
describe('V287 FinanceWorkspace reconciliation keys',()=>{
 it('uses distinct named namespaces for all explicitly keyed business components',()=>{
  const source=ts.createSourceFile('FinanceWorkspace.tsx',
   readFileSync(new URL('../FinanceWorkspace.tsx',import.meta.url),'utf8'),
   ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX)
  const keys=new Map<string,string>()
  const visit=(node:ts.Node)=>{
   if(ts.isJsxOpeningElement(node)||ts.isJsxSelfClosingElement(node)){
    const name=node.tagName.getText(source)
    if(/^[A-Z]/.test(name)){
     for(const attr of node.attributes.properties){
      if(!ts.isJsxAttribute(attr)||attr.name.getText(source)!=='key')continue
      const expr=attr.initializer
      expect(expr&&ts.isJsxExpression(expr),name).toBe(true)
      if(!expr||!ts.isJsxExpression(expr)||!expr.expression)throw Error(name+' missing key')
      const value=expr.expression
      // Either a literal namespace + scope, or a scope + distinct picker suffix.
      expect(ts.isBinaryExpression(value),name+' must not use a bare entity ID').toBe(true)
      if(!ts.isBinaryExpression(value))throw Error(name+' missing namespace')
      expect(value.operatorToken.kind).toBe(ts.SyntaxKind.PlusToken)
      const prefix=value.getText(source)
      expect(keys.has(prefix),name+' duplicates '+keys.get(prefix)).toBe(false)
      keys.set(prefix,name)
     }
    }
   }
   ts.forEachChild(node,visit)
  }
  visit(source)
  expect([...keys.values()]).toContain('SettlementReportsPanel')
  expect([...keys.values()]).toContain('SettlementWorkspace')
  expect([...keys.values()]).toContain('OrderContributionPanel')
  expect([...keys.values()]).toContain('RefundReviewPanel')
  expect(keys.size).toBeGreaterThanOrEqual(19)
 })
})
