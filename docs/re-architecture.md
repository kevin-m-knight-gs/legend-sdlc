# Re-architecting `legend-sdlc`: Separating Core, Backends, and Server

## 1. Goals

This plan re-architects `legend-sdlc` so that:

1. **The backend is separate from the server.** GitLab becomes one backend among many,
   with a clearly defined SPI for implementing a backend and a configuration-driven way
   to select one for a server deployment.
2. **A backend implements as little as possible.** Functionality that can be expressed
   over the project file abstraction (entity access and editing, project configuration,
   dependency resolution, comparison) is implemented once, generically, and inherited by
   every backend.
3. **As much functionality as possible is usable outside the server.** In particular:
   entity editing operations on a model in a local checkout, including a Legend model
   embedded in a larger project that also contains non-Legend content.

This plan subsumes [`project-structure-extraction.md`](project-structure-extraction.md):
that document's phases remain valid and become the early phases here (its Phase 1 is
already partially complete). Where this document and that one conflict, this one governs.

### Non-goals

- Changing the REST API surface consumed by Legend Studio. The server's resources and
  wire formats stay compatible; this is an internal re-layering.
- Changing the on-disk project layout (`project.json`, entity source directories,
  project structure versions). Layout knowledge moves; its semantics do not change.
- Building any specific new backend beyond what is needed to prove the SPI (the existing
  filesystem backend, plus an in-memory backend for testing). GitHub/Bitbucket/etc. become
  *possible*, not *delivered*.

## 2. Current State Assessment

What exists today, and what it tells us:

| Observation | Implication |
|---|---|
| `legend-sdlc-server` is 349 source files containing five distinct things: the domain API interfaces (`server/domain/api/**` — `EntityApi`, `ProjectApi`, `WorkspaceApi`, …), the GitLab implementation (`server/gitlab/**`, ~50 files), project structure (`server/project/**`), the JAX-RS resources (`server/resources/**`), and the Dropwizard/Guice application. | The seams already exist *as packages*; they need to become *modules*. |
| The `domain/api` interfaces are already a de-facto backend SPI: `legend-sdlc-server-fs` implements them as a second backend. | We do not need to invent the SPI from scratch — we need to extract, rationalize, and minimize it. |
| `FileSystemEntityApi` (740 lines) and `GitLabEntityApi` (767 lines) are near-duplicates. Both implement entity read/write by composing `ProjectFileAccessProvider.FileAccessContext` with `ProjectStructure`. The same duplication pattern holds for configuration, comparison, and dependency APIs. | The single biggest lever for "minimize what a backend implements": generic implementations over the file abstraction, written once. The duplication is existence proof that the logic is backend-independent. |
| `ProjectFileAccessProvider` (in `legend-sdlc-project-files`) is the right low-level abstraction: files + revisions + modifications, addressed by `(projectId, SourceSpecification, revisionId)`. | The storage SPI exists. Its problems are (a) it lives in a `server.*` package, (b) it drags in workspace/patch concepts via `SourceSpecification`. |
| `BaseLegendSDLCServer` hard-wires GitLab (`GitLabBundle`, `GitLabConfiguration` in the bootstrap path), even though the interfaces would permit substitution; `legend-sdlc-server-fs` works around this with its own parallel startup. | Backend selection must move from inheritance/forking to configuration. |
| The generation modules and Maven plugins (`legend-sdlc-generation-*`, `legend-sdlc-entity-maven-plugin`, …) and `EntityLoader` already operate on files/entities with no server dependency. | The "usable outside the server" goal is already achieved for *reading and generating*. The gap is *editing* and *project-structure awareness* outside the server. |
| `legend-sdlc-shared` exists; `StringTools`/`IOTools` have moved; `LegendSDLCServerException` still carries a JAX-RS dependency. | Phase 1 of the extraction doc is nearly done. |

## 3. Target Architecture

### 3.1 Layers

```
┌────────────────────────────────────────────────────────────────────┐
│  L6  SERVER         legend-sdlc-server (JAX-RS resources,          │
│                     Dropwizard, auth, Guice, backend selection)    │
├────────────────────────────────────────────────────────────────────┤
│  L5  BACKENDS       legend-sdlc-backend-gitlab                     │
│                     legend-sdlc-backend-fs                         │
│                     legend-sdlc-backend-inmemory (testing)         │
│                     (future: github, bitbucket, plain git, …)      │
├────────────────────────────────────────────────────────────────────┤
│  L4  BACKEND SPI    legend-sdlc-backend-api (the domain API,       │
│                     capability model, backend factory SPI)         │
│      + BACKEND TCK  legend-sdlc-backend-test-suite                 │
├────────────────────────────────────────────────────────────────────┤
│  L3  SDLC CORE      legend-sdlc-core (generic implementations of   │
│                     entity access/edit, configuration read/update, │
│                     dependency resolution, comparison — written    │
│                     once over L1+L2)                               │
├────────────────────────────────────────────────────────────────────┤
│  L2  STRUCTURE      legend-sdlc-project-structure (read-side       │
│                     layout knowledge, per the extraction doc)      │
├────────────────────────────────────────────────────────────────────┤
│  L1  STORAGE SPI    legend-sdlc-project-files                      │
│                     (ProjectFileAccessProvider et al.)             │
├────────────────────────────────────────────────────────────────────┤
│  L0  FOUNDATIONS    legend-sdlc-model, legend-sdlc-shared,         │
│                     legend-sdlc-entity-serialization               │
└────────────────────────────────────────────────────────────────────┘

      LOCAL TOOLING (parallel consumer of L0–L3, no L4–L6):
      legend-sdlc-local — entity editing on a local checkout,
      model-in-a-larger-project support, CLI-able
```

Rules:

- Nothing below L6 may depend on Dropwizard, Guice, pac4j, or JAX-RS.
- Nothing below L5 may depend on GitLab4J or any backend-specific library.
- L3 (core) depends only on L0–L2. A backend (L5) depends on L4 (and hence L0–L3).
- `legend-sdlc-local` depends on L0–L3 only. It must be usable from a plain `main`
  method, a Maven plugin, or another product's JVM with no container of any kind.

### 3.2 The two SPIs

There are deliberately **two** levels at which one can plug in, because they serve
different implementers:

1. **The storage SPI (L1)** — `ProjectFileAccessProvider`: files at revisions,
   file modifications, revision history. This is what a backend implements to get all
   of L3's generic functionality for free.
2. **The backend SPI (L4)** — the domain APIs (`EntityApi`, `ProjectApi`,
   `WorkspaceApi`, `ReviewApi`, …). Most of these get default implementations from L3;
   a backend *overrides* rather than *implements* them when it can do better natively
   (e.g. GitLab can serve a comparison via the GitLab compare API instead of walking
   files), or when the concept is inherently backend-native (reviews ≈ merge requests,
   workflows ≈ CI pipelines).

The minimal backend contract is therefore:

| Required | Why it cannot be generic |
|---|---|
| `ProjectFileAccessProvider` (file access + modification + revision contexts) | This *is* the storage |
| Project lifecycle (`ProjectApi`: create/get/list/delete, access roles if supported) | Maps to backend's repository concept |
| Workspace lifecycle (`WorkspaceApi`: create/list/delete/update) | Maps to backend's branching concept |
| A `BackendFactory` (see §3.5) | Bootstrap |

Everything else — entity read/write, project configuration read/update, dependency
resolution, comparison, conflict-resolution mechanics — comes from L3 defaults, and
the remaining concept-level APIs (reviews, versions, patches, workflows, builds) are
**optional capabilities** (§3.4).

### 3.3 What moves where

**`legend-sdlc-project-files` (L1) — rationalized, not just relocated:**

- Stays the home of `ProjectFileAccessProvider`, `ProjectFile`, `ProjectFileOperation`,
  `ProjectPaths`, caching/empty/abstract contexts.
- `SourceSpecification` is split. Today it conflates two ideas: *"which line of
  development"* (project / workspace / patch) and *"address of file content"*. L1 keeps
  an opaque, backend-interpreted source address; the workspace/patch taxonomy moves up
  to L4 where those concepts are defined. Concretely: L1's contract becomes
  `getFileAccessContext(sourceHandle, revisionId)` where `sourceHandle` is produced by
  L4/L5 code; generic L3 code never inspects its internals. This removes the last
  conceptual server dependency from the storage layer. (If splitting proves too
  disruptive in early phases, an acceptable interim is to move `SourceSpecification`
  as-is and split later; the phase plan allows both.)
- Packages migrate from `org.finos.legend.sdlc.server.project` to
  `org.finos.legend.sdlc.projectfiles` (or similar non-`server` package) — see §5 for
  the compatibility strategy.

**`legend-sdlc-project-structure` (L2) — as per the extraction doc, unchanged in scope:**

- Read-side of `ProjectStructure`, version factories, `EntitySourceDirectory`,
  `Simple*` configuration classes, the `maven/` structure implementations.
- One addition to the extraction doc: the **write-side** (`UpdateBuilder` /
  `updateProjectConfiguration()`) does *not* stay in the server — it moves to L3
  (`legend-sdlc-core`), because configuration updates are expressed entirely as
  `ProjectFileOperation`s against a `FileModificationContext` and are needed by local
  tooling too (e.g. adding a dependency to a local checkout). The extraction doc placed
  it in the server only because L3 did not exist in its world view.

**`legend-sdlc-core` (L3) — new; the de-duplication target:**

- `EntityAccessContext` / `EntityModificationContext` implementations over
  `FileAccessContext` + `ProjectStructure` (today's duplicated logic in
  `GitLabEntityApi`/`FileSystemEntityApi`, factored once: entity-file discovery,
  serialization/deserialization via `legend-sdlc-entity-serialization`, create/update/
  delete computed as file operations).
- Project configuration read (already in `ProjectStructure`) and update
  (`ProjectStructureUpdater`, generalized from `UpdateBuilder`).
- Dependency resolution (`DependenciesApiImpl` is already backend-neutral — it moves
  here).
- Comparison at the file/entity level (compute diffs by walking two
  `FileAccessContext`s); backends may override with native diffing.
- Conflict-resolution mechanics that are pure file/entity computation.

**`legend-sdlc-backend-api` (L4) — extracted from `server/domain/api/**`:**

- The API interfaces (`EntityApi`, `ProjectApi`, `ProjectConfigurationApi`,
  `WorkspaceApi`, `RevisionApi`, `ReviewApi`, `VersionApi`, `PatchApi`, `WorkflowApi`,
  `WorkflowJobApi`, `BuildApi`, `ComparisonApi`, `ConflictResolutionApi`, `BackupApi`,
  `DependenciesApi`, `UserApi`, `IssueApi`), the workspace/source taxonomy, and
  `ProjectConfigurationUpdater`.
- A `Backend` aggregate interface: one object exposing the APIs plus capability
  discovery, so consumers hold one handle rather than seventeen injected interfaces.
- The capability model (§3.4) and backend factory SPI (§3.5).
- Abstract base classes wiring L3 defaults: a backend extends e.g.
  `AbstractBackend`, supplies its `ProjectFileAccessProvider` + lifecycle APIs, and
  inherits entity/configuration/dependency/comparison behavior.

**`legend-sdlc-backend-test-suite` (L4) — new:**

- A TCK: abstract JUnit test classes parameterized over a `BackendFactory`, exercising
  the full SPI contract (file access semantics, entity round-trips, configuration
  updates, workspace lifecycle, capability declarations vs. actual behavior). "A
  clearly defined way to implement a backend" is as much executable contract as
  documentation. The in-memory and FS backends run it in this repo; GitLab runs it
  against the existing integration-test infrastructure; external backend authors run
  it against theirs.

**`legend-sdlc-backend-gitlab` (L5) — extracted from `server/gitlab/**`:**

- The 50 GitLab files, minus whatever dissolves into L3 defaults. Expected to shrink
  substantially: the file-walking halves of the `GitLab*Api` classes disappear;
  what remains is GitLab API plumbing (auth, branches/MRs/pipelines/tags ↔
  workspaces/reviews/workflows/versions) — the part that genuinely *is* GitLab.
- GitLab4J and GitLab auth move here; the server keeps no GitLab dependency.

**`legend-sdlc-backend-fs` (L5) — `legend-sdlc-server-fs` refactored:**

- Keeps its local-git storage implementation; drops its duplicated entity/config logic
  in favor of L3; drops its parallel server startup in favor of the standard server +
  backend selection. (The module today is 29 files largely because it had to stub
  every API; with capabilities + defaults it should be a fraction of that.)

**`legend-sdlc-server` (L6) — what remains:**

- JAX-RS resources, Dropwizard application, configuration, pac4j auth, Guice modules,
  depot client, error handling. Resources are rewritten only in their injection: they
  consume the `Backend` (L4) instead of concrete implementations. Routes and payloads
  unchanged.
- Returns `404`/`501`-style responses derived from the capability model where a
  deployment's backend lacks an optional capability (replacing today's ad-hoc
  `UnsupportedOperationException`s from stub implementations).

**`legend-sdlc-local` — new; the out-of-server consumer:**

See §4.

### 3.4 Optional capabilities

Not every backend has merge requests, CI pipelines, release tags, or patch branches.
Today this surfaces as stub classes throwing `UnsupportedOperationException`
(`legend-sdlc-server-fs` is full of them). Instead:

- L4 defines a small capability enumeration (e.g. `REVIEWS`, `WORKFLOWS`, `VERSIONS`,
  `PATCHES`, `BUILDS`, `BACKUP`, `ISSUES`, plus finer flags where existing APIs imply
  them, e.g. user-vs-group workspaces).
- `Backend.getCapabilities()` reports what is supported; `Backend.getReviewApi()` etc.
  throw a single well-defined `UnsupportedCapabilityException` for absent ones.
- The server maps absent capabilities to consistent HTTP responses, and can expose a
  capabilities endpoint so Studio (eventually) can adapt its UI rather than discovering
  unsupported features by error.
- The TCK verifies that declared capabilities work and undeclared ones fail in the
  defined way.

This is what makes "minimize what a backend implements" real: a minimal backend is
storage + project/workspace lifecycle + `getCapabilities() == {}`, and it is already a
useful deployment (browse, edit, configure, resolve dependencies, compare).

### 3.5 Backend selection

Replace inheritance-based assembly (`BaseLegendSDLCServer` → GitLab bundles) with
configuration + `ServiceLoader`:

```java
public interface BackendFactory
{
    String getType();                                  // e.g. "gitlab", "filesystem"
    Class<? extends BackendConfiguration> getConfigurationClass();
    Backend build(BackendConfiguration config, BackendEnvironment environment);
}
```

- Registered via `META-INF/services`; the server config gains a polymorphic
  `backend:` section (Jackson subtype resolution by `type`, the same pattern the
  Legend stack already uses for store extensions):

  ```yaml
  backend:
    type: gitlab
    gitlab:
      ...existing GitLab configuration...
  ```

- `BackendEnvironment` is the server-provided context a backend may need: the
  per-request user identity/credentials, object mapper, metrics. This is the one
  genuinely tricky seam: GitLab's implementation is session-scoped (per-user OAuth via
  `GitLabUserContext`). The SPI must therefore distinguish the *backend* (deployment
  -scoped, built once) from the *backend session* (request-scoped view bound to an
  authenticated user). Concretely: `Backend.forUser(UserContext)` (or the factory
  produces request-scoped API objects, as Guice does today — the design point is that
  the SPI owns this contract, not Guice). Auth *protocols* (pac4j filters, cookies,
  tokens) stay in the server; auth *material* (the resulting identity/credential)
  crosses the SPI as data.
- The single `legend-sdlc-server` distribution can bundle any set of backend jars;
  deployment chooses by configuration. One main class, no per-backend server modules.

## 4. Local / Embedded Usage (`legend-sdlc-local`)

The second headline goal: use SDLC functionality on a local working copy, with no
server, including when the Legend model is part of a larger non-Legend project.

### 4.1 What it is

A small module providing:

- **`LocalProjectFileAccessProvider`** (actually in L1 or here): `FileAccessContext` /
  `FileModificationContext` over a directory tree. No revisions (`RevisionAccessContext`
  unsupported or backed by JGit later); modifications write files directly. This is
  *not* a backend in the L4 sense — no projects, no workspaces — it plugs in at L1,
  which is exactly why L3 functionality must depend only on L1/L2.
- **`LocalModel` (working-copy façade)**: discover and open a Legend model rooted at a
  directory; read entities; **edit entities** (create/update/delete via the L3 entity
  modification logic, written straight to the source files in the correct source
  directory and serialization format); read and **update project configuration**
  (add/remove dependencies, change structure version) via the L3 updater.
- A thin CLI veneer is optional and deferred; the API is the deliverable.

### 4.2 Model-in-a-larger-project

Key design decision: **rooting is a storage-layer concern.** `FileAccessContext` paths
are project-relative; therefore a Legend model living at `analytics/model/` inside a
larger repository is handled by a *rooted* file access context (a decorator that
prefixes/strips the subpath). Everything above L1 — structure, entities, configuration
— is oblivious. This gives us:

- One repo containing one or more Legend models, each at its own root, each opened as
  its own `LocalModel`.
- Discovery helper: scan a tree for `project.json` files (with sensible pruning) to
  enumerate the models in a checkout.
- Non-Legend content is simply outside the root (or inside it but outside entity
  source directories, which L2 already tolerates).

This also benefits backends: nothing prevents a future backend from mapping a "project"
to a subdirectory of a repository rather than a whole repository, using the same rooted
context — a frequently requested monorepo pattern, and it falls out of the layering
rather than requiring new machinery. (Making the *GitLab* backend support this is out
of scope; the point is the layers do not preclude it.)

### 4.3 Relationship to existing pieces

- `EntityLoader` (read-only, directories/jars) remains for consumers that just need
  entities; `LocalModel` is the structure-aware, writable superset. `EntityLoader`
  stays untouched for compatibility.
- The EMIT project extractor need (per the extraction doc's motivation) is satisfied
  by L2 alone; `legend-sdlc-local` is the fuller answer for tooling that also edits.

## 5. Compatibility Strategy

- **REST API**: unchanged routes, payloads, and semantics for GitLab deployments.
  The capability model only changes responses for endpoints that today fail anyway.
- **Java packages**: extracted classes leave `org.finos.legend.sdlc.server.*` for
  non-server packages (`org.finos.legend.sdlc.projectfiles`, `…sdlc.structure`,
  `…sdlc.core`, `…sdlc.backend.*`). Unlike the extraction doc (which proposed keeping
  the `server.*` package in the new modules), this plan accepts a source-incompatible
  rename **with a deprecation bridge**: the old `server.*` names remain for one release
  cycle as deprecated subclasses/aliases in a `legend-sdlc-server-compat` shim (or in
  the server module itself), then are removed. Rationale: the `server.*` package
  permanently lying about its location is worse than a one-time migration, and split
  packages across modules invite JPMS and shading trouble later. This is the main
  deliberate divergence from the earlier doc.
- **Maven coordinates**: existing modules keep their artifactIds; new modules are
  additive; `legend-sdlc-server-fs` is renamed (with relocation POM) to
  `legend-sdlc-backend-fs`.
- **Downstream consumers** (Studio server config, internal deployments): a GitLab
  deployment's only required change is moving its GitLab config under the new
  polymorphic `backend:` key; a legacy-config adapter can keep even that working for
  a transition release.

## 6. Phased Plan

Each phase is independently releasable and keeps the full build green. Phases 1–2 are
the existing extraction doc, restated; details live there.

**Phase 1 — Foundations (mostly done).**
Complete `legend-sdlc-shared`; de-JAX-RS `LegendSDLCServerException` (rename to
`LegendSDLCException` in its new home, deprecated subclass left behind), move it down.

**Phase 2 — Project structure extraction (= extraction doc Phases 2–4, amended).**
Split `ProjectStructure` read/write; create `legend-sdlc-project-structure` (L2).
Amendment per §3.3: the write-side `ProjectStructureUpdater` is extracted as a
standalone class with no server imports, so it can move to L3 in Phase 3 (it may sit
in the server temporarily; it must not *bind* to it).

**Phase 3 — SDLC core (L3).**
Create `legend-sdlc-core`. Factor the duplicated entity access/modification logic out
of `GitLabEntityApi`/`FileSystemEntityApi` into core implementations; move
`DependenciesApiImpl`, comparison walking, and `ProjectStructureUpdater` in. GitLab
and FS api classes delegate to core (they shrink but do not move yet). This phase has
the highest regression risk and the highest payoff; it is pure refactoring with the
existing test suites as the net, and it is where the TCK should begin to grow
(initially run against GitLab-mocked and FS backends in-repo).

**Phase 4 — Backend SPI (L4).**
Move `domain/api/**` to `legend-sdlc-backend-api`; introduce `Backend`,
`BackendFactory`, `BackendEnvironment`/session contract, capability model,
`AbstractBackend` defaults wired to L3. Server resources switch to consuming
`Backend`. Server config gains the polymorphic `backend:` section with a
GitLab-only registration; `BaseLegendSDLCServer`'s GitLab hard-wiring is removed.

**Phase 5 — Backend extraction (L5).**
Move `server/gitlab/**` to `legend-sdlc-backend-gitlab` (GitLab4J leaves the server's
dependency tree). Refit `legend-sdlc-server-fs` as `legend-sdlc-backend-fs` on the
defaults + capabilities; delete its parallel server. Add
`legend-sdlc-backend-inmemory` and make the TCK (`legend-sdlc-backend-test-suite`) a
published artifact run by all three backends in CI.

**Phase 6 — Local tooling.**
`LocalProjectFileAccessProvider` (rooted contexts, discovery), `LocalModel` editing
façade in `legend-sdlc-local`. Validate the embedded scenario end-to-end: a test
repository containing non-Legend content plus two models; open, edit entities, update
configuration, verify files.

Sequencing notes: Phases 1–3 do not change module boundaries seen by deployments and
can proceed immediately. Phase 4 is the API-shape commitment and deserves a design
review on the SPI details (especially the session/auth contract and capability set)
before code. Phases 5 and 6 are independent of each other once 4 lands.

## 7. Risks and Open Questions

| Risk / question | Notes / mitigation |
|---|---|
| **The `SourceSpecification` split (L1 vs L4) may fight the codebase.** Workspace/patch types are threaded through everything. | The phase plan permits the interim "move as-is, split later" (§3.3). The split is the right end state but is not load-bearing for Phases 2–5. |
| **Session/auth contract is the hardest SPI surface.** GitLab needs per-user OAuth state with re-auth flows; another backend may use a service account; local use has no auth at all. | Treat as the centerpiece of the Phase 4 design review. The litmus test: the FS backend's session object should be trivial, and pac4j must not appear below L6. |
| **Behavioral drift between GitLab and generic implementations during Phase 3.** GitLab's entity/config code has accumulated GitLab-shaped quirks (pagination, retries, caching via `CachingFileAccessContext`). | Factor *behavior* into core but keep backend override points; characterization tests before moving; TCK asserts the contract both must satisfy. |
| **Patch/version/review semantics are Git-flavored even as "generic" APIs.** E.g. patch release branches, MR-based reviews. | Acceptable: the L4 APIs describe SDLC concepts (line of development, proposed change, release); Git-less backends either map them or omit the capability. Document each API's contract in backend-neutral terms as part of Phase 4. |
| **Package rename breaks external code.** | Deprecation bridge for one release (§5); announce; the alternative (frozen `server.*` names forever) is worse. |
| **Two plans diverging** (this doc vs `project-structure-extraction.md`). | The extraction doc stays as the detailed treatment of Phases 1–2, with a banner pointing here; its two superseded decisions (keeping `server.*` packages; leaving the updater in the server) are noted in §3.3/§5. |
| **Scope creep toward monorepo-projects in backends.** | Explicitly deferred (§4.2): the architecture allows it; no backend implements it in this plan. |
| **Guice request-scoping is load-bearing in subtle ways** (e.g. `UserContext`, lazy GitLab clients). | Phase 4 keeps Guice at L6 but moves the *contract* into the SPI; integration tests on the GitLab backend with real auth flows before/after. |

## 8. End-state Dependency Graph (SDLC modules only)

```
legend-sdlc-shared ─────────────┐
legend-sdlc-model ──────────────┤
legend-sdlc-entity-serialization┤  (L0: no SDLC deps except model←shared as today)
                                │
legend-sdlc-project-files ──────┤  L1: shared, model
legend-sdlc-project-structure ──┤  L2: + project-files, entity-serialization
legend-sdlc-core ───────────────┤  L3: + project-structure
legend-sdlc-backend-api ────────┤  L4: + core
legend-sdlc-backend-test-suite ─┤  L4: + backend-api (test-jar style)
                                │
legend-sdlc-backend-gitlab ─────┤  L5: + backend-api (+ gitlab4j)
legend-sdlc-backend-fs ─────────┤  L5: + backend-api (+ jgit)
legend-sdlc-backend-inmemory ───┤  L5: + backend-api
                                │
legend-sdlc-server ─────────────┤  L6: + backend-api (+ dropwizard/guice/pac4j);
                                │      backends arrive on the runtime classpath
legend-sdlc-local ──────────────┘  L0–L3 only
```

The generation modules and Maven plugins keep their current positions (L0-adjacent
consumers) and are untouched except where they can later choose to use
`legend-sdlc-local` conveniences.
