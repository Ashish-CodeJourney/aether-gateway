# Aether Gateway operator console

Phase 10 (M7), PRD section 9.4: React 19 + TypeScript + Vite + TanStack
Query + Tailwind + shadcn/ui + Recharts-ready. Four tight screens
(**Keys**, **Metrics**, **Requests**, **Cache**) plus a small fifth
**Prompts** screen whose only job is proving this milestone's exit
criterion live - roll a prompt version back through the real UI, no
gateway restart.

This is a driving adapter (ADR-009): it talks to the gateway's admin
API over HTTP only, never the database directly.

## Running it

```bash
npm install
npm run dev
```

Open the app, click **Settings**, and paste in:

- **Gateway URL**: `http://localhost:8080` (or wherever `docker compose up` published it)
- **Admin key**: the value of `AETHER_ADMIN_API_KEY` the gateway was started with
  (`docker-compose.yml`'s dev default is `dev-admin-key-do-not-use-in-production`)

The gateway must have granted this console's origin CORS access to
`/admin/**` - `aether.admin.console-origin` / `AETHER_ADMIN_API_KEY`,
defaulting to `http://localhost:5173` (Vite's dev server default).

## Wire format

Every field name in `src/lib/api.ts` is deliberately `snake_case`,
matching the gateway's global `spring.jackson.property-naming-strategy:
SNAKE_CASE` exactly (confirmed live against the real running gateway,
not assumed) - this is not idiomatic TypeScript style, but it is the
real wire format, and a camelCase mismatch here was a real bug caught
during this phase's own end-to-end verification.
