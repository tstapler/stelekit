# SQLDelight Generated Sources

These files are checked in (not generated at build time by Bazel).

## When to re-generate

Re-run whenever you change any `.sq` file under `kmp/src/commonMain/sqldelight/`.

## Regeneration command

```bash
./gradlew :kmp:generateCommonMainSteleDatabase
rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/
git add kmp/src/generated/sqldelight/
```

## Rationale

See `project_plans/stelekit-bazel/decisions/ADR-003-checkin-sqldelight-generated.md`:
checked-in sources are simpler and avoid a complex code-generation Bazel rule that would
require Kotlin scripting support not yet stable in `rules_kotlin`.
