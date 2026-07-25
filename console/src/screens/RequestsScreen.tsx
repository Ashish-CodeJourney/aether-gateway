import { useQuery } from '@tanstack/react-query'
import { useAdminApi } from '@/lib/useAdminApi'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

const CACHE_OUTCOME_VARIANT: Record<string, 'default' | 'secondary' | 'outline'> = {
  MISS: 'outline',
  EXACT_HIT: 'default',
  SEMANTIC_HIT: 'default',
  BYPASS: 'secondary',
}

export function RequestsScreen() {
  const api = useAdminApi()

  const requestsQuery = useQuery({
    queryKey: ['recent-requests'],
    queryFn: () => api.recentRequests(100),
    refetchInterval: 10000,
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Recent requests</CardTitle>
      </CardHeader>
      <CardContent>
        {requestsQuery.isLoading && <p>Loading...</p>}
        {requestsQuery.isError && <p className="text-destructive">Failed to load: {(requestsQuery.error as Error).message}</p>}
        {requestsQuery.data && (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Time</TableHead>
                  <TableHead>Route</TableHead>
                  <TableHead>Provider</TableHead>
                  <TableHead>Model</TableHead>
                  <TableHead>Cache</TableHead>
                  <TableHead>Cost</TableHead>
                  <TableHead>Total ms</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {requestsQuery.data.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell>{new Date(entry.created_at).toLocaleTimeString()}</TableCell>
                    <TableCell>{entry.route_alias ?? '-'}</TableCell>
                    <TableCell>{entry.provider ?? '-'}</TableCell>
                    <TableCell>{entry.model ?? '-'}</TableCell>
                    <TableCell>
                      <Badge variant={CACHE_OUTCOME_VARIANT[entry.cache_outcome] ?? 'outline'}>{entry.cache_outcome}</Badge>
                    </TableCell>
                    <TableCell>{entry.cost_usd ? `$${entry.cost_usd}` : '-'}</TableCell>
                    <TableCell>{entry.total_ms ?? '-'}</TableCell>
                    <TableCell>
                      <Badge variant={entry.status === 'OK' ? 'default' : 'destructive'}>{entry.status}</Badge>
                    </TableCell>
                  </TableRow>
                ))}
                {requestsQuery.data.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8} className="text-center text-muted-foreground">
                      No requests logged yet.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
