# ADR-014: `.stele-sections` Manifest Schema

## Status
Accepted

## Context

The Graph Sections feature allows a user to tag pages with a named "section" (e.g., a job context, personal notes, a client project) so that syncing to a device only downloads that device's relevant sections. ADR-011 already decided the file format is TOML, parsed via `ktoml-core`. ADR-013 describes how the WASM build fetches the manifest from a git remote. This ADR defines the actual schema — the fields, their semantics, and what belongs in the committed manifest versus local device state.

### Schema responsibilities

The `.stele-sections` manifest must cover three concerns:

1. **Section definitions**: What sections exist, their human name, display color, and which graph paths belong to them.
2. **Page assignment**: How pages are associated with a section. Options: path-prefix rules in each section definition, explicit per-page assignment table, or tag-based inference.
3. **Device subscription**: Which sections a given device downloads. This is inherently per-device state.

### Path-prefix vs. explicit-assignment vs. tag-based assignment

#### Option A: Path prefixes in section definition (chosen)
Each `[[section]]` declares a `paths` array. Any page whose file path starts with any listed prefix is assigned to that section. Pages that match no prefix are in the implicit `default` section (synced everywhere).

```toml
[[section]]
id = "work-acme"
paths = ["pages/acme/", "journals/acme/"]
```

**Pros**: compact, no per-page overhead, works correctly when pages are reorganized by directory, human-editable without tooling.  
**Cons**: requires a directory-per-section convention; pages not organized by directory need explicit overrides.

#### Option B: Explicit per-page assignment table
A `[assignments]` table maps each page name or UUID to a section ID.

```toml
[assignments]
"Meeting Notes 2026-06-01" = "work-acme"
```

**Pros**: no directory convention required; individual pages can be in different sections regardless of where they live.  
**Cons**: every new page in a section requires an edit to the manifest; the table grows unboundedly with the graph; merge conflicts on the manifest increase with every add.

**Verdict: rejected for v1.** Path prefixes cover 90% of real use cases without manifest growth. An explicit override table can be added in v2 without breaking the schema (ktoml ignores unknown keys with `ignoreUnknownNames = true`).

#### Option C: Tag-based inference
Pages tagged with `#acme` (declared in the section's `tags` field) are automatically assigned to that section. No manifest write required when tagging a page.

**Pros**: fits naturally into Logseq/SteleKit's tag-based linking model; no directory restructuring required.  
**Cons**: requires parsing every page's content to build the section→page map (O(graph) scan at startup); tags can be added to any page by any user, making assignment non-authoritative; a page with two section tags creates an ambiguous assignment.

**Verdict: deferred.** Tag-based filtering is useful for dynamic queries but not as a sync boundary: the sync boundary must be computable without reading all page content. Tags can be added as a supplemental display filter in v2. The `tags` field is reserved in the schema for forward compatibility.

### Device subscription location

Putting device subscriptions inside `.stele-sections` (committed to git) creates a merge conflict whenever any device changes its subscription: two devices editing `[deviceProfiles]` in the same file will produce conflicts on every pull. Device preference is not shared state — it is local state.

**Decision**: device subscriptions are stored in `PlatformSettings` (key: `subscribedSections/<graphId>`, value: comma-separated section IDs). The manifest declares sections; the device decides which ones to subscribe to via local settings. This means `.stele-sections` is read-only from the perspective of a subscribing device: no subscription operation writes back to the manifest.

## Decision

The `.stele-sections` file at the graph root uses TOML with the following schema:

```toml
# Required. Increment when removing or renaming required fields.
version = 1

[[section]]
# Required. Stable machine identifier. Never rename after pages are assigned.
id = "work-acme"
# Required. Human-readable label shown in the section selector UI.
name = "Work – Acme Corp"
# Required. Path prefixes (relative to graph root). Pages under these paths
# belong to this section. Use trailing slash to avoid prefix collisions.
paths = ["pages/acme/", "journals/acme/"]
# Optional. Hex color for UI badge. Defaults to a deterministic hash of id.
color = "#4A90D9"
# Optional. Reserved for v2 tag-based display filtering. Not used for sync.
tags = ["#acme", "#work"]

[[section]]
id = "personal"
name = "Personal"
paths = ["pages/personal/", "journals/personal/"]
color = "#7B68EE"
```

### Semantics

- **`version`**: integer. Clients that read a `version` higher than they understand must display a warning and skip sync (not silently proceed). Current supported version: `1`.
- **`[[section]]` array**: zero or more sections. An empty sections list is valid — the graph has no sectioning, all devices see all pages.
- **`id`**: lowercase kebab-case string, unique within the manifest, stable across renames. Page assignment is keyed by `id`, so renaming requires a migration step (out of scope for v1).
- **`paths`**: array of strings. Each entry is a path prefix relative to the graph root. The prefix SHOULD end with `/` to avoid a section named `acme` matching pages under `acme-corp/`. An empty `paths` array means the section has no pages assigned by path rule; this is valid (e.g., a section defined for future use).
- **`color`**: optional hex string (`#RRGGBB`). Missing → UI derives a color from `id` via a deterministic hash.
- **`tags`**: optional array of strings. Stored but unused in v1. Clients MUST NOT use `tags` to determine sync scope in v1.

### Page assignment algorithm (v1)

For each page file path:
1. Collect all `[[section]]` entries whose `paths` array contains a prefix of the file path.
2. If exactly one section matches → page belongs to that section.
3. If zero sections match → page belongs to the implicit `default` section (synced to all devices).
4. If multiple sections match → page belongs to the first matching section in declaration order. This is a user error; the UI should warn.

### Device subscription (not in manifest)

`PlatformSettings` stores the device's section subscription:

```
key:   "subscribedSections/$graphId"
value: "work-acme,personal"   // comma-separated section IDs; empty = subscribe to all
```

An empty value (or missing key) means the device subscribes to all sections (backward-compatible default).

### Manifest location and git behavior

- File path: `$graphPath/.stele-sections` (no extension, consistent with Logseq config convention).
- Committed to the graph's git repository alongside all other content.
- `.gitignore` does NOT exclude it; it is shared state across all devices.
- `ktoml-core` reads it with `TomlConfig(ignoreUnknownNames = true)` so future fields do not break old clients.

## Rationale

1. **Path-prefix assignment is O(1) per page during sync**: the WASM sync layer (ADR-013) uses the section's `paths` list to filter the remote tree listing without fetching any page content. Tag-based assignment would require fetching and parsing all pages before deciding what to download — defeating the purpose of sectioned sync.

2. **Device subscriptions in `PlatformSettings` avoid merge conflicts**: the manifest is written by the graph owner and shared; device preferences are local. Mixing them would make every subscription change a git commit, creating noise in the commit history and conflicts between devices.

3. **`version` field enables safe future evolution**: when a required field is removed or renamed, incrementing `version` lets old clients fail with a clear diagnostic instead of silently misassigning pages.

4. **Empty `paths` is valid**: allows the manifest to pre-declare sections before pages are organized into the right directories. The graph owner can define sections first, then move pages; no sync errors occur in the interim.

5. **`ignoreUnknownNames = true` enables additive evolution without bumping `version`**: new optional fields (e.g., an explicit `[assignments]` override table in v2) are ignored by v1 clients.

## Alternatives Rejected

### JSON instead of TOML
ADR-011 already decided TOML. JSON is not human-editable without tooling (no comments, verbose syntax for arrays of tables). TOML's `[[section]]` syntax maps cleanly to `List<GraphSection>` via kotlinx.serialization with no custom parsing. Rejected at ADR-011; not reconsidered here.

### Explicit per-page `[assignments]` table (v1)
Rejected above under Option B. The manifest grows O(pages) and conflicts on every new page. Deferred to v2 as an override mechanism for pages that do not fit a path-prefix convention.

### `deviceProfiles` inside the manifest
Storing device subscriptions in the manifest creates merge conflicts when multiple devices change their subscriptions. The manifest is shared, immutable-from-device-perspective content; `PlatformSettings` is the correct home for per-device state.

## Consequences

- `SectionManifestParser` (to be implemented) will parse `.stele-sections` using `ktoml-core:0.7.1` into a `SectionManifest(version: Int, section: List<GraphSection>)` data class.
- `GraphSection` data class will have fields: `id: String`, `name: String`, `paths: List<String>`, `color: String? = null`, `tags: List<String> = emptyList()`.
- `WasmSectionSyncService` (ADR-013) receives the parsed manifest before the tree-listing phase to filter paths. The `paths` field maps directly to the path-prefix filter.
- Renaming a section `id` after pages are assigned is a breaking operation with no automated migration in v1. The UI must warn if an `id` referenced in `PlatformSettings` no longer exists in the manifest.
- The page assignment algorithm's "first match wins on ambiguity" rule means section order in the manifest is semantically significant. The manifest editor UI must make this ordering visible.
