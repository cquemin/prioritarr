import { useEffect, useState } from 'react'
import { clearApiKey, getApiKey, setApiKey } from '../api/client'

export function useAuth() {
  const [key, setKey] = useState<string | null>(() => getApiKey())

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === 'prioritarr_api_key') setKey(getApiKey())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  return {
    apiKey: key,
    isAuthed: key !== null,
    setApiKey: (k: string) => {
      setApiKey(k)
      setKey(k)
    },
    logout: () => {
      clearApiKey()
      setKey(null)
    },
  }
}
