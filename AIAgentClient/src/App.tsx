import { Navigate, Routes, Route } from 'react-router-dom'
import { AgentPage } from './pages/AgentPage'
import { LoginPage } from './pages/LoginPage'
import { isLoggedIn } from './api/auth'

/** 登录守卫:未登录跳 /login(与主前端同 key 的 localStorage token) */
function RequireAuth({ children }: { children: React.ReactElement }) {
  return isLoggedIn() ? children : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="*" element={<RequireAuth><AgentPage /></RequireAuth>} />
    </Routes>
  )
}
