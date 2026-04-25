import { beforeEach, describe, expect, it } from 'vitest'
import { clearApiKey, getApiKey, setApiKey } from './client'

describe('api-key storage helpers', () => {
  beforeEach(() => localStorage.clear())

  it('getApiKey returns null by default', () => {
    expect(getApiKey()).toBeNull()
  })

  it('setApiKey persists', () => {
    setApiKey('abc')
    expect(localStorage.getItem('prioritarr_api_key')).toBe('abc')
    expect(getApiKey()).toBe('abc')
  })

  it('clearApiKey removes', () => {
    setApiKey('x')
    clearApiKey()
    expect(getApiKey()).toBeNull()
  })
})
