import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'
import { AuthoringAuthProvider } from './auth/AuthoringAuthProvider'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthoringAuthProvider><App /></AuthoringAuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
