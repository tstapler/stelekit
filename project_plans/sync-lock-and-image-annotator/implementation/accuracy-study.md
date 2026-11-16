# Accuracy study: SM3b real-photo hold-out (Story 3.8)

Status: not yet run. The study needs the owner's photos and tape-measured ground truth; see
`owner-verification-E3.md` for what to capture and the exact commands. Synthetic results cannot close SM3b
(`AccuracyStudyVerdictTest` rejects an "SM3 met" verdict without the evidence table below).

Proposed pass rule (OC-13, owner to confirm): on set B the shipped default tier's held-out mean absolute error is
strictly below single-scale and at most 2% of the true length (0.5 cm floor under 25 cm), and no photo is more than
10% worse than single-scale.

## Evidence table

One row per real photo. Door frame is `yes` for the owner's door-frame photo. Set A tunes thresholds, set B decides.

| Photo | Set | Door frame | Tape distances | Single-scale MAE (cm) | Default-tier MAE (cm) |
|---|---|---|---|---|---|

## Verdict

SM3 OPEN - awaiting owner photos
