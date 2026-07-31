# Aether Gateway docs site

[Astro](https://astro.build/) + [Starlight](https://starlight.astro.build/), configured to serve the repository's real `../docs/` folder directly (see `src/content.config.ts`'s comment) - the content here is not a copy, it's the same ADRs and design docs every source comment in the codebase already links to by that same path. Each top-level entry under `docs/` is symlinked individually into the content collection, so `docs/plan/` (the phase-by-phase build plan and STATUS.md) and `docs/PRD.md` never enter the build - both are gitignored at the repo root as internal/non-public material (`.gitignore`: "Only actual product code, architecture diagrams, tech-stack docs, README, and BENCHMARKS.md are public"). Deployed to GitHub Pages by `.github/workflows/docs.yml` on every push to `trunk` that touches `docs/` or `docs-site/`.

Live: <https://ashish-codejourney.github.io/aether-gateway/>

## Local development

```bash
npm install
npm run dev   # live-reloading dev server at http://localhost:4321
```

## Build

```bash
npm run build   # static output in dist/, not committed (see .gitignore)
```

## Adding or editing content

Edit files under `../docs/` (the repository's real docs folder), not anything under `docs-site/`. New top-level folders need a new symlink added under `src/content/docs/docs/` and a corresponding sidebar group in `astro.config.mjs` - Starlight's sidebar groups aren't derived from the filesystem the way Docusaurus's `_category_.json` was.

## One-time GitHub setup this repo needs

`.github/workflows/docs.yml` builds and deploys automatically, but GitHub Pages itself needs to be pointed at "GitHub Actions" as its source once: **Settings → Pages → Build and deployment → Source → GitHub Actions**. Until that's set, the workflow builds successfully but the deploy step fails with a permissions error - this is a repo setting, not something the workflow file can set itself.
