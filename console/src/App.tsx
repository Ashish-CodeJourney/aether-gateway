import { useState } from 'react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { useSettings } from '@/lib/settings'
import { KeysScreen } from '@/screens/KeysScreen'
import { MetricsScreen } from '@/screens/MetricsScreen'
import { RequestsScreen } from '@/screens/RequestsScreen'
import { CacheScreen } from '@/screens/CacheScreen'
import { PromptsScreen } from '@/screens/PromptsScreen'
import { Toaster } from '@/components/ui/sonner'

function SettingsDialog() {
  const { gatewayUrl, adminKey, setGatewayUrl, setAdminKey } = useSettings()
  const [open, setOpen] = useState(false)
  const [draftUrl, setDraftUrl] = useState(gatewayUrl)
  const [draftKey, setDraftKey] = useState(adminKey)

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Settings
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Connection settings</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm text-muted-foreground">Gateway URL</label>
            <Input value={draftUrl} onChange={(e) => setDraftUrl(e.target.value)} />
          </div>
          <div>
            <label className="mb-1 block text-sm text-muted-foreground">Admin key (X-Aether-Admin-Key)</label>
            <Input type="password" value={draftKey} onChange={(e) => setDraftKey(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button
            onClick={() => {
              setGatewayUrl(draftUrl)
              setAdminKey(draftKey)
              setOpen(false)
            }}
          >
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function App() {
  const { adminKey } = useSettings()

  return (
    <div className="mx-auto max-w-6xl p-6">
      <header className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Aether Gateway console</h1>
        <SettingsDialog />
      </header>

      {!adminKey && (
        <p className="mb-4 rounded-md border border-amber-500 bg-amber-50 p-3 text-sm text-amber-800">
          No admin key configured - open Settings and paste your <code>X-Aether-Admin-Key</code> value before using
          this console.
        </p>
      )}

      <Tabs defaultValue="keys">
        <TabsList>
          <TabsTrigger value="keys">Keys</TabsTrigger>
          <TabsTrigger value="metrics">Metrics</TabsTrigger>
          <TabsTrigger value="requests">Requests</TabsTrigger>
          <TabsTrigger value="cache">Cache</TabsTrigger>
          <TabsTrigger value="prompts">Prompts</TabsTrigger>
        </TabsList>
        <TabsContent value="keys">
          <KeysScreen />
        </TabsContent>
        <TabsContent value="metrics">
          <MetricsScreen />
        </TabsContent>
        <TabsContent value="requests">
          <RequestsScreen />
        </TabsContent>
        <TabsContent value="cache">
          <CacheScreen />
        </TabsContent>
        <TabsContent value="prompts">
          <PromptsScreen />
        </TabsContent>
      </Tabs>
      <Toaster />
    </div>
  )
}
