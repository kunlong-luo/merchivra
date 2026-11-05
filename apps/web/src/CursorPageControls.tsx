/** Lightweight navigation for server-side keyset pages: loaded rows are not a full ledger. */
export default function CursorPageControls({label,page,hasMore,nextCursor,busy,onPrev,onNext}:{
 label:string;page:number;hasMore:boolean;nextCursor:string|null;busy:boolean;
 onPrev:()=>void;onNext:(cursor:string)=>void
}){
 return <div className="sandbox-actions" role="group" aria-label={label+' 翻页'}>
  <button type="button" className="refresh-button" disabled={page<=1||busy}
   onClick={onPrev}>上一页</button>
  <span role="status">第 {page} 页（每页最多 50 条，不代表总数）</span>
  <button type="button" className="refresh-button"
   disabled={!hasMore||!nextCursor||busy}
   onClick={()=>{if(nextCursor)onNext(nextCursor)}}>下一页</button>
 </div>
}
