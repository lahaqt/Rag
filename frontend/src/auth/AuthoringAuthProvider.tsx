import type { PropsWithChildren } from 'react'
import { AuthProvider } from 'react-oidc-context'
import { oidcAudience, oidcAuthority, oidcClientId, oidcConfigured } from './config'

export function AuthoringAuthProvider({ children }: PropsWithChildren) {
  if (!oidcConfigured) return children
  return (
    <AuthProvider
      authority={oidcAuthority!}
      client_id={oidcClientId!}
      redirect_uri={`${window.location.origin}/app`}
      post_logout_redirect_uri={window.location.origin}
      response_type="code"
      scope="openid profile"
      automaticSilentRenew
      extraQueryParams={oidcAudience ? { audience: oidcAudience } : undefined}
      onSigninCallback={() => window.history.replaceState({}, document.title, '/app')}
    >
      {children}
    </AuthProvider>
  )
}
