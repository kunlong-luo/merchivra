/** V82: curated editable draft text; selecting a template never posts a mutation.
 * Every template explicitly asks the human to review evidence and append facts.
 */
export const exceptionNoteTemplates=[
 {id:'source',label:'来源证据重新核对',
  text:'人工重新核对采购单、ASN 与 FBA 本地证据；尚需补充实际核对记录及来源差异。'},
 {id:'assignment',label:'负责人分派依据',
  text:'根据当前有效 Owner 的处理范围分派本地异常；请补充责任人沟通及下一步计划。'},
 {id:'ack',label:'人工已知悉与待补证据',
  text:'已人工阅读当前异常与来源指纹，记录需要补充的本地证据和下一次跟进安排。'},
 {id:'resolved',label:'本地处理完成依据',
  text:'经人工核对当前本地证据及独立审核要求，拟将处理单标记本地完成；请补充实际处置结论。'},
 {id:'due',label:'人工期限调整',
  text:'因本地证据与实际处理安排需要，人工调整跟进期限；请补充负责人与调整原因。'}
] as const
export function templateText(id:string):string{
 return exceptionNoteTemplates.find(x=>x.id===id)?.text??''
}
