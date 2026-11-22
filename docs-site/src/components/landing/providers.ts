// Icons are Simple Icons (CC0) copied into public/providers. Groq and OpenAI
// have no icon there, so they get a text badge rather than a drawn logo.
export type Provider = {id: string; name: string; short: string; note: string} & ({icon: string} | {badge: string});

export const PROVIDERS: Provider[] = [
  {id: 'ollama', name: 'Ollama', short: 'Ollama', note: 'local', icon: 'ollama'},
  {id: 'groq', name: 'Groq', short: 'Groq', note: 'cloud', badge: 'Gq'},
  {id: 'gemini', name: 'Google Gemini', short: 'Gemini', note: 'cloud', icon: 'googlegemini'},
  {id: 'anthropic', name: 'Anthropic', short: 'Anthropic', note: 'cloud', icon: 'anthropic'},
  {id: 'openai', name: 'OpenAI', short: 'OpenAI', note: 'cloud', badge: 'OA'},
];

export const base = import.meta.env.BASE_URL.replace(/\/$/, '');

export const iconStyle = (icon: string): string => `--icon: url(${base}/providers/${icon}.svg)`;
