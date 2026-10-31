import {describe,expect,it} from 'vitest'
import {createElement} from 'react'
import {renderToString} from 'react-dom/server'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import Root from '../Root'

describe('React-only application bootstrap',()=>{
  it('renders the session-safe React loading shell',()=>{
    const client=new QueryClient({defaultOptions:{queries:{retry:false}}})
    const html=renderToString(createElement(QueryClientProvider,{client},createElement(Root)))
    expect(html).toContain('Merchivra')
    expect(html).toContain('正在校验登录会话')
    expect(html).toContain('auth-shell')
    expect(html).not.toContain('data-v-')
  })
})
