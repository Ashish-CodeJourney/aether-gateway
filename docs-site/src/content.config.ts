import {defineCollection} from 'astro:content';
import {docsLoader} from '@astrojs/starlight/loaders';
import {docsSchema} from '@astrojs/starlight/schema';

// `src/content/docs/docs` is a symlink to the repository's real `docs/`
// folder (one level up from this repo root's `docs-site/`), not a copy -
// the content itself is NOT duplicated here, matching what dozens of
// source comments across the codebase already cross-reference by that
// same relative path. The extra `docs/` nesting inside the collection
// (rather than symlinking straight into `content/docs/`) exists so
// generated routes keep their `/docs/...` prefix, matching every
// existing absolute link inside the docs content itself (e.g.
// `/docs/design/cache-correctness`) and leaving the site root free for
// the custom homepage at `src/pages/index.astro`.
export const collections = {
  docs: defineCollection({loader: docsLoader(), schema: docsSchema()}),
};
