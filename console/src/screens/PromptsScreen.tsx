import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useAdminApi } from '@/lib/useAdminApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { toast } from 'sonner'

/**
 * F7.4's exit criterion, demonstrated through the UI: create a prompt,
 * add versions, and re-point an alias - proving rollback takes effect
 * live, no gateway restart. Kept intentionally minimal (not a full
 * prompt-template editor) per the phase doc's own "a tight four-screen
 * console beats a sprawling half-finished dashboard" - this is a 5th,
 * deliberately small screen whose only job is proving this milestone's
 * headline capability through the real UI, not the API directly.
 */
export function PromptsScreen() {
  const api = useAdminApi()
  const [promptName, setPromptName] = useState('')
  const [alias, setAlias] = useState('production')
  const [version, setVersion] = useState('1')

  const createPromptMutation = useMutation({
    mutationFn: () => api.createPrompt(promptName),
    onSuccess: () => toast.success(`Created prompt "${promptName}"`),
    onError: (error) => toast.error(`Failed: ${error.message}`),
  })

  const createVersionMutation = useMutation({
    mutationFn: (content: string) =>
      api.createPromptVersion(promptName, [{ role: 'user', content }], []),
    onSuccess: (result) => toast.success(`Created version ${result.version} of "${promptName}"`),
    onError: (error) => toast.error(`Failed: ${error.message}`),
  })

  const setAliasMutation = useMutation({
    mutationFn: () => api.setPromptAlias(promptName, alias, Number(version)),
    onSuccess: () => toast.success(`"${alias}" now points at version ${version} - takes effect on the very next request, no restart`),
    onError: (error) => toast.error(`Failed: ${error.message}`),
  })

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>1. Create a prompt</CardTitle>
        </CardHeader>
        <CardContent className="flex items-end gap-2">
          <div className="flex-1">
            <label className="mb-1 block text-sm text-muted-foreground">Prompt name</label>
            <Input value={promptName} onChange={(e) => setPromptName(e.target.value)} placeholder="support-reply" />
          </div>
          <Button disabled={!promptName || createPromptMutation.isPending} onClick={() => createPromptMutation.mutate()}>
            Create
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>2. Add a version</CardTitle>
        </CardHeader>
        <CardContent className="flex items-end gap-2">
          <div className="flex-1">
            <label className="mb-1 block text-sm text-muted-foreground">Template text (single user message, no variables)</label>
            <Input
              placeholder="You are a helpful assistant..."
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  createVersionMutation.mutate((e.target as HTMLInputElement).value)
                }
              }}
            />
          </div>
          <p className="text-sm text-muted-foreground">Press Enter to submit</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>3. Point (or roll back) an alias - live, no restart</CardTitle>
        </CardHeader>
        <CardContent className="flex items-end gap-2">
          <div>
            <label className="mb-1 block text-sm text-muted-foreground">Alias</label>
            <Input value={alias} onChange={(e) => setAlias(e.target.value)} className="w-32" />
          </div>
          <div>
            <label className="mb-1 block text-sm text-muted-foreground">Version</label>
            <Input value={version} onChange={(e) => setVersion(e.target.value)} className="w-24" type="number" min={1} />
          </div>
          <Button disabled={!promptName || setAliasMutation.isPending} onClick={() => setAliasMutation.mutate()}>
            Point alias
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
