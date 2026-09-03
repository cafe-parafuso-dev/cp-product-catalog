# ADR 0013 — Consume platform-shared-contracts via GitHub Packages

- **Status**: Accepted
- **Date**: 2026-09-03
- **Deciders**: Hubinity Platform team
- **Supersedes**: [ADR 0012](0012-vendor-contracts-via-git-subtree.md)

## Context and Problem Statement

ADR 0012 vendored the full `platform-shared-contracts` tree into this repo
via `git subtree`, to unblock a Railway build that couldn't resolve
`contracts-catalog`/`contracts-events` (the Metal builder clones only this
repo, with neither a sibling checkout nor a populated `~/.m2`). It rejected
GitHub Packages as an alternative, reasoning that `platform-shared-contracts`'
ADR 0006 (build-only CI, no publish) meant standing up registry auth and a
publish pipeline from scratch — judged premature for a single consumer
(N=1).

That reasoning was based on stale information. `platform-shared-contracts`'
own ADR 0010 ("Enable GitHub Packages publish") **superseded ADR 0006 and
merged a working publish pipeline into `main` on 2026-08-12** —
`publish-maven.yml` (tag-triggered, gated on the org's `GH_PACKAGES_TOKEN`)
and an uncommented `<distributionManagement>` block. That is **eight days
before ADR 0012 was written**. `com.hubinity.contracts-catalog` and
`com.hubinity.contracts-events` were already published under the
`v0.1.0-SNAPSHOT` tag by the time ADR 0012 rejected this option. Confirmed
via `gh api /orgs/hubinity/packages?package_type=maven` — both packages
exist, version `0.1.0-SNAPSHOT`, published 2026-08-12.

In other words: the registry ADR 0012 said would need to be built already
existed and already carried the artifacts this service needs. The
"premature for N=1" driver never actually applied.

## Decision Drivers

- The registry option's stated blocker (no publish pipeline) is gone —
  choosing it now costs configuring a Maven repository + credentials, not
  building CI infrastructure.
- ADR 0012's known downsides stand as written: vendors all five contract
  modules though only two are used, staleness is silent by default, and it
  was explicitly flagged as "not a permanent pattern to replicate
  elsewhere."
- `platform-shared-contracts` is a build-time Maven dependency, not a
  runtime peer — the normal microservice-to-microservice channels (REST,
  RabbitMQ events) don't apply here; a package registry is the correct
  analogue for a shared library, which is what ADR 0012 was reaching for in
  the first place before rejecting it on outdated grounds.

## Considered Options

- **Keep the git-subtree vendoring (ADR 0012)** — pros: no new
  configuration; cons: every downside ADR 0012 already documented (repo
  bloat, silent staleness, single-repo stopgap by its own admission) with
  no remaining reason to accept them. Rejected.
- **GitHub Packages** (this ADR) — pros: ordinary Maven dependency
  resolution, no vendored copy to keep in sync, works for any number of
  future consumers without further changes, the publish pipeline already
  exists and is already producing the artifacts needed. Cons: build-time
  network + registry-credential dependency (mitigated by the existing
  `-B -ntp` cache-mount approach and the org's already-provisioned
  `GH_PACKAGES_TOKEN`, reused rather than a new secret). Accepted.

## Decision Outcome

**Chosen**: declare `https://maven.pkg.github.com/hubinity/platform-shared-contracts`
as a `<repository>` in `pom.xml`, authenticate via `settings.xml`
(`GITHUB_USERNAME`/`GITHUB_TOKEN` env vars, server id `github` — mirrors the
template already published at `platform-shared-contracts/settings.xml`),
and drop the vendored subtree entirely.

**Dockerfile**: no longer copies or builds a vendored
`platform-shared-contracts/` tree. It copies `settings.xml` into the build
stage and passes it to every `mvn` invocation (`-s settings.xml`);
`GITHUB_USERNAME`/`GITHUB_TOKEN` arrive as build ARGs, expected to be set as
Railway *build-time* variables on the `hb-catalog-service` service.

**Local dev**: unaffected. `CLAUDE.md`'s existing prerequisite (`mvn install`
in a sibling `platform-shared-contracts` checkout) still populates `~/.m2`
directly; Maven prefers an already-installed matching SNAPSHOT over hitting
the registry, so no local auth is required day-to-day.

**Removed**: `platform-shared-contracts/` (the vendored tree),
`.github/workflows/contracts-subtree-staleness.yml` (nothing left to check
staleness on).

## Consequences

- ✅ No vendored copy — one fewer place `contracts-catalog`/`contracts-events`
  can drift from upstream.
- ✅ Scales to more consumers (`hb-cashier-service`, `sc-order-service`, …)
  with zero additional work per consumer beyond this same `pom.xml` +
  `settings.xml` pattern.
- ✅ Reuses the org's existing `GH_PACKAGES_TOKEN` (already scoped
  `read:packages`, already used for GHCR — see `RUNBOOK.md` §4.2) — no new
  secret to provision or rotate.
- ⚠️ Build now depends on GitHub Packages being reachable and the
  credentials being valid; a revoked/expired token breaks the Docker build,
  not just a deploy of new contract versions. Acceptable — it is the same
  class of dependency `mvn dependency:go-offline` against Maven Central
  already has.
- ⚠️ `GITHUB_TOKEN` passed as a Docker build ARG persists in the *build
  stage's* layer history (not the final runtime image, discarded by the
  multi-stage build). Acceptable at this org's current scale per the same
  reasoning ADR 0010 already applied to `GH_PACKAGES_TOKEN` being a single
  shared secret; revisit with a BuildKit secret mount
  (`--mount=type=secret`) if Railway's builder supports it and this
  becomes a concern.
