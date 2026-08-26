type RealmAccess = { roles?: string[] }

export function extractRoles(profile: Record<string, unknown> | undefined) {
  const direct = Array.isArray(profile?.roles) ? profile.roles.map(String) : []
  const realm = profile?.realm_access as RealmAccess | undefined
  return new Set([...direct, ...(realm?.roles ?? [])].map(role => role.toUpperCase()))
}
