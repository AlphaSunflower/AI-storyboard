import { Routes, Route } from 'react-router-dom'
import { AgentPage } from './pages/AgentPage'

export default function App() {
  return (
    <Routes>
      <Route path="*" element={<AgentPage />} />
    </Routes>
  )
}
