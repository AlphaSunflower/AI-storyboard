import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import gsap from 'gsap'
import { useGSAP } from '@gsap/react'
import './index.css'
import './styles/global.css'
import App from './App.tsx'

// GSAP React 插件全局注册（useGSAP 自动清理动画，避免 unmount 泄漏）
gsap.registerPlugin(useGSAP)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
