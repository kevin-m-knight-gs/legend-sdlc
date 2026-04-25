# Extracting Project Structure from `legend-sdlc-server`

## 1. Motivation

The `ProjectStructure` class and its related types currently live in `legend-sdlc-server`.
This module depends on Dropwizard, Guice, GitLab4J, PAC4J, and other server infrastructure,
making it impossible to use project structure knowledge without pulling in the entire server.

This blocks the EMIT project extractor (see `legend-engine/docs/testing/emit-harvest.md`
§8.2), which needs to understand Studio project layout (entity source directories, file
conventions, dependency configuration) without running or depending on the server.

More broadly, decoupling project structure enables alternative SDLC backends and lightweight
tooling that needs to understand project layout.

## 2. Current State

### 2.1 Where project structure code lives today

All project structure code is in `legend-sdlc-server` under the package
`org.finos.legend.sdlc.server.project`:

| File | Size | Purpose |
|---|---|---|
| `ProjectStructure.java` | 1218 lines | Core class: entity source directories, file layout, configuration parsing, and project configuration updates |
| `ProjectStructureFactory.java` | | SPI factory for creating `ProjectStructure` by version |
| `ProjectStructureV0Factory.java` | | Version 0 implementation |
| `ProjectStructureV11Factory.java` | | Version 11 implementation |
| `ProjectStructureV12Factory.java` | | Version 12 implementation |
| `ProjectStructureV13Factory.java` | | Version 13 implementation |
| `ProjectStructurePlatformExtensions.java` | | Platform-specific extensions to project structure |
| `ProjectStructureVersionFactory.java` | | SPI interface for version factories |
| `SimpleProjectConfiguration.java` | | Concrete `ProjectConfiguration` implementation |
| `SimpleProjectDependency.java` | | Concrete `ProjectDependency` implementation |
| `SimpleArtifactGeneration.java` | | Concrete `ArtifactGeneration` implementation |
| `SimpleProjectDependencyExclusion.java` | | Concrete `ProjectDependencyExclusion` implementation |
| `ProjectConfigurationStatusReport.java` | | Status report for configuration changes |

Subdirectories:

| Directory | Contents |
|---|---|
| `config/` | `ProjectStructureConfiguration`, `ProjectPlatformsConfiguration`, `ProjectCreationConfiguration`, `ProjectFileConfiguration` — Dropwizard-style config objects |
| `extension/` | `ProjectStructureExtension`, `ProjectStructureExtensionProvider`, and implementations — server-managed extensions to project structure |
| `maven/` | `MavenProjectStructure`, `MultiModuleMavenProjectStructure`, `SingleModuleMavenProjectStructure`, Maven plugin helpers — the actual structure implementations that generate POM files |

### 2.2 Problematic dependencies

`ProjectStructure` itself imports from several server-only packages:

| Import | Source Module | Why It's Needed |
|---|---|---|
| `LegendSDLCServerException` | `legend-sdlc-server-shared` | Thrown during validation and update operations |
| `StringTools` | `legend-sdlc-server-shared` | Used for error message formatting |
| `ProjectConfigurationUpdater` | `legend-sdlc-project-files` | Used by `UpdateBuilder` for config mutations |
| `SourceSpecification`, `WorkspaceSpecification` | `legend-sdlc-project-files` | Used by methods that access workspace-qualified files |
| `javax.ws.rs.core.Response.Status` | JAX-RS API | Used for HTTP status codes in `LegendSDLCServerException` |

### 2.3 The two concerns in `ProjectStructure`

`ProjectStructure` conflates two distinct responsibilities:

1. **Read-side (layout knowledge)**: Given a `FileAccessContext`, determine where entity
   files are, parse `project.json` to get `ProjectConfiguration`, find entity source
   directories, resolve dependency information. This is what external tools need.

2. **Write-side (configuration updates)**: The `UpdateBuilder` inner class (lines 1020–1216)
   and `updateProjectConfiguration()` (lines 399–569) orchestrate multi-step configuration
   changes — reading current config, validating, computing file operations, and committing.
   This is server-only logic.

### 2.4 Existing module: `legend-sdlc-project-files`

This module already exists as a lighter-weight extraction point. It contains:
- `ProjectFileAccessProvider` and its inner interfaces (`FileAccessContext`, `ProjectFile`,
  `RevisionAccessContext`, `FileModificationContext`)
- `ProjectFileOperation`, `ProjectFiles`, `ProjectPaths`
- `CachingFileAccessContext`, `EmptyFileAccessContext`, `AbstractFileAccessContext`
- Domain API classes: `SourceSpecification`, `WorkspaceSpecification`, etc.

Its only SDLC dependency is `legend-sdlc-model`. However, it still imports server domain
concepts (`SourceSpecification`, `WorkspaceSpecification`) which involve workspaces, patches,
and conflict resolution — concepts that are server-specific, not project-structure-specific.

## 3. Proposed Approach

### 3.1 Strategy: Split `ProjectStructure` into read and write halves

Rather than extracting the entire `ProjectStructure` class, split it along the read/write
boundary. The read-side goes into a new module; the write-side stays in `legend-sdlc-server`
and depends on the new module.

### 3.2 New module: `legend-sdlc-project-structure`

Create a new module containing the read-side of project structure:

**What moves in:**
- `ProjectStructure` (stripped of `UpdateBuilder` and `updateProjectConfiguration()`)
- `ProjectStructureFactory` and `ProjectStructureVersionFactory`
- All version factory implementations (`V0`, `V11`, `V12`, `V13`)
- `ProjectStructurePlatformExtensions`
- `SimpleProjectConfiguration`, `SimpleProjectDependency`, `SimpleArtifactGeneration`,
  `SimpleProjectDependencyExclusion`
- `EntitySourceDirectory` (currently an inner class of `ProjectStructure`)
- The `maven/` subdirectory: `MavenProjectStructure`, `MultiModuleMavenProjectStructure`,
  `SingleModuleMavenProjectStructure`, and Maven plugin helpers

**What stays in `legend-sdlc-server`:**
- `UpdateBuilder` (moved to a standalone class, e.g., `ProjectStructureUpdater`)
- `updateProjectConfiguration()` and its helper methods
- The `config/` subdirectory (Dropwizard configuration bindings)
- The `extension/` subdirectory (server-managed extension providers)

## 4. Dependency Graph (Final State)

```
legend-sdlc-tools              [NEW] (StringTools, IOTools, LegendSDLCServerException)
       ▲
       │
legend-sdlc-model              (domain interfaces: Entity, ProjectConfiguration, etc.)
       ▲
       │
legend-sdlc-project-files      (FileAccessContext, ProjectFile, SourceSpecification, etc.)
       ▲
       │
legend-sdlc-project-structure  [NEW] (ProjectStructure read-side, version factories, maven)
       ▲
       │
legend-sdlc-server             (UpdateBuilder, Dropwizard config, extensions, GitLab impl)
```

### 3.4 Prerequisite: Create `legend-sdlc-tools` module

`StringTools` (currently in `legend-sdlc-server-shared`) and `IOTools` (currently in
`legend-sdlc-project-files`) are general-purpose utility classes with no external
dependencies — they use only `java.util`, `java.io`, and `java.nio`. They are currently
scattered across modules because there was no shared tools module below them.

Create a new `legend-sdlc-tools` module at the bottom of the dependency graph to hold
these utilities. Both `legend-sdlc-project-files` and `legend-sdlc-server-shared` would
depend on it instead of maintaining their own copies.

`LegendSDLCServerException` can also move to this module, **provided the JAX-RS dependency
is removed**. Currently the exception stores a `javax.ws.rs.core.Response.Status` field.
This can be replaced with a plain `int` status code:

```java
// Before (in legend-sdlc-server-shared, depends on javax.ws.rs)
private final Status status;  // javax.ws.rs.core.Response.Status

// After (in legend-sdlc-tools, no JAX-RS dependency)
private final int statusCode;
```

Server-side code that needs the JAX-RS `Status` enum can convert with
`Status.fromStatusCode(exception.getStatusCode())`. The exception class itself becomes
free of any web framework dependency.

The resulting dependency graph for the tools/utility layer:

```
legend-sdlc-tools              [NEW] (StringTools, IOTools, LegendSDLCServerException)
       ▲
       │
legend-sdlc-project-files      (FileAccessContext, ProjectFile, etc. — removes its own IOTools copy)
       ▲
legend-sdlc-server-shared      (removes its own StringTools, LegendSDLCServerException copies)
```

### 3.5 Breaking the remaining server dependencies

With `StringTools`, `IOTools`, and `LegendSDLCServerException` in `legend-sdlc-tools`,
the project structure code's server dependencies reduce to:

| Dependency | Resolution |
|---|---|
| `SourceSpecification` | The static methods `getProjectStructure(projectId, sourceSpecification, ...)` and `getProjectConfiguration(projectId, sourceSpecification, ...)` are convenience wrappers that delegate to `FileAccessContext`. These are expected — `legend-sdlc-project-structure` depends on `legend-sdlc-project-files` (which defines `SourceSpecification`). No change needed. |
| `ProjectConfigurationUpdater` | Only used by `UpdateBuilder`. Stays in the server module. |
| `WorkspaceSpecification` | Same as `SourceSpecification` — defined in `legend-sdlc-project-files`, no problem. |

### 3.6 Handling `EntitySourceDirectory`

`EntitySourceDirectory` is currently an inner class of `ProjectStructure`. Its
`serializeToBytes()` and `deserialize(ProjectFile)` methods throw `LegendSDLCServerException`.
Since that exception will now live in `legend-sdlc-tools` (with the JAX-RS dependency
removed), these call sites can continue using `LegendSDLCServerException` unchanged.

### 3.7 Maven structure dependencies

`MavenProjectStructure` and its subclasses depend on `org.apache.maven:maven-model` and
`org.codehaus.plexus:plexus-utils`. These are lightweight libraries (no server infrastructure)
and acceptable as dependencies of the new module.

The Maven plugin helper classes (`LegendEntityPluginMavenHelper`,
`LegendServiceExecutionGenerationPluginMavenHelper`, etc.) reference specific Legend
Engine/SDLC Maven plugin artifact coordinates. These are read-only data (they define
which plugins go into generated POMs) and have no runtime dependency on the actual plugins.
They can move to the new module.

## 5. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| **`ProjectStructure` is large (1218 lines) and the read/write boundary is not perfectly clean** | The `UpdateBuilder` and `updateProjectConfiguration()` are well-localized (lines 399–569, 1020–1216). The rest of the class is read-side. Careful review of each method is needed but the split is tractable. |
| **Breaking change for downstream consumers that import `ProjectStructure` from the server package** | Keep the package name (`org.finos.legend.sdlc.server.project`) the same in the new module to avoid source-level breakage. Downstream consumers just need to update their Maven dependency. |
| **Changing `LegendSDLCServerException` from `Status` to `int`** | Add a `getStatus()` convenience method to `legend-sdlc-server-shared` (or a subclass) that converts back to the JAX-RS `Status` enum. Existing server code that calls `getStatus()` continues to work after a minor adaptation. |
| **Other modules in `legend-sdlc` depend on `ProjectStructure` from `legend-sdlc-server`** | Those modules gain a transitive dependency through `legend-sdlc-server` → `legend-sdlc-project-structure`. No change needed for them. |

## 6. Incremental Plan

The extraction can be done incrementally to reduce risk:

### Phase 1: Create `legend-sdlc-tools`

1. Create the `legend-sdlc-tools` module with no SDLC dependencies.
2. Move `StringTools` from `legend-sdlc-server-shared` to `legend-sdlc-tools`.
3. Move `IOTools` from `legend-sdlc-project-files` to `legend-sdlc-tools`.
4. Refactor `LegendSDLCServerException` to use `int statusCode` instead of
   `javax.ws.rs.core.Response.Status`. Move it to `legend-sdlc-tools`.
5. Add `legend-sdlc-tools` as a dependency of `legend-sdlc-project-files` and
   `legend-sdlc-server-shared`. Update imports in both modules.
6. Build and verify all tests pass.

### Phase 2: Prepare the `ProjectStructure` split

7. Promote `EntitySourceDirectory` from an inner class to a top-level class (still in
   `legend-sdlc-server`). This is a refactoring-only change.
8. Extract `UpdateBuilder` and `updateProjectConfiguration()` into a standalone
   `ProjectStructureUpdater` class (still in `legend-sdlc-server`). Have `ProjectStructure`
   delegate to it. Add deprecated forwarding methods.

### Phase 3: Create `legend-sdlc-project-structure`

9. Create `legend-sdlc-project-structure` module with dependencies on `legend-sdlc-tools`,
    `legend-sdlc-model`, `legend-sdlc-project-files`, Eclipse Collections, Jackson,
    and `maven-model`.
10. Move the read-side classes into the new module. Keep the same Java package
    (`org.finos.legend.sdlc.server.project`) to minimize source-level breakage.
11. Add `legend-sdlc-project-structure` as a dependency of `legend-sdlc-server`.
12. Update `ProjectStructureUpdater`, config, and extension classes in `legend-sdlc-server`
    to import from the new module.

### Phase 4: Validate

13. Build the full `legend-sdlc` project and verify that all existing tests pass.
14. Verify that `legend-sdlc-project-structure` can be depended on without pulling in
    `legend-sdlc-server` or `legend-sdlc-server-shared`.
