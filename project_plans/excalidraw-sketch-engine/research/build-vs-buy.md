# Build vs Buy: excalidraw-sketch-engine

**Date**: 2026-11-14. **Confidence labels**: VERIFIED = a source was opened or a command run this session; SEARCH = taken from a web-search summary, primary page not opened; UNVERIFIED = no source found.

## Classpath today
There is no `gradle/libs.versions.toml` in this repo; dependencies are declared inline in `kmp/build.gradle.kts` (VERIFIED, lines 86-163). `commonMain` already has Arrow 2.2.1.1, kotlinx-coroutines 1.10.2, kotlinx-datetime, kotlinx-serialization-json 1.10.0 (enough for `.excalidraw` JSON), Compose Multiplatform (runtime/foundation/material3), Coil 3.2.0, Ktor 3.1.3, Okio 3.17.0 and `kotlin-multiplatform-diff`. `commonTest` has kotlin-test, kotest-assertions-core and kotest-property 6.2.4 (property tests ready for the solver and round-trip metrics). There is no linear-algebra, geometry, WebView, or stroke library. Existing sketch code: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/` (SketchScene, SnapEngine, SketchHistory, ZoneConstraints, ViewTransform...).

## (1) Open-source libraries

### Excalidraw embedded via WebView
- Pros: full feature set and `.excalidraw` fidelity for free; MIT (SEARCH: [github.com/excalidraw/excalidraw](https://github.com/excalidraw/excalidraw/)); actively released (Wikipedia lists 0.18.1, Apr 2026, [Wikipedia](https://en.wikipedia.org/wiki/Excalidraw)).
- Cons: needs a JS bundle shipped offline per platform; WebView KMP wrappers are young and the wasmJs/iOS support claims are unverified (SEARCH: [klibs.io compose-webview](https://klibs.io/project/parkwoocheol/compose-webview), [ComposeNativeWebview](https://klibs.io/project/NucleusFramework/ComposeNativeWebview)). On wasmJs the app would be Compose-in-browser embedding a second web app. Excalidraw has no constraint solver, so FreeCAD-style constraints (success metric 3) are impossible. No access to the scene from commonMain for the dimension/fusion pipeline, undo integration, or SQLDelight persistence; a bridge in JS is needed. Fails requirements constraint "engine in commonMain" and the already-rejected alternative.
- **Verdict: Not recommended** as the engine. Viable only as a throwaway visual-behaviour reference in a browser.

### tldraw
- Pros: best-in-class editor behaviour; a good reference for UX.
- Cons: not MIT. The SDK now needs a license key in production; without one the editor stops rendering after 5 seconds; hobby keeps a watermark, commercial is paid and unpublished pricing; the license forbids removing the watermark or disabling key validation (SEARCH: [tldraw SDK License](https://tldraw.dev/legal/tldraw-license), [license key](https://tldraw.dev/sdk-features/license-key), [FAQ](https://tldraw.dev/faq)). It is also a React SDK, so it hits every WebView problem above.
- **Verdict: Not recommended.** Do not copy code; reading behaviour is fine.

### KMP / Compose drawing and whiteboard libraries
- Found only brush-style drawing views (DrawBox, sketch-kmp, compose-stylus; SEARCH: [klibs.io DrawBox](https://klibs.io/project/akshay2211/DrawBox), [sketch-kmp](https://klibs.io/project/nowjordanhappy/sketch-kmp), [compose-stylus](https://klibs.io/project/MohamedRejeb/compose-stylus)). None offers a scene graph, bindings, constraints, or hit-testing on shapes. Adopting one would add a dependency for the least valuable 5% (raw touch/stylus capture).
- **Verdict: Not recommended** as engine. `compose-stylus` could be a reference for stylus pressure/hover input only; evaluate separately in the input-handling spike.

### Constraint solvers
| Option | License | Fit | Verdict |
|---|---|---|---|
| planegcs (FreeCAD) | LGPL-2.1-or-later (SEARCH: [planegcs docs](https://planegcs.readthedocs.io), [PyPI](https://pypi.org/project/planegcs/)) | C++ with Eigen and Boost; Python bindings only. No Kotlin/Native or wasm build exists in what I found (UNVERIFIED). | Not recommended as a dependency; **Viable as an algorithm reference** (read DogLeg/LM structure and constraint set). |
| SolveSpace libslvs | GPL-3.0-or-later; commercial licence available on request (SEARCH: [solvespace.com/library](https://solvespace.com/library.pl), [Wikipedia](https://en.wikipedia.org/wiki/SolveSpace)) | C library, but GPL forbids linking into the app unless SteleKit's own licence is GPL-compatible. Repo `LICENSE` not checked here. | Not recommended (licence + cinterop on four targets incl. wasm). |

LGPL implications: even a faithful Kotlin port of planegcs source is a derivative work, so it would carry LGPL-2.1+ and be tied to attribution and relinking obligations that are awkward for a statically-compiled Kotlin binary and wasm bundle. Studying the algorithm and writing an independent implementation avoids that, but record the clean-room boundary in an ADR (reader of the planegcs source should not paste code). Needs owner/legal decision if direct porting is ever wanted.

Bespoke pure-Kotlin LM/Gauss-Newton solver: see section 3.

### perfect-freehand
- MIT (SEARCH: [Dart port MIT](https://pub.dev/documentation/perfect_freehand/latest/); original `steveruizok/perfect-freehand`, README states MIT). Excalidraw's freedraw used it as of an old manifest (SEARCH: [lightrun thread](https://lightrun.com/answers/excalidraw-excalidraw-perfect-freehand-drawing)). I found no Kotlin port (SEARCH result: none). The algorithm is small (~500 lines, pure arithmetic).
- Pros: MIT, tiny, deterministic, pure maths so identical on every target; a faithful port gives Excalidraw-looking strokes and pressure.
- Cons: you maintain the port; the author asks commercial users to consider sponsoring.
- **Verdict: Recommended as a port** (MIT allows it; keep the copyright notice in the file header and add a NOTICE entry). Cover with golden-output tests generated from the JS original.

### rough.js (hand-drawn rendering)
- MIT, (c) Preet Shihn (SEARCH: [roughjs.com](https://roughjs.com/)). No Kotlin port found. It depends on a seeded random generator; Excalidraw stores `seed` and `roughness` per element, so identical output needs the same PRNG, which is portable.
- **Verdict: Viable**, gated on Open Question 3. If the owner wants the style, port a subset (line, rectangle, ellipse, polygon strokes, double-stroke) behind a `StrokeStyle` interface; default clean style needs none of it.

### Kotlin linear algebra (multik, ejml, kmath)
- Multik: JVM, JS, wasmJs, iOS, native targets; the pure-Kotlin `multik-kotlin` engine is the one that runs on wasmJs; README labels it alpha (SEARCH: [klibs.io multik](https://klibs.io/project/Kotlin/multik)).
- EJML: Java library, JVM only; nothing found for wasm/native (SEARCH: none). Excluded by "all targets".
- KMath: lists JVM/JS/Native/Wasm for core, connectors (multik, ejml) are prototype or JVM-only (SEARCH: [git.sciprog.center/kscience/kmath](https://git.sciprog.center/kscience/kmath)).
- Need check: a 100-constraint solve is roughly a 200-variable system with a Jacobian of ~200x200. That is small. A hand-written dense LU/QR/least-squares on `DoubleArray` is ~300 lines and avoids an alpha dependency, array-boxing overhead and wasm-compat risk.
- **Verdict: Not recommended** to add a dependency now (alpha, wasm uncertain, unneeded at this size). Revisit only if the solver spike shows sparse or large systems; Multik `multik-kotlin` is the fallback candidate.

## (2) SaaS / managed APIs
- Excalidraw+ and tldraw hosted offerings, Miro/Figma-style embed APIs: all require network, send user diagrams off-device (violates local-first and the internal/confidential classification in requirements), cannot supply constraint solving, and do not run as native Compose. No evidence of an offline-capable SDK with KMP bindings (UNVERIFIED; none found).
- **Verdict: Not recommended.** Out of scope per requirements (no collaboration or cloud).

## (3) Bespoke LLM-written code vs battle-tested library

The question is not "LLM or human" but "can the result be verified against an oracle". Custom code is acceptable where a property test or golden file can prove it; reckless where correctness is a matter of unbounded edge cases with no oracle and the library is available under a usable licence.

| Component | Library available? | Custom risk | Verdict |
|---|---|---|---|
| Constraint solver | None usable on all targets (planegcs LGPL/C++, SolveSpace GPL) | High: singular Jacobians, redundancy detection, drag stability. Mitigable: kotest-property can assert "solved geometry satisfies all constraints within tolerance", DOF counts agree with rank, and compare against planegcs/SolveSpace as an external oracle in a one-off spike (not shipped). | **Viable, with conditions**: build, but only after a spike, a fixed constraint subset for v1, and the oracle comparison. Reckless without it. |
| Stroke smoothing | perfect-freehand (MIT), no Kotlin port | Low if ported with golden tests from the JS output; medium if re-invented | **Recommended**: port, do not invent. |
| Geometry / hit-testing | Excalidraw `@excalidraw/math`/element code is MIT (SEARCH: [ecosyste.ms](https://packages.ecosyste.ms/registries/npmjs.org/packages/@excalidraw%2Fmath)); where hit-testing lives was not confirmed. No KMP geometry lib found. | Low-to-medium: point-in-polygon, segment-distance, bezier-nearest, rotated-rect and a spatial index (grid or R-tree) are textbook. Property tests (hit(p) iff dist <= tolerance vs brute-force) give a strong oracle. | **Viable (build)**; port Excalidraw semantics where needed (MIT). |
| `.excalidraw` parsing | Format documented (SEARCH: [JSON schema doc](https://docs.excalidraw.com/docs/codebase/json-schema)); kotlinx-serialization already on classpath | Low for structure; the risk is fidelity (versioned fields, unknown element types, image `files` map). Untrusted input: size caps, tolerate unknown keys, no remote fetch. | **Recommended (build)** with real Excalidraw sample files as golden fixtures, plus the export-import round-trip property test. |
| Arrow binding / routing, text layout | None | Medium-high, but behavioural not numerical | Build; use Excalidraw behaviour as a spec. |

When custom code is reckless: (a) no external oracle or property to test against; (b) numerically fragile and load-bearing for data (solver on user sketches that feed the dimension pipeline) without a tolerance contract and fallback ("could not solve, geometry unchanged"); (c) security-sensitive parsing without fuzz/size-cap tests; (d) a licence-compatible library exists on every target. None of the five rows meets (d) except stroke smoothing, which is why it is ported rather than invented.

## (4) Fork / adapt
- **Port Excalidraw logic (MIT)** for freedraw, arrow-binding heuristics, element schema and math helpers: **Recommended as selective porting**, with MIT notice retained, never wholesale (architecture mismatch, React/Jotai state, canvas 2D API).
- **Fork tldraw**: **Not recommended** (licence above).
- **Fork planegcs to Kotlin**: **Not recommended as a code port** (LGPL derivative); **Viable as clean-room reimplementation** guided by its documented algorithm set (DogLeg, LM, BFGS, constraint list).
- **Fork a KMP drawing lib (DrawBox etc.)**: **Not recommended**; nothing worth inheriting.

## Recommendation
Build a pure-commonMain engine on the existing stack (Compose Canvas, kotlinx-serialization, kotest-property) and add **no new heavy dependency**. Port perfect-freehand (MIT) and, if the owner opts in, a rough.js subset (MIT). Write a bespoke dense-matrix LM solver behind a `ConstraintSolver` interface, gated by a spike (Jacobian rank, redundancy detection, drag stability, <50 ms for 100 constraints on wasmJs) with planegcs/SolveSpace used only as an offline test oracle, never linked or copied. Parse `.excalidraw` with kotlinx-serialization against real Excalidraw fixtures. An ADR should record: (1) no WebView; (2) MIT ports and NOTICE handling; (3) the LGPL/GPL clean-room boundary.

## Gaps
- Repo `LICENSE` not read, so GPL/LGPL compatibility with SteleKit's own licence is unchecked.
- wasm/native buildability of planegcs not confirmed either way; no Kotlin ports of perfect-freehand or rough.js found by search, not by an exhaustive GitHub scan.
- Maintenance activity of Multik, DrawBox and the WebView wrappers was not checked beyond search snippets.
- Where Excalidraw's hit-testing code lives (`@excalidraw/math` vs element package) was not confirmed.
