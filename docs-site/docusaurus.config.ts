import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Aether Gateway',
  tagline: 'A self-hosted LLM gateway - streaming proxy, multi-provider failover, and vector-based semantic caching',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  // GitHub Pages: project site at https://<org>.github.io/<repo>/
  url: 'https://ashish-codejourney.github.io',
  baseUrl: '/Sluice/',

  organizationName: 'Ashish-CodeJourney',
  projectName: 'Sluice',
  deploymentBranch: 'gh-pages',
  trailingSlash: false,

  onBrokenLinks: 'throw',

  // The existing docs/ content was written as plain CommonMark, not
  // MDX, and has things like bare `<` in prose (comparison operators,
  // shell redirects) that MDX's JSX-aware parser rejects. Since the
  // content is real project documentation this site deliberately does
  // not duplicate or rewrite (see the docs plugin's `path` below), the
  // correct fix is telling Docusaurus to parse .md files as plain
  // Markdown rather than editing every file to be MDX-safe.
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  // Tried @docusaurus/theme-mermaid for live-rendered diagrams
  // (docs/design/architecture-diagram.md has two flowcharts); reverted
  // after finding it silently drops page content when a fenced
  // ```mermaid block's node labels contain HTML `<br/>` tags (which
  // this project's diagrams use for multi-line labels) - not a missing
  // diagram, actual surrounding prose disappearing from the built page,
  // confirmed by comparing rendered output with and without the theme.
  // A real, correctly-labelled ```mermaid code block (readable as text,
  // not visually rendered) is a safe, deliberate fallback over content
  // loss; revisit if upgrading past Docusaurus 3.10.2 / theme-mermaid's
  // current version resolves the underlying interaction.

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        // Docs-only site: no blog, docs served at the site root. The
        // content itself is NOT duplicated here - `path` points at the
        // repository's real docs/ folder (one level up from this repo
        // root's `docs-site/`, i.e. `../docs` from here), which is also
        // what dozens of source comments across the codebase already
        // cross-reference by that same relative path. Moving or copying
        // it would break every one of those references for no benefit.
        docs: {
          path: '../docs',
          // docs/plan/ (the phase-by-phase build plan, including
          // STATUS.md) is deliberately gitignored as internal planning
          // material, not part of the public portfolio repo (see
          // ../.gitignore) - excluded here too, explicitly, rather than
          // relying on it simply being absent from a fresh checkout,
          // so this site's content is identical whether built locally
          // (where the files happen to still be on disk, just
          // untracked) or in CI (where they never exist at all).
          exclude: ['plan/**'],
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/Ashish-CodeJourney/Sluice/edit/trunk/docs/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/docusaurus-social-card.jpg',
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Aether Gateway',
      logo: {
        alt: 'Aether Gateway',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          href: 'https://github.com/Ashish-CodeJourney/Sluice',
          label: 'GitHub',
          position: 'right',
        },
        {
          href: 'https://github.com/Ashish-CodeJourney/Sluice/blob/trunk/BENCHMARKS.md',
          label: 'Benchmarks',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Project',
          items: [
            {label: 'README', href: 'https://github.com/Ashish-CodeJourney/Sluice#readme'},
            {label: 'Benchmarks', href: 'https://github.com/Ashish-CodeJourney/Sluice/blob/trunk/BENCHMARKS.md'},
            {label: 'PRD', to: '/PRD'},
          ],
        },
        {
          title: 'Design',
          items: [
            {label: 'Architecture Decision Records', to: '/category/architecture-decisions'},
            {label: 'Design docs', to: '/category/design-docs'},
          ],
        },
      ],
      copyright: `Aether Gateway - a portfolio/resume project built to production standards. Docs built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
