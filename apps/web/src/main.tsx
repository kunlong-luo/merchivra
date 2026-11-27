import React from 'react'
import {createRoot} from 'react-dom/client'
import PagesDemo from './PagesDemo'
import './style.css'
import './visual-refresh.css'

// Static public showcase only. Authenticated ERP is mounted exclusively by Next App Router.
const element=document.getElementById('app')
if(!element)throw new Error('Missing React root')
createRoot(element).render(<React.StrictMode><PagesDemo/></React.StrictMode>)
