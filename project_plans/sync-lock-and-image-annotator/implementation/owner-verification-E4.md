# Owner verification checklist: Epic E4 (camera lens switching and lens metadata)

CI covers the pure logic (classifier, session state machine, lens-record builder, sidecar round trip, chips and error states under Robolectric). It cannot know which lenses your phone exposes, whether `setPhysicalCameraId` binds, or whether the capture callback delivers lens keys. Until you run the probe, the zoom-chip path is what ships and the physical-id path is UNVERIFIED: a physical lens is listed only if its bind succeeded on the device at runtime, and the saved record falls back to `source = SELECTED_LENS` when the capture result carries no lens key.

## A. Capability probe (Task 4.1b, D-E4-01)

1. Install a debug build (the "Probe lenses" button only shows in a debuggable build) and open the camera from the annotator.
2. Tap "Probe lenses" once. The preview rebinds afterwards; if it stays black, close and reopen the dialog.
3. Pull the result: the Logs screen shows one `camera id=... facing=... focal=... physical=2:ok,3:fail:... zoom=...` line per camera id, and the fixture is written to `<app files>/camera-probe/lens-fixture-<make>-<model>.json` (`adb shell run-as <package> cat files/camera-probe/lens-fixture-*.json`, or the Logs screen export).
4. Read these off the lines and note them in the PR:
   - Which rear ids and focal lengths exist, and whether one id is a logical multi-camera (`logical=true`).
   - For each physical id behind it, `ok` or `fail:<exception>`: this decides whether ultra-wide and telephoto appear as separate lens chips.
   - Whether `intrinsic=` and `distortion=` are present (information only; lens distortion is never applied to the JPEG).
5. Check the fixture contains only make and model, no serial or location, then commit it to `kmp/src/androidUnitTest/resources/camera/` (or `kmp/src/commonTest/resources/camera/`) and tell the next session so the provisional ratio bands in `LensClassifier` (ultra-wide below 0.75, tele above 1.4) can be tightened against it.
6. Optional: run the same probe once on the `Pixel_Fold` AVD (`~/Android/Sdk/emulator/emulator -avd Pixel_Fold -no-window -no-audio -no-snapshot`, `ANDROID_HOME` exported) and record whether the emulator exposes extra lenses (Open Question 6).

## B. Capture metadata delivery (decides the record source, ADR-004 decision 5)

Take one photo per chip. For each, the Logs screen has one line:
`capture lens camera=<id> physical=<id|null> focal=<mm> zoom=<ratio> source=<PREVIEW_RESULT_AT_SHUTTER|SELECTED_LENS> keys=focal:<bool>,physical:<bool>,zoom:<bool>`

- Record which keys arrived on each lens. If `keys=focal:false,physical:false` on every lens, the capture callback is not delivering lens keys on this phone and every record is `SELECTED_LENS` (never used as independent evidence for calibration).
- If `physical:true`, check the id matches the lens you chose. A mismatch is the reason the record is built from the capture result and not from the chip.
- Open the photo's sidecar (`<graph>/.stelekit/images/<uuid>.measure.json`): `imageAnnotation.lensRecord` should show the same camera id, focal length and `source`, and `schemaVersion` should be 2.
- Check the EXIF of a rotated portrait photo (any EXIF viewer): `FocalLength`, `FocalLengthIn35mmFilm`, `Make`, `Model`, `DateTime` are still present (Task 4.5c).

## C. Device acceptance (Task 4.7b, D-E4-02..04; tick in the PR)

- [ ] The chip list matches the probe output: every separately bindable back camera plus every optical zoom stop; stops above the longest optical focal say "Digital".
- [ ] A lens that does not switch is disabled with a reason on its chip, the previous lens keeps running, and the Logs screen has `camera bind lens=<id> result=fail:<class>`.
- [ ] Switching between chips takes about 1 s (median at most 1 s, worst case at most 2 s over 10 switches, from the `camera bind ... ms=` log lines). The thresholds are PROPOSED (OC-14); there is no pre-change baseline to compare with.
- [ ] One photo per lens; sidecar record and EXIF as in section B.
- [ ] Preview never shows a black frame during a switch. KNOWN GAP: the held-frame crossfade (UX-41) is not implemented; CameraX's own preview behaviour is what you see. Note what you see.
- [ ] Rotate the phone during a lens rebind: the selected lens is restored and the shutter stays disabled until bound.
- [ ] Revoke the camera permission while the dialog is open. KNOWN GAP: the dedicated denied screens (F12) are not implemented in this dialog, the existing permission flow still applies. Note what happens.
- [ ] Fold and unfold (Pixel Fold): at 600 dp or wider the controls move to a side rail; nothing draws under system bars.
- [ ] Pinch-to-zoom moves the highlight to the nearest optical chip and does not trigger a lens rebind; hovering a pinch at the midpoint between two chips does not flicker.
- [ ] A lens without a flash unit shows "Flash not available on this lens" (button disabled); otherwise Flash cycles Off, Auto, On.
- [ ] The wide-angle hint appears on the ultra-wide lens and stays dismissed after reopening and after a process restart.
- [ ] The one-shot `AndroidCameraProvider` capture and the QR-scan preview still work (they no longer call `unbindAll()`).
