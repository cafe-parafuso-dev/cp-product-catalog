# ADR 0012 — Vendor platform-shared-contracts via git subtree (stopgap)

- **Status**: Superseded by [ADR 0013](0013-consume-contracts-via-github-packages.md)
- **Date**: 2026-08-20
- **Deciders**: Hubinity Platform team

> **2026-09-03 update**: the "GitHub Packages" option below was rejected as
> premature for a single consumer, citing `platform-shared-contracts`' ADR
> 0006 (build-only CI, no publish). That was stale information — that repo's
> own ADR 0010 had already superseded ADR 0006 and merged a working
> publish pipeline on 2026-08-12, eight days *before* this ADR was written,
> and `contracts-catalog`/`contracts-events` were already published under
> `v0.1.0-SNAPSHOT`. See ADR 0013 for the correction.

## Context and Problem Statement

`hb-catalog-service` depends on `com.hubinity:contracts-catalog` and
`com.hubinity:contracts-events` (both `0.1.0-SNAPSHOT`), built from the
sibling `platform-shared-contracts` repo. Locally this works because devs
run `mvn install` in `platform-shared-contracts` first, populating
`~/.m2`. Railway's Dockerfile builder ("Metal builder") gets neither that
local `.m2` nor the sibling checkout — it clones only `hb-catalog-service`
in isolation, so `mvn package` fails with "Could not find artifact
com.hubinity:contracts-catalog:jar:0.1.0-SNAPSHOT".

`platform-shared-contracts/docs/adr/0006-build-only-ci-no-publish-yet.md`
already anticipated this: GitHub Packages publishing is deferred there on
purpose, with consumers expected to `mvn install` locally "until a Fase 0
follow-up enables `mvn deploy`." This ADR is that gap showing up in a real
deploy, before the Fase 0 follow-up landed.

## Decision Drivers

- Unblock the Railway deploy today, without standing up registry auth
  (`GITHUB_TOKEN`, package scopes) that ADR 0006 explicitly deferred.
- Keep the Dockerfile's existing build steps (cache mounts, `-pl
  contracts-catalog,contracts-events -am install`) unchanged if possible.
- Today there is exactly one consumer of these contracts
  (`hb-catalog-service`, N=1). Don't over-invest in a multi-consumer
  publishing pipeline for a problem only one repo has.

## Considered Options

- **Git submodule, pinned to a SHA** — pros: standard tooling, thin repo
  (only a pointer is stored); cons: **empirically fails** — Railway's
  Metal builder does not check out submodule content. Confirmed via a
  real build: `RUN ls platform-shared-contracts/pom.xml` → `No such file
  or directory`, even though the submodule was correctly committed and
  pinned to an already-pushed SHA. Rejected.
- **`git clone` of platform-shared-contracts inside a `RUN` step** — pros:
  no submodule/subtree tooling, works regardless of the builder's outer
  checkout behavior; cons: re-fetches over the network on every
  build-cache miss, no local-dev-friendly working copy, couples the
  build to git/network availability at build time. Deferred in favor of
  subtree, which gets the files into the repo proper.
- **GitHub Packages** — pros: standard CI/CD pattern, no vendored copy to
  keep in sync, works for any number of consumers; cons: requires wiring
  registry auth and a publish pipeline in `platform-shared-contracts`
  that ADR 0006 explicitly deferred; premature for a single consumer.
  Deferred — see Decision Outcome for the trigger to revisit.
- **`git subtree add --squash`, pinned to a SHA** — pros: ordinary
  tracked files after the merge, so anything that can check out
  `hb-catalog-service` (Railway included) gets them automatically, no
  submodule initialization step required; cons: repo grows by the vendored
  tree's size, staleness is silent unless actively checked (no visible
  pointer like a submodule has). Accepted.

## Decision Outcome

**Chosen**: vendor the full `platform-shared-contracts` tree under
`platform-shared-contracts/` via `git subtree add --prefix
platform-shared-contracts <repo-url> <ref> --squash`, pinned to commit
`4cbdf83` (already pushed to `origin/feature/stock-balance-path` in
`platform-shared-contracts`). The Dockerfile is unchanged from the
submodule attempt: it still `COPY`s the directory and runs `mvn -f
platform-shared-contracts/pom.xml -pl contracts-catalog,contracts-events
-am install -DskipTests` — only the git mechanism moving those files into
the repo changed, from submodule to subtree.

**Update flow**: `git subtree pull --prefix platform-shared-contracts
https://github.com/hubinity/platform-shared-contracts.git <branch>
--squash`, run whenever the vendored contracts need to catch up.
Validated end-to-end in a disposable local sandbox (throwaway clone +
throwaway simulated upstream commit) before being documented here as the
official flow.

**Staleness check**: `.github/workflows/contracts-subtree-staleness.yml`
runs weekly, parses the vendored SHA out of the `git subtree`
squash-commit trailer, compares it against upstream `HEAD`, and emits a
`::warning::` (never fails the build) on drift.

**Explicit migration trigger**: revisit GitHub Packages publishing (per
ADR 0006's own stated direction) when a second repo
(`hb-cashier-service`, `sc-order-service`, …) needs
`contracts-catalog`/`contracts-events`. Until then this stays a one-repo
stopgap, not a permanent pattern to replicate elsewhere.

## Consequences

- ✅ Railway build unblocked today; no new registry secrets to manage.
- ✅ Local dev workflow unaffected — the vendored files are ordinary
  tracked files, no submodule init step for anyone cloning the repo.
- ✅ Update path is a single documented command, validated before being
  written down here.
- ⚠️ Vendors the *entire* `platform-shared-contracts` tree (all five
  contract modules), not just the two consumed by this service — the
  Dockerfile only *builds* `contracts-catalog`/`contracts-events`, but
  the extra modules still live in this repo's history.
- ⚠️ Staleness is silent by default — nothing blocks a stale vendor copy
  from shipping. Mitigated, not eliminated, by the weekly warning-only CI
  check.
- ⚠️ `contracts-subtree-staleness.yml` currently compares against the
  `feature/stock-balance-path` branch of `platform-shared-contracts` —
  update it to track `main` once that work merges.
