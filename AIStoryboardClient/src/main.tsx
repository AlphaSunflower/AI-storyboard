import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import gsap from 'gsap'
import { useGSAP } from '@gsap/react'
import './index.css'
import './styles/global.css'
import App from './App.tsx'
import { installDevLongTaskLogger } from './lib/devLongTaskLogger'
import { initTheme } from './lib/theme'

// 在 React 挂载前先应用外观，避免首屏闪白；账号接口返回后会再次校准。
initTheme()

// GSAP React 插件全局注册（useGSAP 自动清理动画，避免 unmount 泄漏）
gsap.registerPlugin(useGSAP)

// 开发环境挂长任务探针：>200ms 的主线程占用会在 console 打 [perf]，卡顿排查用
if (import.meta.env.DEV) {
  installDevLongTaskLogger()
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
