// Synthetic-only Chromium CI: accept the loopback endpoint Chrome announces.
// Some Linux runners log the CDP URL but never persist DevToolsActivePort.
export function portFromChromeStderr(log) {
  if(typeof log!=='string')return null
  const m=log.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\/devtools\/browser\//)
  const port=m?Number(m[1]):0
  return Number.isInteger(port)&&port>0&&port<=65535?port:null
}
