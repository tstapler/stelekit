# Coverage wave A: `git`, `migration` (T1, JVM row)

Source: `coverage-map.md` (baseline run 40829801185, commit `a543aecb95`). On the `jvm` row only `db`, `repository`, `git` and `migration` are T1 packages under the 85% target. `db` and `repository` belong to the existing `db-repository-test-coverage` plan, so wave A takes the other two.

Method: per-line gain = lines the baseline XML marks missed that a run of only the new classes covers (`jacocoTestReport` after `:kmp:jvmTest --tests <new classes>`). Files with a later commit than the baseline were not re-measured against shifted line numbers (`GitSyncService.kt` changed after the baseline and is left alone).

| Package | Baseline (map) | After | Target | Note |
|---|---|---|---|---|
| `git` | 2764/3366 = 82.1% | 2845/3366 = 84.5% | 85 | 16 lines short; remaining gap is `GitSyncService.kt` (165 missed at baseline) and `JvmGitRepository.kt` (63) |
| `migration` | 474/594 = 79.8% | 539/594 = 90.7% | 85 | met |

Update (wave D): a fresh run of the `dev.stapler.stelekit.git.*` tests on the current tree measures `git` at 3525/4021 = 87.7%, above the target; see `wave-D.md`.

Tests: `GitWildmatchTest`, `GitAttributesParserTest`, `GitOperationSupportHelpersTest`, `GitRefDiagnosticsTest`, `SqlDelightGitConfigRepositoryRoundTripTest`, `FileSystemLfsContentProbeTest`, `GitHubDeviceFlowClientEdgeCasesTest`, `ChangeApplierBranchesTest`, `PostMigrationFlusherTest`.
