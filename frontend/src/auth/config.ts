export const oidcAuthority = import.meta.env.VITE_OIDC_AUTHORITY as string | undefined
export const oidcClientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined
export const oidcAudience = import.meta.env.VITE_OIDC_AUDIENCE as string | undefined
export const oidcConfigured = Boolean(oidcAuthority && oidcClientId)
