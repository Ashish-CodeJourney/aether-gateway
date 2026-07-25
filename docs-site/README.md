# Aether Gateway docs site

[Docusaurus](https://docusaurus.io/), configured to serve the repository's real `../docs/` folder directly (see `docusaurus.config.ts`'s `path` comment) - the content here is not a copy, it's the same ADRs and design docs every source comment in the codebase already links to by that same path. `docs/plan/` (the phase-by-phase build plan and STATUS.md) and `docs/PRD.md` are both deliberately excluded (see the `docs.exclude` comment in `docusaurus.config.ts`) - both are gitignored at the repo root as internal/non-public material (`.gitignore`: "Only actual product code, architecture diagrams, tech-stack docs, README, and BENCHMARKS.md are public"). Verified the build succeeds identically with both genuinely absent (not just untracked), matching what a real CI checkout sees. Deployed to GitHub Pages by `.github/workflows/docs.yml` on every push to `trunk` that touches `docs/` or `docs-site/`.

Live: <https://ashish-codejourney.github.io/Sluice/>

## Local development

```bash
npm install
npm start   # live-reloading dev server at http://localhost:3000
```

## Build

```bash
npm run build   # static output in build/, not committed (see .gitignore)
```

## Adding or editing content

Edit files under `../docs/` (the repository's real docs folder), not anything under `docs-site/`. New folders get a sidebar category label via a `_category_.json` file in that folder (see `../docs/adr/_category_.json` for the pattern) - otherwise Docusaurus falls back to the folder name.

## One-time GitHub setup this repo needs

`.github/workflows/docs.yml` builds and deploys automatically, but GitHub Pages itself needs to be pointed at "GitHub Actions" as its source once: **Settings → Pages → Build and deployment → Source → GitHub Actions**. Until that's set, the workflow builds successfully but the deploy step fails with a permissions error - this is a repo setting, not something the workflow file can set itself.
