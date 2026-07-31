// @ts-check
import {defineConfig} from 'astro/config';
import starlight from '@astrojs/starlight';

// GitHub Pages: project site at https://<org>.github.io/<repo>/
export default defineConfig({
  site: 'https://ashish-codejourney.github.io',
  base: '/aether-gateway/',
  trailingSlash: 'never',

  integrations: [
    starlight({
      title: 'Aether Gateway',
      description:
        'A self-hosted LLM gateway - streaming proxy, multi-provider failover, and vector-based semantic caching',
      favicon: '/favicon.svg',
      logo: {
        src: './public/img/logo.svg',
        replacesTitle: false,
      },
      social: [
        {icon: 'github', label: 'GitHub', href: 'https://github.com/Ashish-CodeJourney/aether-gateway'},
      ],
      editLink: {
        baseUrl: 'https://github.com/Ashish-CodeJourney/aether-gateway/edit/trunk/docs/',
      },
      customCss: ['./src/styles/custom.css'],
      head: [
        {
          tag: 'link',
          attrs: {rel: 'preconnect', href: 'https://fonts.googleapis.com'},
        },
        {
          tag: 'link',
          attrs: {rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: true},
        },
        {
          tag: 'link',
          attrs: {
            rel: 'stylesheet',
            href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500;600&display=swap',
          },
        },
      ],
      sidebar: [
        {label: 'Introduction', link: '/docs/'},
        {label: 'Getting Started', link: '/docs/getting-started/'},
        {label: 'Usage Guide', link: '/docs/usage/'},
        {label: 'Architecture Decisions', items: [{autogenerate: {directory: 'docs/adr'}}]},
        {label: 'Design Docs', items: [{autogenerate: {directory: 'docs/design'}}]},
        {
          label: 'Project',
          items: [
            {label: 'License (MIT)', link: 'https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/LICENSE'},
            {
              label: 'Contributing',
              link: 'https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/CONTRIBUTING.md',
            },
            {
              label: 'Code of Conduct',
              link: 'https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/CODE_OF_CONDUCT.md',
            },
            {
              label: 'Security Policy',
              link: 'https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/SECURITY.md',
            },
          ],
        },
      ],
    }),
  ],
});
