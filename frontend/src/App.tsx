import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { oidcConfigured } from './auth/config'
import { extractRoles } from './auth/roles'
import CoachWorkspace from './features/coach/CoachWorkspace'
import './App.css'

function ProtectedApplication() {
  const auth = useAuth()
  if (auth.isLoading) return <main className="auth-state"><h1>Opening your workspace</h1><p>Validating your identity…</p></main>
  if (auth.error) return <main className="auth-state"><h1>Sign-in failed</h1><p>{auth.error.message}</p><button className="primary-button" onClick={() => void auth.signinRedirect()}>Try again</button></main>
  if (!auth.isAuthenticated || !auth.user) {
    return <main className="auth-state"><h1>Engineering Authoring Coach</h1><p>Sign in to open your course workspace.</p><button className="primary-button" onClick={() => void auth.signinRedirect()}>Sign in</button></main>
  }
  const userRoles = extractRoles(auth.user.profile as Record<string, unknown>)
  const adminRoute = window.location.pathname.startsWith('/admin')
  if (adminRoute && !userRoles.has('ADMIN')) {
    return <main className="auth-state"><h1>Administrator access required</h1><p>Your account cannot open the administration console.</p><a href="/app">Return to your workspace</a></main>
  }
  return <CoachWorkspace adminRoute={adminRoute} accessToken={auth.user.access_token}
    userId={String(auth.user.profile.sub)} roles={[...userRoles]} onUnauthorized={() => void auth.signinRedirect()}
    onSignOut={() => void auth.signoutRedirect()} />
}

export default function App() {
  if (!oidcConfigured) {
    return <main className="auth-state"><h1>Identity provider configuration required</h1><p>Set VITE_OIDC_AUTHORITY and VITE_OIDC_CLIENT_ID before starting the Authoring Coach.</p></main>
  }
  return <Routes><Route path="/" element={<Navigate to="/app" replace />} /><Route path="/app/*" element={<ProtectedApplication />} /><Route path="/admin/*" element={<ProtectedApplication />} /><Route path="*" element={<Navigate to="/app" replace />} /></Routes>
}
