# QA protocol: knife-and-ruler on a real phone

## 1. Quick wins the owner can do today (no code)

Each rule traces to the error budget in requirements.md section 1 (simulation numbers from PR #638's design note).

1. Same plane: lay the knife flat directly on the same surface as the ruler, with the ruler edge touching the blade edge. If the knife must sit on top of the ruler, the blade is raised by the ruler thickness; expect a reading about thickness / camera distance too high (1 cm at 60 cm = 1.7%).
2. Square-on: hold the phone parallel to the table, looking straight down. The in-app camera records pitch/roll; aim for under 3 degrees (a proposal; the existing warning only appears above 15/10 degrees).
3. Long reference: calibrate over at least a third of the photo's long side (a 30 cm ruler filling the frame, not a 5 cm piece). A 1 px tap error on a 60 px line is 3.3%, on a 2400 px line 0.08%.
4. Three or more ticks, spaced over the longest span (needed for the multi-tick fit in #638).
5. Centre the knife, keep the ruler close to the knife (same distance from the image centre), fill the frame, use the main 1x camera, no digital zoom.
6. Use a flat object of known size as a check: a credit-card sized ID-1 card is 85.60 x 53.98 mm (VERIFIED). Measure it first; if it reads within 0.1 cm, the setup is sound.
7. Take 3 photos, re-lift the phone between them; large spread means tapping, not geometry.
8. Zoom in the editor before tapping endpoints (needs the zoom/pan PR).

## 2. Protocol P1 (the standard test)

Setup: matte flat table, 30 cm ruler, a straight 18.0 cm object (use a straight edge verified against the ruler, cut or marked at 18.0 cm). Main camera at 1x, in-app camera for tilt data, then repeat once with the stock camera app + gallery import.
Steps per trial: shoot; open in the editor; calibrate with 3 ticks at 0, 15, 30 cm; measure the object; record the reading and the accuracy panel text.
Expected readings: within +-0.1 cm of 18.0 (M1). Variants and expectations:

| Trial | Change | Expected reading |
|---|---|---|
| A | baseline | 18.0 +- 0.1 |
| B | object raised 1 cm on a 1 cm block (ruler stays on the table), camera about 60 cm away | about 18.3 high without correction; with h = 1 cm entered, 18.0 +- 0.1 |
| C | tilt phone about 5 degrees, object 25 cm to the side | 0.3 to 0.7 cm error without homography; warning shown |
| D | reference only 5 cm long | warning "short reference"; scatter about 0.5 cm |
| E | ultra-wide camera | likely larger error; note whether profile applied |
| F | gallery import (no sensor data) | "Tilt unknown" shown |

## 3. Log sheet (copy into a note per session)

```
date:   phone make/model:   camera (1x/UW/tele):   zoom:   app version:
photo source: in-app | stock camera -> gallery
ruler length cm / tick count / reference length px:
trial | tilt shown (deg) | h entered (cm) | profile (id/none) | reading cm | accuracy panel text | notes
```
Compute per session: mean, max-min, and error vs 18.0. Success = M1/M6 in requirements.md. Attach the three EXIF fields Make, Model, FocalLength and the image pixel size (no location) when filing results. If a profile is built, record `rmsPx` from the calibration script.

## 4. Calibrating a camera profile (v1d, after Epic 4)

Print a checkerboard at 100% scale on A4 without "fit to page", tape it flat. Shoot 14 to 20 JPEGs with the stock camera app at the exact lens and zoom used for measuring, covering corners and tilted views. Run the script from Epic 4.5; check `rmsPx` below 0.7 px (target, INFERRED from the 1 px endpoint noise level); import the JSON in the app; re-run trial A and trial E and compare.
