import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuth } from './useAuth'

describe('useAuth', () => {
  beforeEach(() => localStorage.clear())

  it('starts unauthenticated when no key stored', () => {
    const { result } = renderHook(() => useAuth())
    expect(result.current.apiKey).toBeNull()
    expect(result.current.isAuthed).toBe(false)
  })

  it('reads a pre-existing key from localStorage', () => {
    localStorage.setItem('prioritarr_api_key', 'sk-xyz')
    const { result } = renderHook(() => useAuth())
    expect(result.current.apiKey).toBe('sk-xyz')
    expect(result.current.isAuthed).toBe(true)
  })

  it('setApiKey writes + updates state', () => {
    const { result } = renderHook(() => useAuth())
    act(() => result.current.setApiKey('new-key'))
    expect(result.current.apiKey).toBe('new-key')
    expect(localStorage.getItem('prioritarr_api_key')).toBe('new-key')
  })

  it('logout clears key + state', () => {
    localStorage.setItem('prioritarr_api_key', 'existing')
    const { result } = renderHook(() => useAuth())
    act(() => result.current.logout())
    expect(result.current.apiKey).toBeNull()
    expect(localStorage.getItem('prioritarr_api_key')).toBeNull()
  })
})
