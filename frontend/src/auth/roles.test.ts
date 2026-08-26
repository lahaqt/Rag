import { describe, expect, it } from 'vitest'
import { extractRoles } from './roles'

describe('OIDC role mapping', () => {
  it('normalizes direct and realm roles for student/admin route isolation', () => {
    const roles = extractRoles({ roles: ['student'], realm_access: { roles: ['admin'] } })

    expect(roles.has('STUDENT')).toBe(true)
    expect(roles.has('ADMIN')).toBe(true)
  })

  it('does not infer administrator access from an authenticated subject', () => {
    expect(extractRoles({ sub: 'student-1' }).has('ADMIN')).toBe(false)
  })
})
