import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAdminApi } from '@/lib/useAdminApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { toast } from 'sonner'

export function CacheScreen() {
  const api = useAdminApi()
  const queryClient = useQueryClient()
  const [namespace, setNamespace] = useState('anonymous')
  const [invalidatePrefix, setInvalidatePrefix] = useState('')

  const statsQuery = useQuery({
    queryKey: ['cache-stats', namespace],
    queryFn: () => api.cacheStats(namespace),
  })

  const invalidateMutation = useMutation({
    mutationFn: () => api.invalidateCache(namespace, invalidatePrefix),
    onSuccess: (result) => {
      toast.success(`Removed ${result.removed} cache ${result.removed === 1 ? 'entry' : 'entries'}`)
      queryClient.invalidateQueries({ queryKey: ['cache-stats', namespace] })
    },
    onError: (error) => toast.error(`Failed to invalidate: ${error.message}`),
  })

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Cache stats</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-end gap-2">
            <div className="flex-1">
              <label className="mb-1 block text-sm text-muted-foreground">Namespace</label>
              <Input value={namespace} onChange={(e) => setNamespace(e.target.value)} />
            </div>
          </div>
          {statsQuery.isLoading && <p>Loading...</p>}
          {statsQuery.isError && <p className="text-destructive">Failed to load: {(statsQuery.error as Error).message}</p>}
          {statsQuery.data && (
            <p className="text-2xl font-semibold">
              {statsQuery.data.entry_count} <span className="text-base font-normal text-muted-foreground">live entries</span>
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Manual invalidation</CardTitle>
        </CardHeader>
        <CardContent className="flex items-end gap-2">
          <div className="flex-1">
            <label className="mb-1 block text-sm text-muted-foreground">Model prefix (blank = all models in this namespace)</label>
            <Input value={invalidatePrefix} onChange={(e) => setInvalidatePrefix(e.target.value)} placeholder="e.g. mock" />
          </div>
          <Button variant="destructive" disabled={invalidateMutation.isPending} onClick={() => invalidateMutation.mutate()}>
            Invalidate
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
