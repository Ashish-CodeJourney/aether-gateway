import { useMemo } from 'react'
import { createAdminApi } from './api'
import { useSettings } from './settings'

export function useAdminApi() {
  const { gatewayUrl, adminKey } = useSettings()
  return useMemo(() => createAdminApi(gatewayUrl, adminKey), [gatewayUrl, adminKey])
}
