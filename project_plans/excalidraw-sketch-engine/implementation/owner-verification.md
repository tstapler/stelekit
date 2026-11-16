# Owner verification checklist (excalidraw sketch engine)

Each section is one release or device pass. An item is checked by the owner before the release-please PR is merged; nothing here is
checked by an agent. Unchecked means UNMEASURED.

## Reader-only release (Task 6.2.5e; depends on Stories 1.4.3, 1.4.5, 6.1.1 and 2.1.3 only, never on the A1 gate)

Needs only the router (1.4.3), the lease and refusal paths (1.4.5), the embed preview (6.1.1) and the painter (2.1.3), so it can ship
before the A1 gate (Task 6.2.4e), the solver, the importer or conversion are released. Every outcome table row of requirements.md
"Combined outcomes" except "stop entirely" and "stop at the pure engines" allows it.

### Release notes (paste into the release body)

> **Sketches: this version can read the new sketch format.** This build opens and shows sketches saved in the new editor's format
> (v2) in read-only mode. It never creates one and never converts an existing sketch. Older SteleKit versions cannot open a project
> that holds a new-format sketch: they refuse it instead of risking the file. Update SteleKit on **every device** that opens your
> sketch projects **before** you upgrade any sketch in a later release.

Claims, and the test or file that backs each:

| Claim | Evidence |
|---|---|
| Opens and shows v2 sheets read-only | `EditorRouterTest.v2_should_OpenReadOnlyWithTheReason_When_ModeIsOff` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/sketch/v2/EditorRouterTest.kt`) |
| Older builds fail closed on a project holding a v2 sheet | `LegacyStoreRefusesV2Test` (same directory); an older build's behaviour is only checked by the owner's device pass below |
| Never creates a v2 sheet | holds only while no creation path is reachable: see the first checklist item |

### Checklist

- [ ] The build being released cannot create or convert a v2 sheet. UNVERIFIED: at the time of writing `V2Build.CONVERSION_READY` and
  `V2Build.CONVERSION_OFFERED` are both `true` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/V2Mode.kt`, so the mode
  picker can offer New projects only and On. The owner decides how the reader-only build closes that (build constants for this
  release, or the picker hidden) before this note is published; until then the note's "never creates" sentence is not true of this tree.
- [ ] The release notes above are in the release body, with the "update every device first" sentence unchanged.
- [ ] Owner device pass: a v2 sheet copied to a second device on the previous release is refused there with no data change; the same
  sheet on this build shows read-only with the reason text "Turn on the new editor to edit." (`SketchReasonText.READ_ONLY_TURN_ON`).
- [ ] No A1-gate, solver, importer or conversion text appears in these notes (script-checked edge: 6.2.5e has no path to 6.2.4e, 6.2.4f,
  6.2.5a or a parked node).

Later releases (Task 6.2.5c, 6.2.5d and the Task 6.2.4c device passes) add their own sections here.
