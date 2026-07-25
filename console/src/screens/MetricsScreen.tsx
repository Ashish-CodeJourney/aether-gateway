import { useQuery } from '@tanstack/react-query'
import { useAdminApi } from '@/lib/useAdminApi'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

const GRAFANA_URL = 'http://localhost:3000'

export function MetricsScreen() {
  const api = useAdminApi()

  const healthQuery = useQuery({
    queryKey: ['provider-health'],
    queryFn: () => api.providerHealth(),
    refetchInterval: 5000,
  })

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Provider breaker state</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {healthQuery.isLoading && <p>Loading...</p>}
          {healthQuery.isError && <p className="text-destructive">Failed to load: {(healthQuery.error as Error).message}</p>}
          {healthQuery.data?.map((status) => (
            <Badge
              key={`${status.provider}:${status.model}`}
              variant={status.state === 'OPEN' ? 'destructive' : status.state === 'HALF_OPEN' ? 'secondary' : 'default'}
            >
              {status.provider} / {status.model}: {status.state}
            </Badge>
          ))}
          {healthQuery.data?.length === 0 && <p className="text-muted-foreground">No provider calls observed yet.</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Live dashboard</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="mb-2 text-sm text-muted-foreground">
            Embeds the Grafana dashboard provisioned in Phase 08 (F6.6) - RPS, latency percentiles, cache hit rate,
            spend vs. spend avoided, breaker state timeline, in-flight streams, top keys by cost, error rate.
          </p>
          <iframe
            title="Aether Gateway dashboard"
            src={`${GRAFANA_URL}/d/aether-gateway?orgId=1&kiosk&theme=light`}
            className="h-[800px] w-full rounded-md border"
          />
        </CardContent>
      </Card>
    </div>
  )
}
