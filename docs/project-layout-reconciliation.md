# Build-and-Reconcile: Declarative Project Structures and Extensions

## 1. Problem

Project structures and extensions implement the **write-side** of configuration updates
imperatively: each one computes, by hand, the *delta* between the old layout and the new
one, emitting `ProjectFileOperation`s (`addFile` / `modifyFile` / `moveFile` /
`deleteFile`) against the current file state.

Concretely, the update flow (`ProjectStructure.updateProjectConfiguration` today; moving
to the L3 updater under the re-architecture) is a sequence of hand-written diffs:

1. the updater special-cases the `project.json` write (add vs. modify);
2. it special-cases entity move/re-serialization when source directories change between
   versions — deserialize each entity from the old directory, find a new home, emit a
   `moveFile`/`modifyFile`;
3. `newStructure.collectUpdateProjectConfigurationOperations(oldStructure, fileAccessContext,
   consumer)` emits the structure's own delta — e.g. `MultiModuleMavenProjectStructure`
   walks old vs. new module sets with `moveOrAddOrModifyModuleFile` and deletes the files
   of modules present in the old structure but absent from the new;
4. each `ProjectStructureExtension.collectUpdateProjectConfigurationOperations(oldConfig,
   newConfig, fileAccessContext, consumer)` does the same for its deployment's files.

This design has structural problems:

- **Transition reasoning multiplies.** Correctness requires each structure version to
  handle every reachable old state: an update from *any* older version, plus every
  same-version configuration delta (dependency changes, group/artifact id changes, module
  options, …). Each transition is bespoke diff logic, and only the transitions someone
  thought to test are known to work.
- **Deletion by memory.** A file is removed only if some transition code remembers it
  should be. Stale files from long-gone versions accumulate silently, and a managed file
  the user hand-edited is repaired only if an unrelated diff happens to rewrite it. There
  is no notion of "the canonical layout" to converge to.
- **Duplicated machinery.** Every structure and extension re-implements the same
  add-or-modify / move-or-add-or-modify / delete-stale patterns over `FileAccessContext`.
- **High authoring bar.** Writing a structure version or extension means understanding the
  operation machinery and the update choreography — not just describing what the project
  should look like.

### Goal

A project structure (and each extension) should **build the complete desired layout from
scratch**, as a pure function of the new `ProjectConfiguration` and the project's current
entities (extensions may additionally declare specific existing files as inputs). Generic
machinery then **compares the desired layout with the current files and synthesizes the
`ProjectFileOperation`s** — the only code that produces operations at all.

### Non-goals

- Changing `ProjectFileOperation` or the L1 storage SPI. Operations remain the currency
  between the updater and `FileModificationContext`; only their *producer* changes (one
  generic reconciler instead of every structure and extension).
- Changing the REST surface or `project.json` layout. The one new configuration option
  this plan needs (`additionalFiles`, §3) rides the mechanism of
  [`project-structure-configuration-options.md`](project-structure-configuration-options.md).
- Rewriting existing structure versions' semantics in place. Reconciliation semantics are
  **version-gated** (§5.3): existing projects keep today's behavior until they upgrade to
  a structure version authored under the new model.

## 2. Proposal: build, then reconcile

Invert the contract. Instead of "given the old state, emit operations", a structure
describes its end state:

```java
// L2 — the new write-side contract (shape, not final signature)
public interface ProjectLayout
{
    // the complete set of managed files: path -> content supplier (lazily evaluated)
}

// ProjectStructure / version factory (L2)
ProjectLayout buildLayout(ProjectConfiguration configuration, EntityAccess entities);

// ProjectStructureExtension (L2)
void contributeToLayout(ProjectConfiguration configuration, LayoutInputs inputs, LayoutBuilder builder);
```

Inputs are explicit and closed: the new configuration, the project's current entities
(read once by the updater, deserialized via the *old* structure's source directories —
the same read step 2 of §1 performs today), and, for extensions only, the current content
of **declared** input files (§5.2). `project.json` itself and the serialized entity files
are ordinary members of the layout — steps 1 and 2 of §1 stop being special cases.

A generic **reconciler** (L3, written once) compares desired layout to current files:

| Desired | Current | Operation |
|---|---|---|
| present | absent | `addFile` |
| present | present, different content | `modifyFile` |
| present | present, same content | *(none)* |
| absent | present | `deleteFile` — unless preserved (§3) |
| content matches a deleted path | — | `moveFile` (rename detection, review-friendly diffs) |

What this buys:

- **Transitions collapse.** No version-pair reasoning: build the new layout, diff. An
  update from V11 and an update from V13 to the same target are the same code path.
- **Update ≡ create.** Creating a project and updating an existing one to the same
  configuration produce byte-identical trees — a strong, testable invariant. (Creation is
  already de facto build-from-scratch: the no-old-structure branch of
  `collectUpdateProjectConfigurationOperations` adds every file.)
- **Idempotence.** Reconciling an already-correct project yields zero operations —
  another cheap, universal test.
- **Self-healing.** Stale files from old versions and hand-edits to managed files
  converge to the canonical layout on the next update, by construction rather than by
  someone remembering a transition.
- **Low authoring bar.** A structure version or extension is a function from
  configuration + entities (+ declared inputs) to a file tree — no operations, no
  `FileAccessContext` walking, trivially golden-testable.

## 3. The deletion consequence — and "additional" files

The flip side of self-healing: **any file the structure and extensions do not produce is,
by definition, not in the desired layout and will be deleted on update.** Today's
imperative model silently *tolerates* unknown files (nothing touches what nothing knows
about); reconciliation must be told what to leave alone.

| File category | In desired layout? | Fate under reconciliation |
|---|---|---|
| Structure-generated (POMs, CI-adjacent structure files) | yes | converged to canonical |
| Entity files | yes (serialized from the entities input) | converged |
| Extension-generated (CI config, `settings.xml`, …) | yes | converged |
| User-maintained files | **no** | **deleted — unless preserved** |

The mitigation is the sibling of `additionalModules`
([config-options §4.8](project-structure-configuration-options.md)): an
**`additionalFiles`** structure option — a `LIST<STRING>` of path patterns, declared by
reconciling structure versions via the config-options mechanism, empty by default. Paths
matching a pattern are opaque to the reconciler: never generated, never deleted; users
create and manage them. Related rules:

- Each module named in `additionalModules` implicitly preserves its whole directory —
  the module option *means* "user-owned zone"; users need not repeat it in
  `additionalFiles`.
- Because the layout is a pure function, **validation can build it**: a pattern that
  matches a path the layout produces is a contradiction (the reconciler cannot both write
  and ignore it) and is rejected at configuration-update time, alongside the other
  owner-supplied checks of config-options §4.4.
- Whether a small built-in exemption set should exist (e.g. `.gitignore`, `README.md`)
  or the default is strictly "nothing survives outside the layout and the patterns" is an
  open question (§9).

## 4. Relationship to the other plans — and the decision

**Decision: a separate companion plan, like
[`project-structure-configuration-options.md`](project-structure-configuration-options.md)
— dependent on the re-architecture and sequenced onto it, with two reserved seams (§8) so
the re-architecture does not entrench the contract this plan replaces.**

Why separate rather than folded into
[`re-architecture.md`](re-architecture.md):

1. **It is a behavior change.** The re-architecture's review premise is
   behavior-preserving re-layering with the existing tests as the net. Reconciliation
   deliberately changes update behavior — unknown files are deleted — which is the kind
   of user-visible, data-affecting semantics that needs its own design review and its own
   safety story (§5.3–5.4).
2. **It depends on the config-options plan.** `additionalFiles` is a declared,
   list-valued structure option; it needs that plan's schema, persistence, and REST
   surface. Folding this into the re-architecture would transitively drag the
   config-options non-goals in too.
3. **It delivers value independently** — simpler authoring, drift repair, golden-testable
   structures — whatever module boundaries the classes live in.

Why not orthogonal:

1. **It rewrites exactly the write-side contract the re-architecture relocates.** Phase 2
   extracts the write-side from `ProjectStructure`; Phase 3 moves the updater to L3 and
   pulls `ProjectStructureExtension` down to L2. If those phases enshrine the imperative
   `collectUpdateProjectConfigurationOperations` as the new public L2 SPI, this plan
   becomes a second SPI break. Hence seam R1 (§8): extract it as the *legacy* contract
   behind a single dispatch point.
2. **It strengthens the re-architecture's own goals.** A pure
   `configuration + entities → layout` function is the ideal L2 citizen: no server
   dependencies to leak, trivially exercised by the TCK and by `legend-sdlc-local`
   in-process (the IDE story), and the reconciler is precisely the kind of write-once
   generic logic L3 exists to hold.

## 5. Design details

### 5.1 Layout building

- **Deterministic and byte-stable.** The same inputs must produce the same bytes
  (stable POM serialization, sorted collections, no timestamps) — otherwise reconciliation
  churns. This is an authoring rule enforced by the idempotence test (§2).
- **Lazy content.** The layout maps paths to content *suppliers*; the reconciler compares
  hashes/bytes only for paths present on both sides and never materializes unchanged
  content it can avoid.
- **Scope = the rooted file access context.** The reconciler operates over the project
  root as L1 presents it. For a model embedded in a larger repository (re-architecture
  §4.2), the rooted context *is* the boundary — host-project files are outside it and
  untouchable by construction. `ProjectType.EMBEDDED` layouts contain only `project.json`
  and entity files (no structure scaffolding, no extensions), preserving today's
  semantics under the new mechanism.

### 5.2 Extension inputs

Extensions may need existing content — e.g. seeding a file from a template only when
absent, or deriving CI configuration from a descriptor the user maintains. They get it by
**declaring input paths**, and the updater supplies those files' current content in
`LayoutInputs`. Extensions do not receive a `FileAccessContext`: the layout stays a pure
function of *(configuration, entities, declared inputs)*, so its domain is explicit,
its evaluation is possible in-process with no server (the `legend-sdlc-local` / IDE
story), and "what does this extension depend on?" is answerable by inspection.

Note the boundary this draws: a file is either **canonical** (the extension owns it and
regenerates it) or **additional** (the user owns it and the reconciler ignores it).
"Mostly generated but preserves user edits" is not a supported state — that pattern
should become a declared input (user-owned source, extension-owned output at a different
path) rather than an in-place merge.

### 5.3 Version-gated semantics (migration)

Reconciliation changes what an update does, so it must not switch on under existing
projects' feet:

- The L3 updater dispatches at a single point: structures that implement `buildLayout`
  get the reconciler; structures that do not keep the legacy imperative path via an
  adapter. Existing versions (V0–V13) stay imperative and frozen.
- The **first reconciling structure version** (V14, say) is authored declaratively from
  the start. A project meets reconciliation semantics only by upgrading its structure
  version — already understood to be a layout-rewriting, reviewed event.
- Extensions follow the same gate: reconciling structure versions pair with
  `contributeToLayout` extensions; legacy extensions keep the old SPI against legacy
  versions (§7 Phase 4).

### 5.4 Safety of deletions

Operations land in a workspace and reach the project through the normal review flow —
deletions are visible in the review diff before anything merges. On top of that
backstop: the updater should summarize planned deletions distinctly (clients can render
"this update removes N files not part of the managed layout"), and the `additionalFiles`
validation (§3) runs *before* operations are computed, so a preservation mistake is a
rejected configuration rather than a merged deletion.

## 6. Compatibility

- **REST and `project.json`**: unchanged by this plan (`additionalFiles` arrives through
  the config-options mechanism, which owns those surfaces).
- **Existing projects**: no behavior change until a project upgrades to a reconciling
  structure version; the upgrade itself goes through review like any version upgrade.
- **Java SPI**: `collectUpdateProjectConfigurationOperations` (structure and extension)
  is retained as the deprecated legacy contract for as long as legacy versions exist;
  new versions/extensions implement only the layout contract.

## 7. Phased plan

**Phase 0 — prerequisites.** Re-architecture Phases 2–3 complete (structure at L2,
updater at L3) honoring seams R1–R2 (§8); config-options Phases 1–3 for the option
machinery `additionalFiles` needs.

**Phase 1 — layout model + reconciler (no consumer).** `ProjectLayout`, `LayoutBuilder`,
and the reconciler in L3, tested standalone: diff correctness, move detection,
idempotence, determinism harness. Nothing calls it yet.

**Phase 2 — parity proof.** Implement `buildLayout` for the newest existing version
*behind the scenes* and assert on a test corpus that reconciler output matches the legacy
path (creation should match exactly; updates modulo intentional stale-file deletions,
which are enumerated and reviewed). Switch the *creation* path to the layout — creation
is already build-from-scratch, so this is behavior-preserving and retires the first slice
of imperative code.

**Phase 3 — first reconciling version.** Cut the next structure version (V14) authored
declaratively, declaring `additionalFiles` (and the §4.8 module options) among its
configuration properties; land the updater dispatch (§5.3) and the deletion summary
(§5.4). Upgrade tests: V13→V14 with user files present, with and without
`additionalFiles` patterns.

**Phase 4 — extensions.** `contributeToLayout` with declared inputs on the L2 extension
interface; adapter keeps legacy extensions working against legacy versions; one concrete
extension (the GitLab CI extension) ported end-to-end. Deprecate the imperative SPI.

## 8. Reserved seams for the re-architecture

| Seam | Where | Why |
|---|---|---|
| **R1 — extract the write-side behind one dispatch point, as the *legacy* contract** | Re-architecture Phases 2–3 (`ProjectStructureUpdater` → L3; `ProjectStructureExtension` → L2) | The updater should reach structure/extension write logic through a single choke point so a declarative path can be added beside the imperative one without touching callers. Do not design new public SPI surface *around* `collectUpdateProjectConfigurationOperations`; it is scaffolding this plan removes. |
| **R2 — TCK includes layout invariants** | Re-architecture Phases 3–5 (`legend-sdlc-backend-test-suite`) | Update≡create and idempotence are backend-independent contracts. Designing the TCK to express them from the start lets reconciling versions be certified by the same suite, instead of bolting a second harness on later. |

## 9. Risks and open questions

| Risk / question | Notes / mitigation |
|---|---|
| **Deletion of user files on first reconciling update.** Projects accumulated files under the tolerant imperative model; the V13→V14 upgrade is where they would vanish. | Version gating makes it opt-in; review shows every deletion before merge; the deletion summary (§5.4) makes them conspicuous; `additionalFiles` set *in the same update* as the version upgrade preserves known user files. Consider having Studio prompt with the planned-deletion list at upgrade time. |
| **"Preserve user edits inside a managed file" has no home.** Some deployments may rely on hand-tuned managed files surviving. | Deliberate (§5.2): canonical or additional, nothing between. The escape hatches are declared inputs (derive output from a user-owned source file) or making the file additional (user owns it entirely). Audit known deployments' extensions for in-place-merge patterns before Phase 4. |
| **Performance: full build + diff per update** vs. today's targeted operations; entity files make the layout large. | Layouts are small (hundreds of files); content is lazily supplied and compared by hash; `CachingFileAccessContext` already fronts reads. Entity content need only be produced when serialization or source directories change — the reconciler can treat "same serializer, same directory" entity files as fixed points. Measure in Phase 2's parity harness. |
| **Non-deterministic generation causes churn.** Any timestamp, unstable ordering, or environment-dependent bytes in generated content makes every update dirty. | Determinism is an authoring rule enforced by the Phase 1 idempotence tests; the parity corpus doubles as the determinism corpus. |
| **Rename detection quality.** Naïve diffs would turn entity moves (version upgrades) into delete+add pairs, degrading review readability vs. today's explicit `moveFile`s. | Reconciler detects moves by content equality (hash) before emitting delete/add; entity moves are content-identical or near-identical re-serializations, the easy case. |
| **Default exemption set.** Should `.gitignore`, `README.md`, or dot-files survive by default, or is everything outside the layout and `additionalFiles` fair game? | Open. Leaning: a minimal, documented, version-declared default set (a version can declare built-in preserved patterns exactly like structure options), so the rule stays "the version declares it", not "the reconciler hard-codes it." |
| **Pattern semantics for `additionalFiles`.** Glob dialect, directory-prefix vs. full glob, case sensitivity. | Decide with the config-options validation work; keep it minimal (directory prefixes + `*` globs) and validate patterns at update time (§3). |
