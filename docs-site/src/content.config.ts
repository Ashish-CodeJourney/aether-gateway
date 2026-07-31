import {defineCollection} from 'astro:content';
import {docsLoader} from '@astrojs/starlight/loaders';
import {docsSchema} from '@astrojs/starlight/schema';

// `src/content/docs/docs/*` are individual symlinks into the
// repository's real `docs/` folder (one level up from this repo root's
// `docs-site/`), not a copy - the content itself is NOT duplicated
// here, matching what dozens of source comments across the codebase
// already cross-reference by that same relative path. Each entry
// (adr/, design/, getting-started.md, index.md, usage.md) is symlinked
// individually, rather than symlinking the whole `docs/` directory in
// one shot, so `docs/plan/` (the phase-by-phase build plan, gitignored
// internal planning material - see ../.gitignore) and `docs/PRD.md`
// (also gitignored there) never enter Starlight's docsLoader() glob,
// which has no exclude option. This matches Docusaurus's old explicit
// `exclude: ['plan/**', 'PRD.md']` by construction instead of by glob
// pattern: the build is identical whether those files exist on disk or
// not, same guarantee as before. The extra `docs/` nesting inside the
// collection exists so generated routes keep their `/docs/...` prefix,
// matching every existing absolute link inside the docs content itself
// (e.g. `/docs/design/cache-correctness`) and leaving the site root
// free for the custom homepage at `src/pages/index.astro`.
export const collections = {
  docs: defineCollection({loader: docsLoader(), schema: docsSchema()}),
};
