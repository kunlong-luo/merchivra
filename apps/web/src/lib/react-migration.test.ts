import {describe,expect,it} from 'vitest'
import {createElement} from 'react'
import {renderToString} from 'react-dom/server'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import Root from '../Root'

describe('React-only application bootstrap',()=>{
  it('renders the session-safe React loading shell',()=>{
    const client=new QueryClient({defaultOptions:{queries:{retry:false}}})
    const html=renderToString(createElement(QueryClientProvider,{client},createElement(Root)))
    expect(html).toContain('MERCHIVRA')
    expect(html).toContain('正在连接工作空间')
    expect(html).toContain('auth-shell')
    expect(html).toContain('业务数据将在会话验证成功后加载')
    expect(html).not.toContain('已登录的工作台')
    expect(html).not.toContain('data-v-')
  })
})
