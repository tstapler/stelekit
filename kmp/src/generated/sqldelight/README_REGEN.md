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

See ADR-001: checked-in generated sources vs. a custom `sqldelight_codegen` Bazel rule.
Checked-in sources are simpler and avoid a complex code-generation Bazel rule that would
require Kotlin scripting support not yet stable in `rules_kotlin`.
