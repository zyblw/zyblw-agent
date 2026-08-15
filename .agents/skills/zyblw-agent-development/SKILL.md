---
name: zyblw-agent-development
description: Develop, review, test, document, or release the public zyblw-agent Scala 3/ZIO 2 framework. Use for runtime loops, tools, permissions, providers, context, memory, RAG, persistence, HTTP, observability, evals, public API compatibility, Maven publishing, examples, and source-learning work in this repository.
---

# zyblw Agent Development

Work from the checked-in implementation, not from an imagined framework.

## Establish the current contract

1. Read `AGENTS.md`.
2. Enter through `docs/README.md`; follow the task-specific path instead of reading every document.
3. Before changing public APIs or modules, also read `docs/architecture.md`,
   `docs/maturity-and-roadmap.md`, `docs/compatibility.md`, and `docs/releasing.md`.
4. Inspect the relevant build, source, tests, examples, migrations, and generated POM. Code and tests
   take precedence over roadmap prose.

## Preserve framework boundaries

- Keep this repository independently cloneable, testable, and publishable.
- Keep private product types, database schemas, fixtures, credentials, and release automation out.
- Keep provider-neutral ADTs, runtime, tools, permissions, context, and memory in `agent-core`.
- Add an artifact only for a real dependency, lifecycle, protocol, security, or license boundary.
- Let the model propose intent; validate, authorize, execute, trace, and stop in the harness.
- Prefer one bounded agent loop. Add orchestration only after replayable evals demonstrate a need.
- Treat administration as an optional sub-surface: every capability is a host-supplied `Option`,
  unwired capabilities mount no route, and admin scopes never reuse the business ownership rule.

## Implement in this order

1. Define or amend the domain contract and typed error/stop reason.
2. Add deterministic unit tests for success, denial, malformed input, timeout, cancellation, budget
   exhaustion, and redaction as relevant.
3. Implement provider/infrastructure adapters behind narrow traits.
4. Compose resources with scoped `ZLayer`; preserve interruption and cancellation semantics.
5. Add an executable example or consumer test when a public API changes.
6. Update an existing canonical document and `CHANGELOG.md`; add an ADR only for a durable
   cross-cutting decision.

## Verify version-sensitive facts

- For ZIO APIs, start at `https://zio.dev/llms.txt`, open the exact official page, and adapt it to
  the versions pinned in `project/Dependencies.scala`.
- For ZIO HTTP APIs, start at `https://ziohttp.com/llms.txt`.
- For Scala or sbt behavior, prefer the official documentation and the repository's actual build.
- Do not paste remembered signatures when compilation can prove the contract.

## Design and implement the dashboard

For any change under `modules/agent-dashboard`, read its `package.json`, generated `AGENTS.md`, the
affected route and components, and the current admin HTTP contract before proposing UI code.

1. For a new page or substantial redesign, use `frontend-design` to define the operator, the page's
   single job, a compact visual system, and one justified signature interaction. Keep the result an
   operations console rather than a generic SaaS landing page.
2. Use `ui-ux-pro-max` to compare data-density, navigation, color, typography, feedback, accessibility,
   and Next.js guidance. From Codex or Cursor, resolve and run
   `<repository-root>/.agents/skills/ui-ux-pro-max/scripts/search.py` with `python3`; the upstream
   `CLAUDE_PLUGIN_ROOT` command examples are only valid for a Claude plugin installation. Do not
   persist or overwrite a design system without reading any existing design decision first.
3. Apply `vercel-composition-patterns` to reusable component APIs and
   `vercel-react-best-practices` to rendering, data loading, and bundle work.
4. Apply `vercel-react-view-transitions` only when motion explains spatial continuity, loading, or a
   state change. Preserve focus, provide a non-animated fallback, respect reduced-motion preferences,
   and verify the repository's installed Next.js documentation before enabling experimental config.
5. Finish with `web-design-guidelines` plus a running-browser check of the complete user path at
   representative desktop and narrow viewport sizes. Typecheck/build success alone is insufficient.

## Run sbt outside the Cursor sandbox

Cursor's default command sandbox breaks sbt. Do not probe in-sandbox first.

Always invoke any `sbt` command with `required_permissions: ["all"]`. `full_network` alone is not
enough: the client/server path needs local IPC and hostname resolution, and sbt writes boot/cache
state under `~/.sbt` and the Coursier/Ivy home outside the workspace.

While iterating:

- Prefer the narrowest task that can falsify the change (`core/compile`, `http/testOnly …Spec`).
- Reuse a warm sbt server across turns; do not shut it down unless plugins or JVM options changed.
- Use `sbt --server -batch …` only when you need a foreground single process; it still requires
  `["all"]`.
- Run the full release gates below before release or merge readiness, not after every small edit.

## Release and compatibility gate

Treat Scala APIs, serialized state, HTTP/schema contracts, Maven coordinates, and Flyway migrations
as separate compatibility surfaces. During `0.x`, preserve public Scala APIs within a minor line for
patch releases. Never edit a released migration.

Run the smallest relevant checks during development and the full gates before release. From a Cursor
agent shell, every command below still needs `required_permissions: ["all"]`:

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.6.3-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.6.3-local sbt -batch compile
```

Console changes additionally require, in `modules/agent-dashboard`:

```bash
npm run typecheck && npm run lint && npm run build && npm run test:e2e
```

Live-provider tests remain opt-in. Never expose secrets, raw private prompts, provider payloads, or
production traces in source, fixtures, logs, documentation, or CI artifacts. An intermittently
failing test is a defect: fix the coupling that makes it racy rather than widening its timeout.

## Deliver evidence

Report the public contract changed, compatibility impact, tests executed, consumer verification,
documentation updated, rollback path, and any experimental limitations that remain.
