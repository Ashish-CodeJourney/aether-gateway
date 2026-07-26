import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';
import Heading from '@theme/Heading';
import styles from './index.module.css';

const STATS: {value: string; label: string; note?: string}[] = [
  {value: '8.9ms', label: 'Cache-hit latency, p95', note: 'target: 50ms'},
  {value: '67.92ms', label: 'Failover latency, p99 (100 trials)', note: 'target: 500ms'},
  {value: '500/500', label: 'Concurrent streams survived a real rolling update', note: 'run twice, zero broken'},
  {value: '76.8%', label: 'Line coverage, 4 core modules', note: 'JaCoCo, unit + integration'},
];

const FEATURES: {title: string; body: ReactNode}[] = [
  {
    title: 'Routing and failover',
    body: (
      <>
        Circuit breakers, retries, and bulkheads across multiple providers (Ollama, Groq, Gemini, or any
        OpenAI-compatible endpoint) - a failing provider doesn't take your application down with it. See{' '}
        <Link to="/docs/adr/circuit-breaker-state-local-with-redis-advisory-hint">why breaker state is local, not shared</Link>.
      </>
    ),
  },
  {
    title: 'Semantic caching',
    body: (
      <>
        Near-duplicate prompts are served from a vector cache (Postgres + pgvector) instead of hitting a provider
        again, with a guard against false hits. See{' '}
        <Link to="/docs/design/cache-correctness">how the false-hit rate is actually measured</Link>.
      </>
    ),
  },
  {
    title: 'Quota enforcement',
    body: 'Per-API-key rate limits, concurrency caps, and monthly token budgets, checked and reserved atomically before a request is ever dispatched - not after the fact.',
  },
  {
    title: 'Cost accounting',
    body: 'Every request’s real (or avoided, on a cache hit) cost, computed from a configurable per-model price sheet and exposed in response headers, the request log, and a Grafana dashboard.',
  },
  {
    title: 'Versioned prompt registry',
    body: 'Name a prompt, version it, and roll a production alias back to a previous version on the next request - no gateway restart, no redeploy.',
  },
  {
    title: 'Kubernetes-proven',
    body: (
      <>
        Graceful SSE drain under a real rolling update, in-flight-stream-based autoscaling, not CPU. See{' '}
        <Link to="/docs/design/kubernetes-deployment">the graceful-drain chain in detail</Link>.
      </>
    ),
  },
];

export default function Home(): ReactNode {
  return (
    <Layout
      title="Aether Gateway"
      description="A self-hosted LLM gateway - streaming proxy, multi-provider failover, and vector-based semantic caching, measured against a reproducible load-test harness.">
      <header className={styles.hero}>
        <div className={`container ${styles.heroInner}`}>
          <span className={styles.kicker}>Self-hosted LLM gateway</span>
          <Heading as="h1" className={styles.heroTitle}>
            Aether Gateway
          </Heading>
          <p className={styles.heroLede}>
            Point an existing OpenAI-compatible SDK at Aether instead of at a provider directly. It handles routing,
            failover, semantic caching, quota enforcement, cost accounting, and observability transparently - measured
            to cut cache-hit latency to <code>8.9ms p95</code> and fail over to a healthy provider in{' '}
            <code>67.92ms p99</code>, both against a reproducible load-test harness, not estimates.
          </p>
          <div className={styles.ctaRow}>
            <Link className="button button--primary button--lg" to="/docs/getting-started">
              Get started &rarr;
            </Link>
            <Link className="button button--outline button--lg" to="/docs/usage">
              Usage guide
            </Link>
            <Link className="button button--outline button--lg" href="https://github.com/Ashish-CodeJourney/aether-gateway">
              View source
            </Link>
          </div>
        </div>
      </header>

      <main>
        <section className={styles.section}>
          <div className="container">
            <Heading as="h2" className={styles.sectionTitle}>
              Measured, not estimated
            </Heading>
            <p className={styles.sectionLede}>
              Every number here is reproducible via <code>make bench</code> against a real running stack - full
              methodology and raw data in{' '}
              <Link href="https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/BENCHMARKS.md">BENCHMARKS.md</Link>.
            </p>
            <div className={styles.statGrid}>
              {STATS.map((stat) => (
                <div className={styles.statCard} key={stat.label}>
                  <div className={styles.statValue}>{stat.value}</div>
                  <div className={styles.statLabel}>{stat.label}</div>
                  {stat.note && <div className={styles.statNote}>{stat.note}</div>}
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className={`${styles.section} ${styles.sectionAlt}`}>
          <div className="container">
            <Heading as="h2" className={styles.sectionTitle}>
              What actually happens on a request
            </Heading>
            <p className={styles.sectionLede}>
              The response headers tell you exactly what the gateway did - no guessing whether a request was served
              from cache or which provider actually answered it.
            </p>
            <div className={styles.demoLayout}>
              <CodeBlock language="console">
                {`$ curl -X POST http://localhost:8080/v1/chat/completions \\
    -H "Content-Type: application/json" \\
    -d '{"model":"mock","messages":[{"role":"user","content":"What is 2+2?"}]}' -D -

HTTP/1.1 200 OK
X-Aether-Cache: MISS
X-Aether-Cost-USD: 0.0000195
X-Aether-Attempts: 1
X-Aether-Provider: mock-primary`}
              </CodeBlock>
              <div className={styles.demoNote}>
                <p>Same prompt, sent again:</p>
                <ul>
                  <li>
                    <code>X-Aether-Cache: EXACT_HIT</code> - served from the semantic cache, <code>X-Aether-Cost-USD: 0</code>
                  </li>
                </ul>
                <p>Force the primary provider to fail, then send a new prompt:</p>
                <ul>
                  <li>
                    <code>X-Aether-Provider: mock-fallback</code> - failover happened, same request shape, no client
                    change
                  </li>
                </ul>
                <p>
                  This is a real transcript from a real <code>docker compose up</code>, not a mockup - see the{' '}
                  <Link to="/docs/getting-started">Getting Started guide</Link> to reproduce it yourself in under a
                  minute.
                </p>
              </div>
            </div>
          </div>
        </section>

        <section className={styles.section}>
          <div className="container">
            <Heading as="h2" className={styles.sectionTitle}>
              What it does
            </Heading>
            <p className={styles.sectionLede}>
              The engineering value isn't in calling models - it's in everything around the call. Six things it
              actually handles for you:
            </p>
            <div className={styles.featureGrid}>
              {FEATURES.map((feature) => (
                <div className={styles.featureCard} key={feature.title}>
                  <div className={styles.featureTitle}>{feature.title}</div>
                  <div className={styles.featureBody}>{feature.body}</div>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className={styles.finalCta}>
          <div className="container">
            <Heading as="h2" className={styles.finalCtaTitle}>
              Bring up the whole stack in one command
            </Heading>
            <p className={styles.finalCtaLede}>
              Gateway, Postgres+pgvector, Redis, two mock providers, Prometheus, and a pre-provisioned Grafana
              dashboard - seeded and ready, no manual setup.
            </p>
            <div className={styles.ctaRow} style={{justifyContent: 'center'}}>
              <Link className="button button--primary button--lg" to="/docs/getting-started">
                Getting Started guide &rarr;
              </Link>
              <Link className="button button--outline button--lg" to="/docs/">
                Browse the docs
              </Link>
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}
