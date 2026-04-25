import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Login } from './Login'

// Mock the openapi-fetch client so we can control the GET /api/v2/settings result.
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    apiClient: {
      GET: vi.fn(),
    },
  }
})

import { apiClient } from '../api/client'

describe('Login', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.clearAllMocks())

  it('rejected key shows error + does not persist', async () => {
    (apiClient.GET as ReturnType<typeof vi.fn>).mockResolvedValue({ error: { detail: 'nope' } })
    render(<Login />)
    await userEvent.type(screen.getByPlaceholderText('X-Api-Key'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: /connect/i }))
    expect(await screen.findByText(/rejected/i)).toBeInTheDocument()
    expect(localStorage.getItem('prioritarr_api_key')).toBeNull()
  })

  it('accepted key persists to localStorage', async () => {
    (apiClient.GET as ReturnType<typeof vi.fn>).mockResolvedValue({ data: {} })
    render(<Login />)
    await userEvent.type(screen.getByPlaceholderText('X-Api-Key'), 'valid-key')
    await userEvent.click(screen.getByRole('button', { name: /connect/i }))
    await waitFor(() =>
      expect(localStorage.getItem('prioritarr_api_key')).toBe('valid-key'),
    )
  })
})
