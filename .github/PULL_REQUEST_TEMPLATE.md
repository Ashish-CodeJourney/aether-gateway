## What and why

<!-- What does this change, and why? Link the issue it addresses if there is one. -->

## How was this tested?

<!--
- [ ] Unit tests (gradlew test)
- [ ] Integration tests against real infra (gradlew integrationTest)
- [ ] Acceptance tests (gradlew :gateway-acceptance-tests:test)
- [ ] docs-site build (cd docs-site && npm run build), if docs/ or docs-site/ changed
-->

## Checklist

- [ ] Tests were written first (see [CONTRIBUTING.md](../CONTRIBUTING.md)) and actually fail without the fix
- [ ] `gradle test integrationTest` passes locally
- [ ] No new module dependency violates the hexagonal boundary (`gateway-core` depends on nothing but the JDK)
- [ ] This PR is scoped to one coherent change
