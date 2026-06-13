# Version- and Extension-Scoped Project Configuration Options

## 1. Problem

Some project configuration is meaningful only for a particular **project structure
version**, or only for a particular **project structure extension version** — not for
projects in general. Today such configuration is modeled as flat, top-level fields on the
version-independent `ProjectConfiguration`, which does not scale and leaks
version/deployment concerns into the shared model.

The motivating example is already in the tree. `ProjectConfiguration` carries:

```java
default Boolean getProduceShadedServiceJar() { return null; }
default Boolean getRunDependencyTests()      { return null; }
```

`produceShadedServiceJar` only makes sense for the multi-module Maven structure that has a
`service_execution` module (it gates a Maven shade plugin); it is meaningless for a
structure version that has no such module. Yet it sits on the top-level model, and adding
it required touching **four** places in lockstep:

| Layer | File | What had to change |
|---|---|---|
| L0 model | `ProjectConfiguration` | new `getProduceShadedServiceJar()` default |
| project.json (de)serialization | `SimpleProjectConfiguration` | new field, ctor arg, getter/setter, `@JsonProperty` |
| update logic | `ProjectConfigurationUpdater` | new field, `with…`/`set…`, merge in `update()` |
| REST | `UpdateProjectConfigurationCommand` | new field, `@JsonProperty`, wire into updater |

Every new knob repeats this four-place edit. Worse, there is **no schema or discovery**:
a client (Studio, or an IDE plugin) cannot ask "which options apply to structure version
N?" — the set is hard-coded into the client. And the field is half-wired: `produceShaded­
ServiceJar` exists in the model and updater but the V13 factory still adds the shade plugin
unconditionally, so the flag does nothing yet. It is a top-level field that should have been
a property of one structure version from the start.

The same need exists one level out, for **extensions**. Project structure extensions
(`ProjectStructureExtension`, keyed by `(structureVersion, extensionVersion)`) are the
deployment-specific hook — e.g. a GitLab deployment's extension that injects CI files. A
deployment will want options of its own: a GitLab extension might expose which runner tags
the generated pipeline targets. Those options are even less appropriate as top-level model
fields: they are specific to one deployment's extension, invisible to every other.

### Goal

Let a project structure version, and a project structure extension version, **declare**
typed configuration options; **persist** their values in a namespaced section of
`project.json`; **validate** values against the declared schema; **expose** the schema for
discovery so clients render the right form; and **consume** values during structure
build/update — all while the version-independent `ProjectConfiguration` and the generic
SDLC core stay ignorant of any specific option.

### Non-goals

- Removing the existing top-level getters outright. `getProduceShadedServiceJar()` /
  `getRunDependencyTests()` become deprecated read bridges over the new mechanism (§6), not
  hard breaks.
- A general user-defined settings system. Options are declared by **code** (the version
  factory or the extension), not by end users; this is typed metadata, not a free-form map
  that anything can write to.
- Per-entity or per-module configuration. The unit remains the project (`project.json`).

## 2. Relationship to the Re-architecture — and the decision

This is the question raised alongside the re-architecture
([`re-architecture.md`](re-architecture.md)): fold this in, or keep it separate?

**Decision: keep it as this separate plan, but treat it as *dependent on* — not orthogonal
to — the re-architecture. It is sequenced onto the new layers, and the re-architecture
reserves three small seams (§8) so this lands additively rather than as a re-migration.**

Why separate rather than merged:

1. **It crosses two of the re-architecture's load-bearing non-goals.** That plan explicitly
   does *not* change the REST surface or the on-disk `project.json` layout; those non-goals
   are what let it be reviewed as a behavior-preserving re-layering with the existing test
   suite as the safety net. This work **does** change both: a new namespaced block in
   `project.json` and a new discovery endpoint (plus additive request fields). Merging it in
   would forfeit "pure refactor, existing tests prove it" and entangle two different risk
   profiles.
2. **Different audience and review gate.** The re-architecture is an internal platform
   refactor. This is a user-visible feature touching Studio (form rendering) and IDE plugins
   (§7 of the re-architecture's local story). It deserves its own design review with the
   client teams.
3. **It delivers value on its own** and does not *require* the modularization to function —
   whereas the modularization does not require it.

Why not orthogonal:

1. **It edits exactly the classes the re-architecture relocates** — `ProjectConfiguration`
   (L0), `ProjectStructure` + version factories + `ProjectStructureExtension` (→ L2),
   `ProjectConfigurationUpdater` (→ L3), and the REST command (L6). Doing it *before* those
   move means doing it twice; doing it *after* means designing against the final boundaries.
2. **Extension options entangle the backend boundary.** In the re-architecture, deployment
   ≈ backend (L5), and structure *extensions* are the deployment hook. A GitLab extension's
   runner options are conceptually owned by the GitLab backend. This work needs that
   extension↔backend boundary settled first (§5), or it will be designed against a seam
   that is about to move.
3. **It is the same shape of problem as the capability model.** The re-architecture already
   introduces "declare what is supported, expose it for discovery, enforce it." Option
   schemas are "declare what is configurable, expose it for discovery, validate against it."
   They should share machinery, not invent two parallel discovery endpoints.

**Therefore: schedule this after re-architecture Phases 1–4** (foundations, structure
extraction → L2, core → L3, backend SPI + capability model → L4), and have those phases
honor the reserved seams in §8.

## 3. Current State

- **`ProjectConfiguration`** (`legend-sdlc-model`, L0) — the version-independent model.
  Has accreted `getRunDependencyTests()` and `getProduceShadedServiceJar()` as `default`
  getters returning `null`.
- **`SimpleProjectConfiguration`** (server today; the `project.json` (de)serializer) —
  flat `@JsonProperty` fields, one per option.
- **`ProjectConfigurationUpdater`** (server `domain/api/project`; → L3/L4) — merges an
  updater's set fields onto an existing config in `update()`.
- **`ProjectStructureVersionFactory`** + `ProjectStructureV0/V11/V12/V13Factory`
  (server `project`; → L2) — build POMs/structure from a `ProjectConfiguration`; this is
  where option values are *consumed* (e.g. V13's `configureServiceExecutionModule` adds the
  shade plugin).
- **`ProjectStructureExtension`** / `ProjectStructureExtensionProvider`
  (server `project/extension`) — extensions keyed by `(structureVersion, extensionVersion)`;
  contribute file operations via `collectUpdateProjectConfigurationOperations(oldConfig,
  newConfig, fileAccessContext, consumer)`. They carry **no typed options** today.
- **Precedent — `GenerationProperty`** (`legend-sdlc-model`): the artifact-generation
  subsystem already declares typed option metadata: `name`, `description`, `type`
  (`GenerationItemType`), `items`, `defaultValue`, `required`. We mirror this rather than
  invent a new shape.

## 4. Design

### 4.1 Option schema (declaration)

Introduce a typed option descriptor in `legend-sdlc-model` (L0), modeled on
`GenerationProperty`:

```java
public interface ConfigurationProperty
{
    String getName();
    String getDescription();
    ConfigurationPropertyType getType();   // BOOLEAN, STRING, INTEGER, ENUM, LIST<…>
    Object getDefaultValue();
    boolean getRequired();
    List<String> getAllowedValues();       // for ENUM; null/empty otherwise
}
```

(Reuse `GenerationItemType`/`GenerationPropertyItem` if the type lattice already fits;
otherwise a small parallel enum keeps the two subsystems decoupled.)

A **structure version** declares its options. Add to the L2 factory abstraction:

```java
// ProjectStructureVersionFactory (L2)
default List<ConfigurationProperty> getConfigurationProperties() { return Collections.emptyList(); }
```

A **structure extension** declares its options. Add to the extension SPI:

```java
// ProjectStructureExtension
default List<ConfigurationProperty> getConfigurationProperties() { return Collections.emptyList(); }
```

Both default to empty, so existing versions/extensions are unchanged until they opt in.

### 4.2 Option values (persistence)

Add two **namespaced, schema-validated** value bags to the L0 `ProjectConfiguration`,
stored in `project.json`:

```java
// ProjectConfiguration (L0)
default Map<String, Object> getStructureConfiguration() { return Collections.emptyMap(); }
default Map<String, Object> getExtensionConfiguration() { return Collections.emptyMap(); }
```

```jsonc
// project.json
{
  "projectStructureVersion": { "version": 13, "extensionVersion": 2 },
  "groupId": "...", "artifactId": "...",
  "structureConfiguration": { "produceShadedServiceJar": false },
  "extensionConfiguration": { "gitlabRunnerTags": ["legend", "docker"] }
}
```

The generic core treats these as opaque typed values; **only the owning version/extension
interprets a key.** The active version is already recorded in `projectStructureVersion`, so
the bags need no further qualifier — the schema to interpret them against is implied by the
config itself. `SimpleProjectConfiguration` gains two `Map` fields instead of one new field
per option; the four-place edit (§1) collapses to "declare the property on the version."

### 4.3 Who declares what

| Option lives on | Declared by | Stored under | Read by |
|---|---|---|---|
| A structure version | the version's factory (L2) | `structureConfiguration` | the version factory at build/update |
| A deployment's extension | the `ProjectStructureExtension` (L4/L5, see §5) | `extensionConfiguration` | the extension's `collectUpdate…Operations` |

### 4.4 Validation

On config create/update, validate each bag against the active version's / extension's
declared `ConfigurationProperty` list:

- unknown key → reject;
- missing required key with no default → reject;
- value not assignable to the declared type / not in `allowedValues` → reject.

Validation lives where the schema lives (L2 for structure options; with the extension for
extension options) and is invoked from the L3 updater's validate step — the same place that
already validates group/artifact ids, platform configs, and metamodel dependencies. No
generic code hard-codes any option name.

### 4.5 Discovery

Clients must learn the schema to render a form. Expose it through the **same discovery
mechanism the re-architecture builds for capabilities** rather than a one-off endpoint —
e.g. extend the structure/capabilities describe call so that, given a structure version
(and optionally an extension version), it returns the `ConfigurationProperty` list. For IDE
plugins (the re-architecture's `legend-sdlc-local` consumer) the same schema is available
**in-process** off the L2 factory and the extension — no server required, which is exactly
why the schema must live in L2/L4, not in a server resource.

### 4.6 Consumption

Version factories and extensions read values from the bags instead of from bespoke
top-level getters. Example, the motivating case:

```java
// ProjectStructureV13Factory.configureServiceExecutionModule(...)
configuration.addPlugin(this.legendServiceExecutionGenerationPluginMavenHelper.getPlugin(this));
if (booleanOption(projectConfiguration, "produceShadedServiceJar", true))   // default true = today's behavior
{
    configuration.addPlugin(this.legendServiceExecutionGenerationPluginMavenHelper.getShadePlugin());
}
```

where `booleanOption(config, name, default)` reads `getStructureConfiguration()` with the
declared default — a small typed accessor on the structure base class.

### 4.7 Migrating the existing flags

`produceShadedServiceJar` and `runDependencyTests` become **declared options of their owning
structure version(s)**, stored in `structureConfiguration`. To stay compatible:

- The top-level getters `getProduceShadedServiceJar()` / `getRunDependencyTests()` remain as
  `@Deprecated` bridges that read the value out of `structureConfiguration` (so old Java
  callers still compile and behave).
- `SimpleProjectConfiguration` deserialization accepts the **old** top-level
  `project.json` keys and folds them into `structureConfiguration` on read (read-compat for
  existing repositories).
- On the next configuration write, the file is normalized to the namespaced form; a
  one-time, behavior-preserving migration.
- The REST `UpdateProjectConfigurationCommand` keeps accepting the old fields (deprecated)
  and also accepts a generic `structureConfiguration` map; both feed the updater.

## 5. The extension ↔ backend relationship

Extension-scoped options are where this plan and the re-architecture must agree, so resolve
it explicitly as part of the re-architecture's Phase 4/5 rather than here:

- Today `ProjectStructureExtension` and its provider live server-side
  (`server/project/extension`) and are wired from Dropwizard config
  (`ProjectStructureConfiguration`).
- In the re-architecture, deployment-specific behavior is the **backend's** province (L5),
  and the server selects a backend by configuration. A GitLab deployment's extension — and
  thus its runner options — is naturally **provided by the GitLab backend**.
- Recommended boundary: the *option schema* and the *file-operation contribution* of an
  extension stay in the structure/extension layer (so `legend-sdlc-local` and the TCK can
  exercise them without a backend), while the *binding of which extensions a deployment
  offers* is part of backend/server configuration. Concretely: `ProjectStructureExtension`
  (schema + `collectUpdate…Operations`) is L2/L4 SPI; a backend (or server config) supplies
  the `ProjectStructureExtensionProvider`. This keeps extension options testable in
  isolation while letting a deployment own which ones exist and what their values may be.

This is exactly why §2 sequences this work *after* the backend SPI lands: the home of an
extension's option schema depends on where the extension/backend boundary is drawn.

## 6. Compatibility

- **`project.json`**: read-compat for old files (old top-level keys folded into the bag,
  §4.7); new files use the namespaced form. No project becomes unreadable.
- **REST**: additive — old request fields still accepted (deprecated); new
  `structureConfiguration`/`extensionConfiguration` maps and a discovery response are added.
  No route or existing payload changes meaning.
- **Java API**: top-level getters deprecated, not removed, for one release cycle.
- **Defaults preserve behavior**: each migrated option's default reproduces today's
  hard-coded behavior (e.g. shade jar default `true`), so unconfigured projects build
  identically.

## 7. Phased Plan

Each phase keeps the build green; phases 1–2 are pure platform with no client surface.

**Phase 0 — prerequisite.** Re-architecture Phases 1–4 complete (config model in L0,
structure in L2, core/updater in L3, backend SPI + capability/discovery in L4), honoring the
seams in §8.

**Phase 1 — schema + persistence (no behavior change).** Add `ConfigurationProperty` (L0),
the two namespaced bags on `ProjectConfiguration`/`SimpleProjectConfiguration`, the
`getConfigurationProperties()` SPI methods (default empty) on the version factory and the
extension. Wire read-compat for the old top-level keys. Nothing declares options yet; tests
prove `project.json` round-trips unchanged.

**Phase 2 — migrate the existing flags.** Declare `produceShadedServiceJar` (finishing its
wiring — gate the shade plugin on it) and `runDependencyTests` as options of their owning
version(s). Add validation. Deprecate the top-level getters/fields as bridges. Existing
tests + new round-trip/migration tests are the net.

**Phase 3 — discovery + client surface.** Extend the discovery mechanism to return option
schemas; teach the REST command to accept the generic maps. Studio renders options from the
schema. `legend-sdlc-local` exposes the schema in-process for IDE plugins.

**Phase 4 — extension options.** With the extension↔backend boundary settled (§5), let an
extension declare and consume options; prove with one concrete extension option end-to-end
(e.g. a sample GitLab runner-tags option behind the GitLab backend).

## 8. Reserved seams for the re-architecture

Three cheap accommodations the re-architecture should make *now* so this work is additive,
not a second migration of `project.json`:

| Seam | Where | Why |
|---|---|---|
| **S1 — introduce the namespaced bag when the config model/updater are touched** | L0 `ProjectConfiguration`, L3 updater | The refactor already rewrites these; adding `getStructureConfiguration()`/`getExtensionConfiguration()` (even if only the two existing booleans live there, behind their deprecated getters) avoids re-migrating `project.json` later. Do **not** entrench new flat booleans. |
| **S2 — reserve the option-schema method shape on the version/extension SPI** | L2 `ProjectStructureVersionFactory`, `ProjectStructureExtension` | Land `getConfigurationProperties()` defaulting empty so versions/extensions can opt in without an SPI break later. |
| **S3 — make capability/discovery extensible to option schemas** | L4 discovery endpoint (Phase 4 of re-architecture) | Design the "describe what this structure/extension supports" call so it can also carry option schemas, instead of bolting on a separate endpoint afterward. |

## 9. Risks and Open Questions

| Risk / question | Notes / mitigation |
|---|---|
| **Typed values in an opaque `Map<String,Object>`** lose compile-time safety for option authors. | Acceptable: the `ConfigurationProperty` schema + validation enforce types at the edges; typed accessors (`booleanOption(...)`) keep call sites clean. Mirrors how generation properties already work. |
| **Option lifetime across version upgrades.** When a project moves structure version, options that no longer apply must be dropped; carried-over ones validated against the new schema. | Slot into the existing config-update re-serialization (the same flow that moves/re-serializes entities on version change). Define policy: drop unknown options on upgrade, warn on dropped non-default values. |
| **Extension options vs. backend configuration overlap.** A GitLab runner option could be argued as either project config or deployment config. | Distinguish *per-project, persisted in `project.json`* (this plan) from *deployment-wide server/backend config* (the re-architecture's `backend:` section). Runner *tags for a project's pipeline* are the former; runner *infrastructure* is the latter. Document the test: does it belong in version control with the project? |
| **Two discovery endpoints** if §8/S3 is missed. | Land S3 during the re-architecture's Phase 4; otherwise this plan adds a parallel endpoint and the client integrates twice. |
| **Studio form rendering** is out of this repo. | Schema is designed to be client-agnostic (name/type/default/required/allowed-values is enough to render a generic form); coordinate the wire shape with Studio before Phase 3. |