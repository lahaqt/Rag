import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('ApiClient', () => {
  it('attaches the OIDC token and starts login recovery on 401', async () => {
    const unauthorized = vi.fn()
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'expired' }), { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(new ApiClient('token-1', unauthorized).request('/api/v1/projects')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer token-1')
    expect(unauthorized).toHaveBeenCalledOnce()
  })

  it('preserves 403 without starting another login', async () => {
    const unauthorized = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'forbidden' }), { status: 403 })))

    await expect(new ApiClient('token-2', unauthorized).request('/api/v1/admin/courses'))
      .rejects.toMatchObject({ status: 403, message: 'forbidden' })
    expect(unauthorized).not.toHaveBeenCalled()
  })
})
