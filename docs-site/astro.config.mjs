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
        light: './public/logo.svg',
        dark: './public/logo-dark.svg',
        alt: 'Aether Gateway',
        replacesTitle: true,
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
          attrs: {rel: 'icon', href: '/aether-gateway/favicon.ico', sizes: '32x32'},
        },
        {
          tag: 'link',
          attrs: {rel: 'apple-touch-icon', href: '/aether-gateway/apple-touch-icon.png'},
        },
        {
          tag: 'meta',
          attrs: {name: 'theme-color', content: '#0B1B34'},
        },
        {
          tag: 'meta',
          attrs: {property: 'og:image', content: 'https://ashish-codejourney.github.io/aether-gateway/img/social-card.jpg'},
        },
        {
          tag: 'meta',
          attrs: {property: 'og:image:width', content: '1200'},
        },
        {
          tag: 'meta',
          attrs: {property: 'og:image:height', content: '630'},
        },
        {
          tag: 'meta',
          attrs: {property: 'og:type', content: 'website'},
        },
        {
          tag: 'meta',
          attrs: {name: 'twitter:card', content: 'summary_large_image'},
        },
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
