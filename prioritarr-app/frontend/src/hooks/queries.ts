import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'

interface PaginationInput {
  offset?: number
  limit?: number
  sort?: string
  sort_dir?: 'asc' | 'desc'
}

export function useSeriesList(p: PaginationInput = {}) {
  return useQuery({
    queryKey: ['series', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/series', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export function useSeries(id: number | null) {
  return useQuery({
    queryKey: ['series', id],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/series/{id}', {
        params: { path: { id: id! } },
      })
      if (error) throw error
      return data
    },
    enabled: id != null,
  })
}

export function useRecomputeSeries() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => {
      const { data, error } = await apiClient.POST('/api/v2/series/{id}/recompute', {
        params: { path: { id } },
      })
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['series'] }),
  })
}

export function useDownloads(p: PaginationInput & { client?: 'qbit' | 'sab' } = {}) {
  return useQuery({
    queryKey: ['downloads', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/downloads', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export function useDownloadAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { client: string; clientId: string; action: 'pause' | 'resume' | 'boost' | 'demote' }) => {
      const { data, error } = await apiClient.POST(
        '/api/v2/downloads/{client}/{clientId}/actions/{action}',
        { params: { path: v } },
      )
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
  })
}

export function useUntrackDownload() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { client: string; clientId: string }) => {
      const { data, error } = await apiClient.DELETE(
        '/api/v2/downloads/{client}/{clientId}',
        { params: { path: v } },
      )
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
  })
}

export function useAudit(p: PaginationInput & { series_id?: number; action?: string; since?: string } = {}) {
  return useQuery({
    queryKey: ['audit', p],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/audit', {
        params: { query: p },
      })
      if (error) throw error
      return data
    },
  })
}

export function useSettings() {
  return useQuery({
    queryKey: ['settings'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/settings')
      if (error) throw error
      return data
    },
  })
}

export function useMappings() {
  return useQuery({
    queryKey: ['mappings'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v2/mappings')
      if (error) throw error
      return data
    },
  })
}

export function useRefreshMappings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const { data, error } = await apiClient.POST('/api/v2/mappings/refresh')
      if (error) throw error
      return data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mappings'] }),
  })
}
