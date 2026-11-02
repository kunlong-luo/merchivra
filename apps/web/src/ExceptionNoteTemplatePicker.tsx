import {exceptionNoteTemplates,templateText} from './lib/exception-note-templates-v82'

export default function ExceptionNoteTemplatePicker({onApply,label}:{
 onApply:(value:string)=>void;label:string
}){
 return <label className="shop-filter">{label}
  <select aria-label={label} value=""
   onChange={e=>{const note=templateText(e.target.value);if(note)onApply(note)}}>
   <option value="">选择模板并填入可编辑理由</option>
   {exceptionNoteTemplates.map(x=><option key={x.id} value={x.id}>
    {x.label}</option>)}
  </select>
 </label>
}
