# ADR-004: What NOT to build

Status: PROPOSED

| Not building | Why (evidence) | Revisit when |
|---|---|---|
| Use of Camera2 `LENS_DISTORTION` / `LENS_INTRINSIC_CALIBRATION` on saved JPEGs | Pre-correction frame; JPEGs are processed output and may already be corrected; optional/null on some devices (research/camera-intrinsics-distortion.md sections 1-2) | A device-verified experiment shows corrected vs raw frames match the documented formula |
| Bundled lensfun / lens database | Poor phone coverage, share-alike licence, one crop factor per entry (section 5) | Owner accepts CC BY-SA and a phone-aware database appears |
| Monocular metric depth for correction | Error above the 1 cm signal; heavy; licences mixed (depth-sources.md section 5) | A model meets the criterion in ADR-003 v3 |
| Depth as a silent substitute for a missing reference | Low confidence; misleading number | never silently |
| IMU-baseline stereo | drift | VIO available as a library |
| DNG WarpRectilinear ingest | no DNG capture; phone coverage unverified | owner shoots DNG |
| New profile DB tables | profile belongs to device; snapshot in existing lens JSON | multi-user sharing of profiles |
| Sub-0.1 cm promises on gallery photos of unknown provenance | cannot know crop, zoom, tilt | after Epic 2/3 give an uncertainty readout |
