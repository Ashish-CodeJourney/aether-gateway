import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAdminApi } from '@/lib/useAdminApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { toast } from 'sonner'

export function KeysScreen() {
  const api = useAdminApi()
  const queryClient = useQueryClient()
  const [newKeyName, setNewKeyName] = useState('')
  const [revealedKey, setRevealedKey] = useState<string | null>(null)

  const keysQuery = useQuery({
    queryKey: ['api-keys'],
    queryFn: () => api.listApiKeys(),
  })

  const createMutation = useMutation({
    mutationFn: () => api.createApiKey({ name: newKeyName, tags: [] }),
    onSuccess: (created) => {
      setRevealedKey(created.raw_key)
      setNewKeyName('')
      queryClient.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: (error) => toast.error(`Failed to create key: ${error.message}`),
  })

  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.updateApiKey(id, { enabled }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['api-keys'] }),
    onError: (error) => toast.error(`Failed to update key: ${error.message}`),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteApiKey(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['api-keys'] }),
    onError: (error) => toast.error(`Failed to delete key: ${error.message}`),
  })

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Create API key</CardTitle>
        </CardHeader>
        <CardContent className="flex gap-2">
          <Input
            placeholder="Key name"
            value={newKeyName}
            onChange={(e) => setNewKeyName(e.target.value)}
          />
          <Button
            disabled={!newKeyName || createMutation.isPending}
            onClick={() => createMutation.mutate()}
          >
            Create
          </Button>
        </CardContent>
      </Card>

      {revealedKey && (
        <Card className="border-amber-500">
          <CardHeader>
            <CardTitle>New key created - shown once only</CardTitle>
          </CardHeader>
          <CardContent>
            <code className="break-all text-sm">{revealedKey}</code>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>API keys</CardTitle>
        </CardHeader>
        <CardContent>
          {keysQuery.isLoading && <p>Loading...</p>}
          {keysQuery.isError && <p className="text-destructive">Failed to load keys: {(keysQuery.error as Error).message}</p>}
          {keysQuery.data && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Prefix</TableHead>
                  <TableHead>RPS limit</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {keysQuery.data.map((key) => (
                  <TableRow key={key.id}>
                    <TableCell>{key.name}</TableCell>
                    <TableCell>
                      <code>{key.key_prefix}...</code>
                    </TableCell>
                    <TableCell>{key.rps_limit ?? '-'}</TableCell>
                    <TableCell>
                      <Badge variant={key.enabled ? 'default' : 'secondary'}>{key.enabled ? 'enabled' : 'disabled'}</Badge>
                    </TableCell>
                    <TableCell className="flex gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => toggleMutation.mutate({ id: key.id, enabled: !key.enabled })}
                      >
                        {key.enabled ? 'Disable' : 'Enable'}
                      </Button>
                      <Button size="sm" variant="destructive" onClick={() => deleteMutation.mutate(key.id)}>
                        Delete
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
                {keysQuery.data.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={5} className="text-center text-muted-foreground">
                      No API keys yet.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
