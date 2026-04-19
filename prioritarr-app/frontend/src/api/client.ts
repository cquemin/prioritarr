import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './generated'

const STORAGE_KEY = 'prioritarr_api_key'

export function getApiKey(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function setApiKey(key: string): void {
  localStorage.setItem(STORAGE_KEY, key)
}

export function clearApiKey(): void {
  localStorage.removeItem(STORAGE_KEY)
}

export class UnauthorizedError extends Error {
  constructor() {
    super('Unauthorized')
    this.name = 'UnauthorizedError'
  }
}

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const key = getApiKey()
    if (key) request.headers.set('X-Api-Key', key)
    return request
  },
  async onResponse({ response }) {
    if (response.status === 401) {
      clearApiKey()
      // Let the caller handle routing back to /login.
      throw new UnauthorizedError()
    }
    return response
  },
}

// Served behind Traefik at yoshiz.org/prioritarr; Vite's `base` puts
// `/prioritarr/` in import.meta.env.BASE_URL at runtime. Strip the
// trailing slash so openapi-fetch doesn't produce `//api/v2/...`.
export const API_BASE: string = (
  import.meta.env.VITE_BACKEND_URL ?? import.meta.env.BASE_URL ?? ''
).replace(/\/+$/, '')

export const apiClient = createClient<paths>({
  baseUrl: API_BASE,
})
apiClient.use(authMiddleware)
