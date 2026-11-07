import type {NextConfig} from 'next'
import path from 'node:path'

/**
 * V99 migration preview. The real browser Session/CSRF remains backed by
 * Spring Boot on the SAME ORIGIN via a server-side reverse proxy.
 * The NEXT_PUBLIC bundle contains no server URL or Amazon credentials.
 *
 * Do not enable real OAuth/SP-API while the proxy's OAuth callback access
 * logging and HTTPS trust/forwarded headers are awaiting review.
 */
const backend=(process.env.MERCHIVRA_API_INTERNAL_URL??
  'http://127.0.0.1:8080').replace(/\/$/,'')
const config:NextConfig={
  reactStrictMode:true,
  output:'standalone',
  outputFileTracingRoot:path.resolve(process.cwd(),'../..'),
  typescript:{tsconfigPath:'tsconfig.next.json'},
  async rewrites(){
    return [{source:'/api/:path*',destination:backend+'/api/:path*'}]
  }
}
export default config
