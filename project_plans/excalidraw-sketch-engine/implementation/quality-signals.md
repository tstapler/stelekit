window-start: UNSET (set to the date of the flag release of Task 6.2.5c, the first release in which the owner can create new-editor sketches in a graph they use; no such release exists yet)

# Quality signals and the data-loss log

The one origin of every window below is the `window-start:` line above (requirements.md metric 8, "Flag and legacy-editor exit criteria"; D-63). Until it is a date, every window is "not started" and no threshold can be met.

Counters are local and never transmitted: `QualitySignals` (`kmp/src/commonMain/.../sketch/v2/runtime/telemetry/QualitySignals.kt`) over `Settings`, shown in Settings > Sketches and emitted once per session end as `sketch.adoption authored=<n> imported=<n>` and `sketch.quality v2Sessions=<n> crashes=<n> solves=<n> noConvergence=<n> conflicts=<n>`. Authored means a sheet created in-app with at least 3 elements drawn with the tools; imports and conversions are separate (metric 8).

## Counter readings

| date | v2Sessions | crashes | solves | noConvergence | conflicts | authored | imported | read by |
|---|---|---|---|---|---|---|---|---|
| (none recorded; the owner copies the Settings readout here when reading a window) | | | | | | | | |

## Data-loss log

One line per report: `<date> | <what was lost, truncated or unreadable and after which v2 write, sync, conversion or restore> | <confirmed against the backup or file: yes/no> | <root cause> | <regression test>`.

(no reports)
