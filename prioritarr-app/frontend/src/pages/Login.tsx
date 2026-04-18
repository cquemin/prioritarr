import { useState } from 'react'
import { apiClient } from '../api/client'
import { useAuth } from '../hooks/useAuth'

export function Login() {
  const { setApiKey } = useAuth()
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    localStorage.setItem('prioritarr_api_key', value)
    const { error: err } = await apiClient.GET('/api/v2/settings')
    setBusy(false)
    if (err) {
      localStorage.removeItem('prioritarr_api_key')
      setError('API key rejected.')
    } else {
      setApiKey(value)
    }
  }

  return (
    <div className="h-screen flex items-center justify-center bg-surface-0">
      <form onSubmit={onSubmit} className="bg-surface-1 p-8 rounded-lg shadow-xl w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">prioritarr</h1>
        <p className="text-sm opacity-70 text-center">Enter your API key to continue.</p>
        <input
          type="password"
          autoFocus
          required
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="X-Api-Key"
          className="w-full px-3 py-2 rounded bg-surface-2 border border-surface-3 focus:outline-none focus:border-accent"
        />
        {error && <p className="text-red-400 text-sm">{error}</p>}
        <button
          type="submit"
          disabled={busy || !value}
          className="w-full py-2 rounded bg-accent hover:opacity-90 disabled:opacity-50 font-medium"
        >
          {busy ? 'Connecting…' : 'Connect'}
        </button>
      </form>
    </div>
  )
}
