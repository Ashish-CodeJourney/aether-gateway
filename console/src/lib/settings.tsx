import { createContext, useContext, useState, type ReactNode } from 'react'

interface Settings {
  gatewayUrl: string
  adminKey: string
}

interface SettingsContextValue extends Settings {
  setGatewayUrl: (url: string) => void
  setAdminKey: (key: string) => void
}

const STORAGE_KEY = 'aether-console-settings'

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw) as Settings
  } catch {
    // fall through to defaults
  }
  return { gatewayUrl: 'http://localhost:8080', adminKey: '' }
}

function saveSettings(settings: Settings) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
}

const SettingsContext = createContext<SettingsContextValue | null>(null)

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(loadSettings)

  const setGatewayUrl = (gatewayUrl: string) => {
    const next = { ...settings, gatewayUrl }
    setSettings(next)
    saveSettings(next)
  }
  const setAdminKey = (adminKey: string) => {
    const next = { ...settings, adminKey }
    setSettings(next)
    saveSettings(next)
  }

  return (
    <SettingsContext.Provider value={{ ...settings, setGatewayUrl, setAdminKey }}>
      {children}
    </SettingsContext.Provider>
  )
}

export function useSettings(): SettingsContextValue {
  const ctx = useContext(SettingsContext)
  if (!ctx) throw new Error('useSettings must be used within a SettingsProvider')
  return ctx
}
