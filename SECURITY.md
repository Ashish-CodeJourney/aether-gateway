# Security Policy

## Reporting a vulnerability

Please don't open a public GitHub issue for a security vulnerability. Instead, use GitHub's [private vulnerability reporting](https://github.com/Ashish-CodeJourney/aether-gateway/security/advisories/new) for this repository, or email **ashish.vaghela@nelkinda.com** with:

- What the vulnerability is and its likely impact.
- Steps to reproduce it (a `curl` command against a fresh `docker compose up` is usually enough).
- Any suggested fix, if you have one - not required.

This is a personal/portfolio project without a formal SLA, but security reports get priority over everything else and I'll acknowledge within a few days.

## What's already in scope and handled

So a report isn't spent re-discovering something already addressed - see the [Usage Guide](https://ashish-codejourney.github.io/aether-gateway/docs/usage#authentication) for the current state of:

- **API keys** are stored as salted hashes, never plaintext.
- **Provider credentials** are injected via environment variable *names* in `routing.yaml` (never the raw value), resolved only where an adapter is actually constructed, and never logged.
- **SSRF protection**: a real, operator-configured provider base URL that resolves to a private/reserved IP range is rejected outright at routing-policy reload time (unit, integration, *and* end-to-end acceptance-tested).
- **Request limits**: a bounded max message count and an explicit request body size limit.
- **Per-IP rate limiting** on unauthenticated (anonymous) requests specifically, separate from the per-API-key quota system.
- **Admin API** (`/admin/**`) is fail-closed - a blank or missing configured admin key rejects every admin request, not just the ones that happen to need real protection.

## Supported versions

This project doesn't yet have tagged releases with a maintenance policy - the `trunk` branch is the only supported line. Once tagged releases exist, only the latest will receive security fixes.
