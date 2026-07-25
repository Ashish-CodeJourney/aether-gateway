/**
 * Thin fetch wrapper for the gateway's admin API (ADR-009: the console
 * is a driving adapter, HTTP only, never the database directly).
 *
 * Field names below are deliberately snake_case, matching the wire
 * format exactly: gateway-proxy's application.yml sets
 * spring.jackson.property-naming-strategy: SNAKE_CASE globally, for
 * both serialization and deserialization, so every admin endpoint
 * (and every other endpoint in this project) speaks snake_case JSON -
 * confirmed live against the real running gateway, not assumed.
 */

export class AdminApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(
  gatewayUrl: string,
  adminKey: string,
  path: string,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(`${gatewayUrl}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Aether-Admin-Key': adminKey,
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new AdminApiError(response.status, body || response.statusText)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export interface ApiKeySummary {
  id: string
  name: string
  key_prefix: string
  tags: string[]
  rps_limit: number | null
  concurrency_limit: number | null
  monthly_token_budget: number | null
  monthly_usd_budget: string | null
  enabled: boolean
  created_at: string
}

export interface CreatedApiKey {
  summary: ApiKeySummary
  raw_key: string
}

export interface CacheStats {
  namespace: string
  entry_count: number
}

export interface UsageAggregate {
  group_value: string
  request_count: number
  input_tokens: number
  output_tokens: number
  cost_usd: string
  saved_usd: string
}

export interface RequestLogEntry {
  id: string
  api_key_id: string
  route_alias: string | null
  provider: string | null
  model: string | null
  streamed: boolean
  cache_outcome: string
  similarity: number | null
  input_tokens: number | null
  output_tokens: number | null
  cost_usd: string | null
  saved_usd: string | null
  ttfb_ms: number | null
  total_ms: number | null
  status: string
  error_code: string | null
  created_at: string
}

export interface BreakerStatus {
  provider: string
  model: string
  state: string
}

export interface PromptVersionResponse {
  prompt_id: string
  prompt_name: string
  version: number
}

export function createAdminApi(gatewayUrl: string, adminKey: string) {
  return {
    listApiKeys: () => request<ApiKeySummary[]>(gatewayUrl, adminKey, '/admin/api-keys'),
    createApiKey: (body: { name: string; tags: string[]; rps_limit?: number; concurrency_limit?: number; monthly_token_budget?: number }) =>
      request<CreatedApiKey>(gatewayUrl, adminKey, '/admin/api-keys', { method: 'POST', body: JSON.stringify(body) }),
    updateApiKey: (id: string, body: { enabled?: boolean }) =>
      request<ApiKeySummary>(gatewayUrl, adminKey, `/admin/api-keys/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    deleteApiKey: (id: string) =>
      request<void>(gatewayUrl, adminKey, `/admin/api-keys/${id}`, { method: 'DELETE' }),

    cacheStats: (namespace: string) =>
      request<CacheStats>(gatewayUrl, adminKey, `/admin/cache/stats?namespace=${encodeURIComponent(namespace)}`),
    invalidateCache: (namespace: string, prefix: string) =>
      request<{ namespace: string; removed: number }>(
        gatewayUrl,
        adminKey,
        `/admin/cache?namespace=${encodeURIComponent(namespace)}&prefix=${encodeURIComponent(prefix)}`,
        { method: 'DELETE' },
      ),

    usage: (from: string, to: string, groupBy: string) =>
      request<UsageAggregate[]>(gatewayUrl, adminKey, `/admin/usage?from=${from}&to=${to}&groupBy=${groupBy}`),

    recentRequests: (limit = 100) =>
      request<RequestLogEntry[]>(gatewayUrl, adminKey, `/admin/requests?limit=${limit}`),

    providerHealth: () => request<BreakerStatus[]>(gatewayUrl, adminKey, '/admin/providers/health'),

    createPrompt: (name: string) =>
      request<PromptVersionResponse>(gatewayUrl, adminKey, '/admin/prompts', { method: 'POST', body: JSON.stringify({ name }) }),
    createPromptVersion: (name: string, template: { role: string; content: string }[], variables: string[]) =>
      request<PromptVersionResponse>(gatewayUrl, adminKey, `/admin/prompts/${encodeURIComponent(name)}/versions`, {
        method: 'POST',
        body: JSON.stringify({ template, variables }),
      }),
    setPromptAlias: (name: string, alias: string, version: number) =>
      request<void>(gatewayUrl, adminKey, `/admin/prompts/${encodeURIComponent(name)}/aliases/${encodeURIComponent(alias)}`, {
        method: 'PUT',
        body: JSON.stringify({ version }),
      }),
  }
}

export type AdminApi = ReturnType<typeof createAdminApi>
