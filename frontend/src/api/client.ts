export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export class ApiClient {
  private readonly accessToken: string
  private readonly onUnauthorized: () => void

  constructor(accessToken: string, onUnauthorized: () => void) {
    this.accessToken = accessToken
    this.onUnauthorized = onUnauthorized
  }

  async request<T>(path: string, options?: RequestInit): Promise<T> {
    const headers = new Headers(options?.headers)
    headers.set('Authorization', `Bearer ${this.accessToken}`)
    if (!(options?.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    const response = await fetch(path, { ...options, headers })
    if (response.status === 401) this.onUnauthorized()
    if (!response.ok) {
      const body = await response.json().catch(() => ({})) as { message?: string; detail?: string }
      throw new ApiError(response.status, body.message ?? body.detail ?? `Request failed (${response.status})`)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }
}
