# Pre-mortem

| Priority | Failure | Prevention (in plan.md) |
|---|---|---|
| P1 | A late cancel kills the user's retried download | `awaitPendingCancel()` before every `runOnce`; ordering test |
| P1 | WorkManager unavailable becomes an endless `Downloading` | `statusFlow == null` term in the guard |
| P2 | Root stops passing the scheduler | estimator parameter has no default |
| P3 | Process dies before the launched cancel runs | accepted |

No P1 is open: both have their prevention in the plan.
