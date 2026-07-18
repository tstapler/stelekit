# Changelog

## [0.71.1](https://github.com/tstapler/stelekit/compare/v0.71.0...v0.71.1) (2026-07-18)


### Bug Fixes

* **test:** close QrTransferCoordinatorTest's real event-capture race with CoroutineStart.UNDISPATCHED ([#246](https://github.com/tstapler/stelekit/issues/246)) ([56a3d3d](https://github.com/tstapler/stelekit/commit/56a3d3d588c7e21ae489497b9c6f9686600c52a7))

## [0.71.0](https://github.com/tstapler/stelekit/compare/v0.70.0...v0.71.0) (2026-07-18)


### Features

* **web:** local folder live sync (write-through, external-change detection, cross-tab locks) ([#245](https://github.com/tstapler/stelekit/issues/245)) ([da00bae](https://github.com/tstapler/stelekit/commit/da00bae141255e46044e5eedd4ac8756706e9516))


### Bug Fixes

* **ui:** resolve tap-vs-selection race by consolidating gesture recognizers per block row ([#240](https://github.com/tstapler/stelekit/issues/240)) ([dc94048](https://github.com/tstapler/stelekit/commit/dc94048f63ff051d5720d45855577df70144a8c3))

## [0.70.0](https://github.com/tstapler/stelekit/compare/v0.69.1...v0.70.0) (2026-07-16)


### Features

* **drag-reorder:** implement UX punch list for block reordering ([#237](https://github.com/tstapler/stelekit/issues/237)) ([2d624d4](https://github.com/tstapler/stelekit/commit/2d624d4a87fb149b046065af6234371cd076b332))
* **git:** implement WASM git write-back for web sync (closes BUG-005 Phase 2) ([#239](https://github.com/tstapler/stelekit/issues/239)) ([bb063ef](https://github.com/tstapler/stelekit/commit/bb063efedafc335a28b248597fe2943524132cc4))
* **links:** index parenthetical content as a page-name alias too ([c2658b2](https://github.com/tstapler/stelekit/commit/c2658b21bb646ca5c1a8ef6cb4ec4ff91994f46c))
* **web:** PWA install + offline durability for the browser app ([f8608b8](https://github.com/tstapler/stelekit/commit/f8608b82979c09578e13f37b17eb19711ad24de4))


### Bug Fixes

* **android:** drag-to-reorder blocks never actually moved (stelekit[#238](https://github.com/tstapler/stelekit/issues/238)) ([98b36a9](https://github.com/tstapler/stelekit/commit/98b36a9b8856cb52ec43d57e5ad35c9717d87452))
* **ci:** satisfy detekt ComplexCondition in PageNameIndex alias registration ([6426ecb](https://github.com/tstapler/stelekit/commit/6426ecb03cab55be57a19a5c610233e5bd533cad))
* **db:** remove O(pages×blocks) UPDATE from pages_backlink_count migration ([cdcff5e](https://github.com/tstapler/stelekit/commit/cdcff5ef02c2e52c757fc8d6e46b50686ec4405a))
* **migration:** handle concurrent switchGraph races on migration_changelog ([d63b882](https://github.com/tstapler/stelekit/commit/d63b88289a474bc5a04e8a3137b9eaa7ada328cf))
* **pages:** replace evictable cache fallback with artifact download for web demo ([7c704fc](https://github.com/tstapler/stelekit/commit/7c704fcddb32a29f9a89836dbee5c464790db8d4))
* **parser:** fix exponential blowup in InlineParser on unmatched brackets ([11b9e62](https://github.com/tstapler/stelekit/commit/11b9e6270442ccd4dffe2d3c718d69fc60c612f7))
* **test:** eliminate lost-event race in QrTransferCoordinatorTest ([eaaf0b3](https://github.com/tstapler/stelekit/commit/eaaf0b32be8e7171be6d5f127ee378d1d56cdef6))
* **web:** prevent the coming-soon regression from recurring, protect existing clients from bad deploys ([47b97c1](https://github.com/tstapler/stelekit/commit/47b97c11821f1aa39ce252acc6fc9338442ce809))
* wiki-link insertion ordering bugs ([#234](https://github.com/tstapler/stelekit/issues/234)) ([001942a](https://github.com/tstapler/stelekit/commit/001942a5222025c9e763bbcb3f68b371ef100479))


### Performance Improvements

* **ci:** enable persistent/multiplexed workers for Android dex/desugar ([9c24454](https://github.com/tstapler/stelekit/commit/9c244541ad74cb2549b3e8795eb2a0818e725502))


### Reverts

* **ci:** drop persistent_multiplex_android_tools, breaks Bazel Android build ([94f44db](https://github.com/tstapler/stelekit/commit/94f44db6ecaaf05842f39f669b24c34406b54f16))

## [0.69.1](https://github.com/tstapler/stelekit/compare/v0.69.0...v0.69.1) (2026-07-14)


### Bug Fixes

* **detekt:** remember(context) for PreviewView constructor arg ([331b3ca](https://github.com/tstapler/stelekit/commit/331b3cab04856d48ad85e64180ea7d4eda4e8031))

## [0.69.0](https://github.com/tstapler/stelekit/compare/v0.68.0...v0.69.0) (2026-07-13)


### Features

* **android:** AndroidCameraFrameSource via CameraX ImageAnalysis ([80a7c99](https://github.com/tstapler/stelekit/commit/80a7c996df750966625c3142d770b54911357e6f))
* **android:** replace headless capture with CameraX viewfinder dialog ([0daeeae](https://github.com/tstapler/stelekit/commit/0daeeae0e199a484b06a4d52ceffa02353d569db))
* **desktop:** wire Send via QR entry point + JVM round-trip coverage ([9b537e8](https://github.com/tstapler/stelekit/commit/9b537e8cdc244af1c33708917061ef98811afe48))
* **ios:** CoreImage QrCodec encode actual + send entry point wiring ([c87ad73](https://github.com/tstapler/stelekit/commit/c87ad73b91f263d8d41ac51c2fa240ac5a88f007))
* **qr-decode:** render a real CameraX preview behind the scan reticle ([375db50](https://github.com/tstapler/stelekit/commit/375db5011aa82dcf9f42f91577246837b96929a1))
* **sensor:** CameraFrameSource interface and no-op default ([8c73967](https://github.com/tstapler/stelekit/commit/8c7396777f49bf250da691e599b3af9c55e347ac))
* **transfer:** air-gapped QR page transfer between devices ([3899224](https://github.com/tstapler/stelekit/commit/389922467862b0e8008884a788e8a58d87cda263))
* **transfer:** carry real page name through the QR wire payload, closing the synthesized-placeholder-name gap ([5e5ca25](https://github.com/tstapler/stelekit/commit/5e5ca258d77b02444b50d45e55771798dc8269da))
* **transfer:** distinguish locked-on vs searching reticle state (validation.md criterion 14) ([130149c](https://github.com/tstapler/stelekit/commit/130149c19ac2d4b2c08654a0e1db75c088f76b06))
* **transfer:** fountain codec and chunk buffer with proof-gated reassembly ([a4f53dc](https://github.com/tstapler/stelekit/commit/a4f53dc86a658d918d8ac9eed9c67c4a797e6c2d))
* **transfer:** FrameTransport seam, QrCodec ZXing actuals, QrFrameTransport adapter ([498bf44](https://github.com/tstapler/stelekit/commit/498bf44613929abe810eb6fe05c8a8e805c566fb))
* **transfer:** protocol core foundation — serializer, error types, newtypes, air-gap guard ([b13f27b](https://github.com/tstapler/stelekit/commit/b13f27b7e8336492e5d838c4f9b94ba37167276f))
* **transfer:** QR decoder coordinator, ViewModel, screen, and import service ([4a01b82](https://github.com/tstapler/stelekit/commit/4a01b822884f0e7ed419d9b19fa1ed4a5d600580))
* **transfer:** QR encoder ViewModel, screen, and send entry point ([7ab5030](https://github.com/tstapler/stelekit/commit/7ab5030a39bfeb742ef99200c2dd6c2d4ed75959))
* **transfer:** round-trip fidelity gate, stall timer, structured logging, concurrent-sender handling ([464f748](https://github.com/tstapler/stelekit/commit/464f7485c345c547598d8643528f4b8d6c497262))


### Bug Fixes

* **build:** add kotlin-reflect to androidUnitTest dependencies ([e08475d](https://github.com/tstapler/stelekit/commit/e08475d48719d9272b372f32326cbc72df51e34d))
* **build:** wire camera-view Bazel dep, add missing wasmJs QrCodec actual ([680f468](https://github.com/tstapler/stelekit/commit/680f468cea66012323f21dfd35c7ace425c3c124))
* **qr-decode:** fix subscription race in shared camera-flow (CI flake root cause) ([3143f3e](https://github.com/tstapler/stelekit/commit/3143f3ea64e928f7546ea44e78d4fc514f853d2e))
* **qr-decode:** reach PermissionDenied preflight and show distinct copy per reason ([754831e](https://github.com/tstapler/stelekit/commit/754831e6ea3ac157ee1c9f83243d7d02f4dbc109))
* **qr-decode:** share one camera-flow subscription, bind additively, volatile coordinator fields ([e052539](https://github.com/tstapler/stelekit/commit/e0525391f6b9bbe22aca2aa1d3a0595a5db45284))
* **qr-decode:** unique per-import pagePath and non-destructive overwrite rollback ([aa4a0dd](https://github.com/tstapler/stelekit/commit/aa4a0dd1eba4271818c4340b1c637d2ab7e4a7d5))
* **qr-decode:** wire PayloadTooLarge decode-side, remove dead IncompleteTransfer/TransferCancelled ([92da8a4](https://github.com/tstapler/stelekit/commit/92da8a441940bc7919796ce439075d211671856d))
* **qr-transfer:** reject oversized payloads before ZXing render in QrCodec.jvm ([f8c812d](https://github.com/tstapler/stelekit/commit/f8c812d2d8fa110db44b9f22414f8d6d83ea51e6))
* **transfer:** address PR [#221](https://github.com/tstapler/stelekit/issues/221) Copilot review comments ([c8360e6](https://github.com/tstapler/stelekit/commit/c8360e62a68bcb84f9855fc57b1b4539ed40adbb))
* **transfer:** bound fountain decoder work/memory against adversarial chunk sizes ([5fc5172](https://github.com/tstapler/stelekit/commit/5fc517289db2a811d60858c3661274a5d0c8f20e))
* **transfer:** rate-limit reduce-motion tap-to-advance to WCAG 2.3.1 2fps ceiling ([c8f96ae](https://github.com/tstapler/stelekit/commit/c8f96ae474083c33fd8341213b74559bacb67507))
* **transfer:** resolve detekt Compose-rules findings blocking CI ([cd43348](https://github.com/tstapler/stelekit/commit/cd43348d3c6eb79fe1c1c512076ade199e09ea9e))
* **transfer:** volatile QrEncodeViewModel pacing state for cross-thread visibility ([c463261](https://github.com/tstapler/stelekit/commit/c46326117471f978e640e5b75599d6df09e2dc53))
* **transfer:** widen ZXing try/catch, volatile TransferSession fields ([46f5e52](https://github.com/tstapler/stelekit/commit/46f5e52503034517d10c36d0aaa970d06860613f))
* **transfer:** wire Import via camera entry point (decode-side equivalent of 9b537e8cdc) ([e90f253](https://github.com/tstapler/stelekit/commit/e90f253b4832f16164834ad72f3a99f61d771d12))

## [0.68.0](https://github.com/tstapler/stelekit/compare/v0.67.0...v0.68.0) (2026-07-11)


### Features

* **suggestions:** unified inbox for matcher and LLM suggestions on page scan ([61b894c](https://github.com/tstapler/stelekit/commit/61b894cffec8bdee684ebb7b7e46c32d6af6ddca))

## [0.67.0](https://github.com/tstapler/stelekit/compare/v0.66.0...v0.67.0) (2026-07-10)


### Features

* **assets:** pinch-zoom fixes, background swatches, and measurement annotate on image viewer ([ac1eef9](https://github.com/tstapler/stelekit/commit/ac1eef9289537c80dd526d45907fc3bd2cbb58bb))


### Bug Fixes

* **assets:** clip zoomed image to the viewer's bounds ([fd9dd23](https://github.com/tstapler/stelekit/commit/fd9dd231dfba506324b9f2f962dd9e0e47edcd97))
* **assets:** create matching Block for synthesized annotations, move switchGraph close off UI thread ([614da88](https://github.com/tstapler/stelekit/commit/614da885e2bf1d9554d50253ecc2edef2047a940))
* **db:** don't let a stale-factory close failure abort switchGraph ([f24fa1e](https://github.com/tstapler/stelekit/commit/f24fa1eb0849015ce52144c9ae80248574b3ecb3))
* **desktop:** isolate ./gradlew run from the real graph, checkpoint WAL on close ([d45acf8](https://github.com/tstapler/stelekit/commit/d45acf87d9198665a017f5151bb65d8c89a73e1a))

## [0.66.0](https://github.com/tstapler/stelekit/compare/v0.65.1...v0.66.0) (2026-07-10)


### Features

* **tags:** add explicit Scan button for bulk journal LLM tag suggestions ([2681e98](https://github.com/tstapler/stelekit/commit/2681e98bb274f9388a3887586e359af95d3b8413))
* **tags:** background LLM suggestions with app-startup preload and result cache ([b307c72](https://github.com/tstapler/stelekit/commit/b307c729db03f12941f3a73d2ed2c2e48d8ce331))


### Bug Fixes

* **lint:** resolve 3 Detekt violations from previous commits ([b234527](https://github.com/tstapler/stelekit/commit/b2345271b4c63f80fdc53d6700e7486e8e3b62d3))

## [0.65.1](https://github.com/tstapler/stelekit/compare/v0.65.0...v0.65.1) (2026-07-10)


### Bug Fixes

* **tags:** preload on-device model and increase suggestion timeout to 90s ([e080c8d](https://github.com/tstapler/stelekit/commit/e080c8d96c4ae8b3762bd1980c9876b5ef851ec7))

## [0.65.0](https://github.com/tstapler/stelekit/compare/v0.64.0...v0.65.0) (2026-07-09)


### Features

* **journals:** add per-entry overflow menu with export and suggest tags ([4daee78](https://github.com/tstapler/stelekit/commit/4daee78915aefe920608f1198df35d1bf647f73f))


### Bug Fixes

* **mobile:** fix block drag conflicting with selection mode, add Select All ([c96fdde](https://github.com/tstapler/stelekit/commit/c96fdded7bb9695f858ed6b09c3c6a5d44bafed9))
* **tags:** trigger ML Kit model download and include block content in prompt ([19efb39](https://github.com/tstapler/stelekit/commit/19efb39f3549301b36fa82d33eb1e39534cbd83e))

## [0.64.0](https://github.com/tstapler/stelekit/compare/v0.63.0...v0.64.0) (2026-07-07)


### Features

* **web:** demo graph as dedicated named graph in switcher ([27bef53](https://github.com/tstapler/stelekit/commit/27bef530e3959a3887e602abe5878e23668e4401))


### Bug Fixes

* **db:** make GraphManager registry updates atomic to fix flaky lifecycle tests ([81b6db3](https://github.com/tstapler/stelekit/commit/81b6db34b31147d33675ed52690a7524ba660b43))

## [0.63.0](https://github.com/tstapler/stelekit/compare/v0.62.0...v0.63.0) (2026-07-07)


### Features

* **editor:** rich editing experience overhaul ([#208](https://github.com/tstapler/stelekit/issues/208)) ([afc94fd](https://github.com/tstapler/stelekit/commit/afc94fd3276bef7a9f9e3cd8c683b3c06c9e5a91))


### Bug Fixes

* **build:** wire generateDemoFileSystem to the real Android compile tasks ([#211](https://github.com/tstapler/stelekit/issues/211)) ([08afa7f](https://github.com/tstapler/stelekit/commit/08afa7f4cd36ef27461602b07293fc617049ab66))

## [0.62.0](https://github.com/tstapler/stelekit/compare/v0.61.0...v0.62.0) (2026-07-07)


### Features

* **web:** wire app icon as browser favicon ([4a22a71](https://github.com/tstapler/stelekit/commit/4a22a71ed0e3dd99e033be37689a9f06745264e3))


### Bug Fixes

* **release:** collapse AppImage classpath to avoid launcher buffer overflow ([#206](https://github.com/tstapler/stelekit/issues/206)) ([91e087b](https://github.com/tstapler/stelekit/commit/91e087bd4628b1a328b3337ade56bb7d4872fd08))
* **ui:** resolve Detekt failures blocking main CI ([#209](https://github.com/tstapler/stelekit/issues/209)) ([1a0af50](https://github.com/tstapler/stelekit/commit/1a0af50766bf17552af3819b013c9949a31e8c72))

## [0.61.0](https://github.com/tstapler/stelekit/compare/v0.60.0...v0.61.0) (2026-07-05)


### Features

* **demo:** add demo graph isolation — in-memory sandbox with no disk writes ([b3c2fa1](https://github.com/tstapler/stelekit/commit/b3c2fa12c1af492fe63412645cbd613e027c61c0))


### Bug Fixes

* **ui:** resolve four UX bugs in DiskConflictDialog ([#203](https://github.com/tstapler/stelekit/issues/203)) ([7d72fd0](https://github.com/tstapler/stelekit/commit/7d72fd0282cd5d9ecf910843a1fa4b1be3c70cf7))

## [0.60.0](https://github.com/tstapler/stelekit/compare/v0.59.1...v0.60.0) (2026-07-05)


### Features

* **wasm:** seed demo content on first load; add directory import logging ([ee7947c](https://github.com/tstapler/stelekit/commit/ee7947c9711458ee00a6ea67c49802f0a2840495))


### Bug Fixes

* **db:** prevent JDBC pool deadlock in OperationLogger ([5c7b961](https://github.com/tstapler/stelekit/commit/5c7b96175b95522a13a9aebbc507f4c4f313a879))
* **observability:** remove SpanLogSink from LogManager on factory close ([3911134](https://github.com/tstapler/stelekit/commit/3911134d11550d8c1d05bd1cf463342fca2f07bf))
* **quality:** reflect-and-fix enforcement for this session's bugs ([84148bd](https://github.com/tstapler/stelekit/commit/84148bdd55ce6ab3d340d925685a250ed5072421))
* update tests and call sites for type-driven design refactors ([610de21](https://github.com/tstapler/stelekit/commit/610de216d4077b109e9512949277f73516edc115))
* **wasm:** fix graph switch stuck on Initializing overlay ([f87254c](https://github.com/tstapler/stelekit/commit/f87254cc84b7715612f3a06de264b5ff44ac4450))
* **wasm:** replace all sync executeAsList/executeAsOne/executeAsOneOrNull with async equivalents ([6f49ffa](https://github.com/tstapler/stelekit/commit/6f49ffa1d0cbc0a5b1c6d1d8f7d7dd6e7f363d4d))
* **wasm:** resolve await unresolved reference for showDirectoryPicker ([0c8b194](https://github.com/tstapler/stelekit/commit/0c8b19483d0bfee043fcbf8212c0ba7ec0833d17))
* **wasm:** use println instead of appLogger in onAddGraph lambda ([f5b237c](https://github.com/tstapler/stelekit/commit/f5b237ca1e0498214f524abc4b85090b29581654))
* **web:** restore OPFS persistence and wire up native directory picker ([f456513](https://github.com/tstapler/stelekit/commit/f4565134ad78070d995faf441da3e7877c535f1a))

## [0.59.1](https://github.com/tstapler/stelekit/compare/v0.59.0...v0.59.1) (2026-07-03)


### Bug Fixes

* **ci:** resolve CI failures, skip warm-start ANALYZE, add init timing logs ([#200](https://github.com/tstapler/stelekit/issues/200)) ([7f9ecbd](https://github.com/tstapler/stelekit/commit/7f9ecbdbcdd77b97acd085c9b657124f5e49ba30))
* **ui:** hide edit affordance for on-device provider rows ([b01ed75](https://github.com/tstapler/stelekit/commit/b01ed7574c223c9b30bc42b0c16a505e777dc01b))
* **ui:** show actual LLM error message; skip API key dialog for on-device providers ([bc0de57](https://github.com/tstapler/stelekit/commit/bc0de57fdc4c5051bb3e0b0952f2a90cc3488a63))
* **ui:** show actual LLM error; skip API key dialog for on-device providers ([49880fc](https://github.com/tstapler/stelekit/commit/49880fcc4180ac5ba64c041ffd7540dae16d5df5))
* **ui:** use allowlist (== REMOTE) not denylist (!= ON_DEVICE) in onEditProvider ([59c6e52](https://github.com/tstapler/stelekit/commit/59c6e5257cb9954aa3365a7a2ed39ff524164ad1))

## [0.59.0](https://github.com/tstapler/stelekit/compare/v0.58.1...v0.59.0) (2026-07-02)


### Features

* **llm:** unified multi-provider LLM abstraction, on-device parity, approval-gated edits ([3bd02fe](https://github.com/tstapler/stelekit/commit/3bd02fe926639ae4c9a154c52c8ba97ad13791a3))


### Bug Fixes

* **ci:** split Roborazzi screenshot tests out of Bazel's jvm_tests glob ([97bcda2](https://github.com/tstapler/stelekit/commit/97bcda2a8c6987a0621ac394a1a085d120a3267e))

## [0.58.1](https://github.com/tstapler/stelekit/compare/v0.58.0...v0.58.1) (2026-07-02)


### Bug Fixes

* **ci:** resolve pre-existing CI failures on main ([#198](https://github.com/tstapler/stelekit/issues/198)) ([9bd0280](https://github.com/tstapler/stelekit/commit/9bd028020c6c0bc567fddb3fb2e54b28d64de6d0))
* **db:** remove raw BEGIN/COMMIT from pages_section_id migration + pool hardening ([#195](https://github.com/tstapler/stelekit/issues/195)) ([24fcbc3](https://github.com/tstapler/stelekit/commit/24fcbc30b4f0acd102d03d23ccebfbcef9ea5221))

## [0.58.0](https://github.com/tstapler/stelekit/compare/v0.57.0...v0.58.0) (2026-07-01)


### Features

* **assets:** fully implement asset browser — thumbnails, detail, sort, pagination, tags, orphan, empty state + camera fix ([#188](https://github.com/tstapler/stelekit/issues/188)) ([02daad2](https://github.com/tstapler/stelekit/commit/02daad284c7d1074d4f219be72b534c6daa70a06))
* **wasm:** close browser platform gaps — image picker, share, file download, graph dialog ([#191](https://github.com/tstapler/stelekit/issues/191)) ([c10dd8c](https://github.com/tstapler/stelekit/commit/c10dd8cf9e14844a5021d59f21f6680363a69787))

## [0.57.0](https://github.com/tstapler/stelekit/compare/v0.56.0...v0.57.0) (2026-07-01)


### Features

* **clipboard:** block reorder persistence, copy-paste with CUT support, and algorithm extraction ([#192](https://github.com/tstapler/stelekit/issues/192)) ([5b7f639](https://github.com/tstapler/stelekit/commit/5b7f63979d3c0e8b739f7585c4c53ec0f7297511))

## [0.56.0](https://github.com/tstapler/stelekit/compare/v0.55.0...v0.56.0) (2026-06-24)


### Features

* **bazel:** migrate build system to Bazel with full CI ([#149](https://github.com/tstapler/stelekit/issues/149)) ([8b4cb25](https://github.com/tstapler/stelekit/commit/8b4cb25af002a4c2021c37fe0c6943dd67c686fc))
* **tags:** wire Suggest Tags button to MobileBlockToolbar ([#185](https://github.com/tstapler/stelekit/issues/185)) ([fb0ddc0](https://github.com/tstapler/stelekit/commit/fb0ddc053a90affd59a01fd45d02a6754902fe57))

## [0.55.0](https://github.com/tstapler/stelekit/compare/v0.54.0...v0.55.0) (2026-06-23)


### Features

* **imagemeter:** loupe magnifier, ripple, context hints, toolbar UX overhaul ([#183](https://github.com/tstapler/stelekit/issues/183)) ([1cc2b99](https://github.com/tstapler/stelekit/commit/1cc2b998cb765b9865edaa01defd34ecd03ef8f3))

## [0.54.0](https://github.com/tstapler/stelekit/compare/v0.53.1...v0.54.0) (2026-06-22)


### Features

* **sensor:** wire AndroidMotionSensorProvider and OnnxMonocularDepthEstimator at startup ([7883861](https://github.com/tstapler/stelekit/commit/78838612444110b9e0a347e45dcb9c7cb3ad1f53))


### Bug Fixes

* **calibration:** correct 1000× unit mismatch in ARCore and LiDAR depth providers ([1fcfbfe](https://github.com/tstapler/stelekit/commit/1fcfbfe2786f27844f358a1d297b1e261c207452))
* **capture:** show loading state while import runs, block premature dismiss ([54e9e55](https://github.com/tstapler/stelekit/commit/54e9e55fbdb6ce71a056ff9b9b03ef54a564db8e))
* **git-sync:** triad review fixes — schema, nav, UX, accessibility ([533fbba](https://github.com/tstapler/stelekit/commit/533fbbae030dd2e3f679fc376a900fb34b121b9a))
* **imagemeter:** repair three broken image behaviors on Android SAF graphs ([66ce7ca](https://github.com/tstapler/stelekit/commit/66ce7cab522b5c0469fb537076a6cae2f84c4e96))
* **journals:** provide LocalGraphRootPath inside JournalsView for image rendering ([d6dc254](https://github.com/tstapler/stelekit/commit/d6dc254cef8e227253d1061df94a0370d7de749e))
* **sensor:** enforce correctness structurally after reflect-and-fix ([db540fb](https://github.com/tstapler/stelekit/commit/db540fbb910c118bef73524c6741d7a0fb28d540))

## [0.53.1](https://github.com/tstapler/stelekit/compare/v0.53.0...v0.53.1) (2026-06-22)


### Bug Fixes

* **android:** fix SAF image display and gallery attachment ([#181](https://github.com/tstapler/stelekit/issues/181)) ([009857d](https://github.com/tstapler/stelekit/commit/009857d258a68b93ad9f845f52c7bb67e113d60f))
* **test:** time the hot-read path in GraphLoadTimingTest, not the cold write ([da309b0](https://github.com/tstapler/stelekit/commit/da309b01fbb0e15c6d7c293bcc24b8168d6d4519))

## [0.53.0](https://github.com/tstapler/stelekit/compare/v0.52.2...v0.53.0) (2026-06-21)


### Features

* **android:** switch to bundled SQLite with pluggable driver provider ([5a191f4](https://github.com/tstapler/stelekit/commit/5a191f48ad60a86120b22c81f2152e1852941ae0))


### Bug Fixes

* **android:** handle FTS5-unavailable devices on fresh install ([7b76394](https://github.com/tstapler/stelekit/commit/7b7639460db21adacf5eff23693f997366437a87))
* **ci:** remove backslash line continuations in android-benchmark script ([850a84a](https://github.com/tstapler/stelekit/commit/850a84a6502aafb7a002b57e7fa72b8a200c1329))
* **test:** include required created_at/updated_at in SqliteCapabilityTest INSERTs ([3299f8d](https://github.com/tstapler/stelekit/commit/3299f8d2b3884adbcd08845aa6cbb704df80d669))
* **test:** use spaced page name in FTS5 end-to-end test ([a87011d](https://github.com/tstapler/stelekit/commit/a87011d12c3ff27b09062a087d33ca956aad0ccd))

## [0.52.2](https://github.com/tstapler/stelekit/compare/v0.52.1...v0.52.2) (2026-06-21)


### Bug Fixes

* **db:** drop stale FTS5 triggers programmatically on FTS5-unavailable devices ([dcdffa5](https://github.com/tstapler/stelekit/commit/dcdffa5992d4894855de35e7ee5c537e166c423c))
* **db:** guard FTS5 triggers with WHEN clause to prevent crashes on devices lacking FTS5 ([7b08455](https://github.com/tstapler/stelekit/commit/7b084556ed654646c6677f1a82d9e1b15cb3f147))

## [0.52.1](https://github.com/tstapler/stelekit/compare/v0.52.0...v0.52.1) (2026-06-21)


### Bug Fixes

* **watcher:** remove racy mtime update from parseAndSavePage ([295fd1a](https://github.com/tstapler/stelekit/commit/295fd1afab820a8bf296eaa160c7f221ff048a4e))

## [0.52.0](https://github.com/tstapler/stelekit/compare/v0.51.0...v0.52.0) (2026-06-21)


### Features

* **db:** add libsql JNI driver with MVCC support for JVM and Android ([#171](https://github.com/tstapler/stelekit/issues/171)) ([18d8250](https://github.com/tstapler/stelekit/commit/18d8250cf3f6141fce243c4bca57ce7151797090))
* **db:** migrate wikilink_references to WITHOUT ROWID (Epic 5) ([3cdd025](https://github.com/tstapler/stelekit/commit/3cdd025078247c2dff219139e3315f05d5dcfbe3))
* **model:** BlockType sealed class with Unknown(raw) fallback (Epic 2) ([9393b9a](https://github.com/tstapler/stelekit/commit/9393b9aef0fce6f737004b1931429baf19defa47))


### Bug Fixes

* **android:** call enableWriteAheadLogging() to unlock SQLiteConnectionPool multi-reader ([6fb248c](https://github.com/tstapler/stelekit/commit/6fb248cfd3959e8c5311d2b4e1ae0c5b6ba55ac1))
* **android:** update CaptureViewModel to use fractional position; remove unused logger ([7f11b70](https://github.com/tstapler/stelekit/commit/7f11b705c6521b8e46e4d59950bf7b4630c14458))
* **bench:** pass benchConfig as Gradle project property not JVM system property ([db008d2](https://github.com/tstapler/stelekit/commit/db008d229dca7b2984331b6dae1393eac4b8b81a))
* post-merge compile errors — blockTypeFromString in splitBlock, CancellationException rethrow ([4e0521d](https://github.com/tstapler/stelekit/commit/4e0521d3ee0545e65defa43d0069ee03f6ad37e3))
* **test:** update PhotoInsertAndroidTest to compare BlockType sealed class not String ([fc8167e](https://github.com/tstapler/stelekit/commit/fc8167e15138542107866994854ebc72d8dfa74d))


### Performance Improvements

* **android:** eliminate SAF getLastModifiedTime from own-write path ([f6ba685](https://github.com/tstapler/stelekit/commit/f6ba6851685b63a2042822982ea1d6eef2ae5d6c))
* **android:** fire-and-forget SAF modtime update after parseAndSavePage ([5c599c6](https://github.com/tstapler/stelekit/commit/5c599c6fe9871242636f6f2efc621997137b9ec4))
* **android:** replace Requery with FrameworkSQLiteOpenHelperFactory (Epic 7) ([08bb18b](https://github.com/tstapler/stelekit/commit/08bb18b5f6e77c53735bf5c27bfd46dc926e5503))
* **android:** separate WAL read connection eliminates read/write lock contention ([a6eda9f](https://github.com/tstapler/stelekit/commit/a6eda9f0d0fabf6cf2b6ec917ed6cf154270e64e))
* **android:** yield DB connection between saveBlocks chunks to unblock reads ([6a93847](https://github.com/tstapler/stelekit/commit/6a9384711b75798f062ec44c6c0ca2bc295c8540))
* **db:** batch wikilink inserts — N individual INSERTs → 1 multi-row (Epic 4) ([a593c93](https://github.com/tstapler/stelekit/commit/a593c937715f549d9f2a046b602e5fa89f527451))
* **db:** eliminate O(n) sibling position shifts and N-call backlink recomputes ([83155e5](https://github.com/tstapler/stelekit/commit/83155e51769f34f23ae0c1c5ab86e86916e29a39))
* **db:** fix Android editor lag — separate wikilink index pass from block inserts ([a904b5d](https://github.com/tstapler/stelekit/commit/a904b5d03d1aca8d6e244ba2117e896794979dd8))
* **db:** fractional string positions for zero-shift block insertion (Epic 6) ([4d843be](https://github.com/tstapler/stelekit/commit/4d843be3f0ca347f2f205a19bd483f735c2b2aca))
* **db:** O(1) move/merge/delete-subtree — batch wikilink collection ([4c24d7a](https://github.com/tstapler/stelekit/commit/4c24d7a29a7fa84d47dbfa71b4d6ef2c9a5808cf))
* **db:** replace getBlockHierarchy BFS with WITH RECURSIVE CTE (Epic 3) ([80b4e33](https://github.com/tstapler/stelekit/commit/80b4e33e2dcfc2322e861046687f23ccea1bb21b))
* **editor:** zero DB reads for hot-path block writes (Phase 3 push events) ([4eeb68b](https://github.com/tstapler/stelekit/commit/4eeb68bcf9062f9f2dc07cd83202e230a22f8180))

## [0.51.0](https://github.com/tstapler/stelekit/compare/v0.50.0...v0.51.0) (2026-06-19)


### Features

* **docs:** enforce documentation coverage for all user-facing screens ([f2194b5](https://github.com/tstapler/stelekit/commit/f2194b568efafb93dbb36b460ca64eaf17316a34))


### Bug Fixes

* **android:** wire camera permission request and binary JPEG copy ([#175](https://github.com/tstapler/stelekit/issues/175)) ([173af54](https://github.com/tstapler/stelekit/commit/173af54028f664e3b8b78affcd48e065f04bea9a))
* **compose:** add missing remember keys for constructor-capturing lambdas ([47cc022](https://github.com/tstapler/stelekit/commit/47cc02296283dc5d72a455f2ab9f74d922f01e31))
* **demo:** suppress false git-detection banner; expand web and resource demo content ([c3015a7](https://github.com/tstapler/stelekit/commit/c3015a70be7c17a29315e4970e0851996017b9d6))
* **detekt:** repair broken PSI visitor recursion in 2 custom rules ([8da2b97](https://github.com/tstapler/stelekit/commit/8da2b972909aa0016de508bba2a3a3e62b195989))

## [0.50.0](https://github.com/tstapler/stelekit/compare/v0.49.2...v0.50.0) (2026-06-19)


### Features

* **db:** add BlockUpdateEvent sealed class and blockInvalidations SharedFlow ([1d998b2](https://github.com/tstapler/stelekit/commit/1d998b258e3c3e5a49cfc46b0a043685927167db))
* **release:** ad-hoc codesign macOS app bundle before DMG packaging ([844271c](https://github.com/tstapler/stelekit/commit/844271c167e18ef3a7c516e2e161bee6555be141))


### Bug Fixes

* **review:** address code review findings from /code:review ([00c9b5e](https://github.com/tstapler/stelekit/commit/00c9b5e675ceaf867204c1ef5ebeb87dd9766ccf))


### Performance Improvements

* **android:** reduce editing latency — debounce observer fanout, eliminate hot-path FTS merges, fix journal title nav ([05b5978](https://github.com/tstapler/stelekit/commit/05b597844ce62ee342f510fd7d6fb9ad66e77f01))
* **db:** add wikilink_references index for O(1) backlink counting ([386ffe9](https://github.com/tstapler/stelekit/commit/386ffe9a55ce889fbd0f3935cf3c1bec50673898))
* **db:** Phase 2 push payload — zero DB re-query for hot-path in-app block edits (ADR-012 Epic 3) ([667715a](https://github.com/tstapler/stelekit/commit/667715a4b90e01acf5a5f9755df08bcca9fc5e5d))
* **db:** replace reactive block subscriptions with page-scoped invalidation (ADR-012 Phase 1) ([fb16c1b](https://github.com/tstapler/stelekit/commit/fb16c1b4a80ee1bfafb5d0ae6952b5db1376edb4))

## [0.49.2](https://github.com/tstapler/stelekit/compare/v0.49.1...v0.49.2) (2026-06-18)


### Bug Fixes

* **db:** eliminate SQLITE_BUSY_SNAPSHOT by using BEGIN IMMEDIATE for all JVM transactions ([e341140](https://github.com/tstapler/stelekit/commit/e3411409d01149d69026db2d2ed13637a889fb13))


### Performance Improvements

* **db:** move FTS merge from per-save to post-bulk-index ([ea67faa](https://github.com/tstapler/stelekit/commit/ea67faac97c99d7cb708f5e8ae2b58084613d444))

## [0.49.1](https://github.com/tstapler/stelekit/compare/v0.49.0...v0.49.1) (2026-06-18)


### Bug Fixes

* **detekt:** suppress UnusedPrivateProperty on for-loop signal variable ([ed87003](https://github.com/tstapler/stelekit/commit/ed87003129acd2f4128e1ec31ba6e46c680f55c0))
* **wasm:** extract pragmaOptimizeAndClose expect/actual; debounce QueryStatsCollector ([77c7bbe](https://github.com/tstapler/stelekit/commit/77c7bbe723aa43dd5e73b2e583a99ad14e343596))


### Performance Improvements

* **telemetry:** batch telemetry writes — drain channel, coalesce histograms, single-transaction spans ([8f85ed7](https://github.com/tstapler/stelekit/commit/8f85ed74c78454fc0e4a1f4a41df187d00e3c83e))

## [0.49.0](https://github.com/tstapler/stelekit/compare/v0.48.3...v0.49.0) (2026-06-18)


### Features

* **db:** split telemetry tables into dedicated TelemetryDatabase ([91de722](https://github.com/tstapler/stelekit/commit/91de722edb9486405868dfcc2c406b5cd68f5a64))

## [0.48.3](https://github.com/tstapler/stelekit/compare/v0.48.2...v0.48.3) (2026-06-18)


### Bug Fixes

* **db:** correct PRAGMA optimize mask and call site per SQLite docs ([65ad04c](https://github.com/tstapler/stelekit/commit/65ad04cce8f57faada1e858e0ac62d2bffeb5d70))


### Performance Improvements

* **db:** fix write-actor priority flooding, span placement, and WAL autocheckpoint ([83cf1e8](https://github.com/tstapler/stelekit/commit/83cf1e8227149a297fe6637794b6b16652a11fb5))
* **db:** reduce busy_timeout and Android cache_size per pragma audit ([85e1f3f](https://github.com/tstapler/stelekit/commit/85e1f3f419cc7325e5c214fa3f1b7be37eab308e))

## [0.48.2](https://github.com/tstapler/stelekit/compare/v0.48.1...v0.48.2) (2026-06-18)


### Bug Fixes

* **git:** wire JvmGitRepository on desktop and keep OAuth code visible during polling ([#166](https://github.com/tstapler/stelekit/issues/166)) ([00df575](https://github.com/tstapler/stelekit/commit/00df5755bae06b88b7428fd3fc22740abbdb2f7d))
* **watcher:** journals page live refresh + Android detection reliability ([#165](https://github.com/tstapler/stelekit/issues/165)) ([27bf06d](https://github.com/tstapler/stelekit/commit/27bf06d3a37eea47d186272a560e707877628891))

## [0.48.1](https://github.com/tstapler/stelekit/compare/v0.48.0...v0.48.1) (2026-06-17)


### Performance Improvements

* **db:** batch actor writes, add telemetry spans, and enforce with lint ([637dd41](https://github.com/tstapler/stelekit/commit/637dd416a59061fcfc18dad064ba96d29065f2f3))

## [0.48.0](https://github.com/tstapler/stelekit/compare/v0.47.2...v0.48.0) (2026-06-17)


### Features

* **ci:** serve fdroid APKs via GitHub Releases redirect on Cloudflare Pages ([11da29b](https://github.com/tstapler/stelekit/commit/11da29b91b241ba7a25e0143c287d90029096b88))
* **perf:** show triggering SQL in Query Plans tab; persist samples across drain cycles ([82771b7](https://github.com/tstapler/stelekit/commit/82771b7e893f72fbf1c31bae0b235a6c6e6c5a78))


### Bug Fixes

* **ci:** fix release workflow — build demo on release, reduce APK set, drop deprecations ([2f7dfbb](https://github.com/tstapler/stelekit/commit/2f7dfbb6f95b5277c7d4f4e8067b3353239de079))
* **db:** add SchemaRunner start/complete log lines to db.MigrationRunner ([5918da3](https://github.com/tstapler/stelekit/commit/5918da31d29752b8b8d11d25213249d7ed74bfbb))


### Performance Improvements

* **db:** run ANALYZE blocks/pages unconditionally on every startup ([a81c0c1](https://github.com/tstapler/stelekit/commit/a81c0c1ac1ad48ee84d92addd9fe399941248c71))

## [0.47.2](https://github.com/tstapler/stelekit/compare/v0.47.1...v0.47.2) (2026-06-16)


### Bug Fixes

* **formula:** remove openjdk JVM swap on macOS to fix Empty installation error ([ed5398b](https://github.com/tstapler/stelekit/commit/ed5398bdac07069712ef20eef0c110fe446454c4))
* **ui:** enforce non-null BlockUuid for onAttachImage callback ([1a5b853](https://github.com/tstapler/stelekit/commit/1a5b85370348653f317ddba09b934baf72db5e25))


### Performance Improvements

* **db:** fix SCAN blocks regression, 0-byte export, and reduce write overhead ([e52607f](https://github.com/tstapler/stelekit/commit/e52607f60a0f3bb40088540374040b3f2302451a))

## [0.47.1](https://github.com/tstapler/stelekit/compare/v0.47.0...v0.47.1) (2026-06-16)


### Performance Improvements

* **db:** eliminate O(N²) backlink recompute, add indexes, enforce query plan coverage ([81c0ef7](https://github.com/tstapler/stelekit/commit/81c0ef7d6dde6379296f5ca34b288b9ab06d20be))

## [0.47.0](https://github.com/tstapler/stelekit/compare/v0.46.0...v0.47.0) (2026-06-16)


### Features

* **all-pages:** amber warning icon on conflicted pages in AllPages screen ([921611c](https://github.com/tstapler/stelekit/commit/921611c3b995ee8bc2602f44c88fd4aaadf24252))
* **perf:** export available on all tabs, logs export, fix picker latency, rename Traces→Events tab ([4f9b2c9](https://github.com/tstapler/stelekit/commit/4f9b2c90ba30acd62c837277b5a0ba7b9f763ab8))

## [0.46.0](https://github.com/tstapler/stelekit/compare/v0.45.0...v0.46.0) (2026-06-16)


### Features

* **journals:** amber warning icon on journal entries with pending disk conflicts ([930143e](https://github.com/tstapler/stelekit/commit/930143e21afcc20eaac4effeb26e007543c1c7de))


### Bug Fixes

* background disk conflicts, FTS merge perf, trace export on Android ([a17122b](https://github.com/tstapler/stelekit/commit/a17122b650e0314a9c9dda468d1a3f41983661f9))

## [0.45.0](https://github.com/tstapler/stelekit/compare/v0.44.0...v0.45.0) (2026-06-15)


### Features

* **perf:** gzip export, queryStats/queryPlan in report, perf indexes ([1812d96](https://github.com/tstapler/stelekit/commit/1812d96c5a0c552d58fc22a934b5b15040499e82))
* **perf:** track DB read queue wait and queue depth ([19493e9](https://github.com/tstapler/stelekit/commit/19493e93f3f2fbcf9d5208ed8a03e64b58eb38df))

## [0.44.0](https://github.com/tstapler/stelekit/compare/v0.43.0...v0.44.0) (2026-06-14)


### Features

* **perf:** FTS5 automerge control, diff-aware saves, batch N+1 fix, WAL checkpoint ([#156](https://github.com/tstapler/stelekit/issues/156)) ([7bd8ebe](https://github.com/tstapler/stelekit/commit/7bd8ebe57872895b69a7e9da8c836fc79aa02c47))

## [0.43.0](https://github.com/tstapler/stelekit/compare/v0.42.0...v0.43.0) (2026-06-14)


### Features

* **assets:** asset browser, typed subfolder routing, and ML pipeline ([#153](https://github.com/tstapler/stelekit/issues/153)) ([206fce7](https://github.com/tstapler/stelekit/commit/206fce763bd4836eee731539171d9010361922b5))
* **tags:** two-tier auto-tag suggestion engine for blocks ([#154](https://github.com/tstapler/stelekit/issues/154)) ([94c7af1](https://github.com/tstapler/stelekit/commit/94c7af123734db2ee6beb3fe47291e07055a19d2))


### Bug Fixes

* **android:** detect external file changes when sync tool preserves older timestamps ([65a3a14](https://github.com/tstapler/stelekit/commit/65a3a1471feff45e784b3cdfc1bd6e74f82e88bf))

## [0.42.0](https://github.com/tstapler/stelekit/compare/v0.41.3...v0.42.0) (2026-06-14)


### Features

* **perf:** embed git commit hash in performance exports ([ca301a1](https://github.com/tstapler/stelekit/commit/ca301a1e4bec10b4af3ee5e52fe731697c2a6236))


### Performance Improvements

* auto-discover histogram operations from ring buffer; add slow-case span attributes ([f889392](https://github.com/tstapler/stelekit/commit/f8893920642597c034c891e3b07a59bb9bb17312))
* fix Span.finish() inflating parent durations; add analyze-perf CLI ([626a617](https://github.com/tstapler/stelekit/commit/626a617ac4e6ebba7ce27ae83093d8b8d94875a0))
* instrument file reads and SaveBlocks queue wait for bottleneck diagnosis ([7a5b842](https://github.com/tstapler/stelekit/commit/7a5b8420a4c20dd920b279e8213429d416336cf7))
* tag every span with app.version + app.commit for regression tracking ([84426e3](https://github.com/tstapler/stelekit/commit/84426e3cb31dece23bf1d065e9b565248e2ba555))

## [0.41.3](https://github.com/tstapler/stelekit/compare/v0.41.2...v0.41.3) (2026-06-13)


### Bug Fixes

* **android:** request camera permission at capture time and enable GitHub OAuth device flow ([adb6238](https://github.com/tstapler/stelekit/commit/adb6238652a56f5495a86bfcfa3cd373006651ce))

## [0.41.2](https://github.com/tstapler/stelekit/compare/v0.41.1...v0.41.2) (2026-06-13)


### Bug Fixes

* **ci:** add xvfb-run to release safety gate jvmTest ([69d456e](https://github.com/tstapler/stelekit/commit/69d456e03cd8748752bf7312757816d33a07bb49))
* **git:** use kotlin.concurrent.Volatile in commonMain ([8add86e](https://github.com/tstapler/stelekit/commit/8add86e0f6b2add0e69ecf87f2a0130d2fcab33b))

## [0.41.1](https://github.com/tstapler/stelekit/compare/v0.41.0...v0.41.1) (2026-06-13)


### Bug Fixes

* **git-smart-sync:** address code review findings — safety, correctness, and test coverage ([5086e0f](https://github.com/tstapler/stelekit/commit/5086e0fda0bd5d8e654ef4a908620d0410550e96))

## [0.41.0](https://github.com/tstapler/stelekit/compare/v0.40.4...v0.41.0) (2026-06-12)


### Features

* **editor:** add camera capture button to editor toolbar ([#147](https://github.com/tstapler/stelekit/issues/147)) ([a555aaa](https://github.com/tstapler/stelekit/commit/a555aaa26fd6e7eac082bf7a13496a626f86df03))
* **git-smart-sync:** repo auto-detection, algorithmic journal merge, CLI sync ([6aef1db](https://github.com/tstapler/stelekit/commit/6aef1db5f719674647d3fe12c8483f68b54c888f))
* **git:** native file pickers and GitHub OAuth device flow in git setup ([2b188f2](https://github.com/tstapler/stelekit/commit/2b188f25936028b0bceae682872df378eebc49b7))


### Bug Fixes

* **android-auto:** add service label and sideload discovery guidance ([#146](https://github.com/tstapler/stelekit/issues/146)) ([70d7ad8](https://github.com/tstapler/stelekit/commit/70d7ad80284827b9584576e92b07c259eb1aa68e))
* **cask:** use non-deprecated macos depends_on syntax ([cb3ea88](https://github.com/tstapler/stelekit/commit/cb3ea882e794dad3e57311d26b2173e6f13417fc))
* **git-setup:** triad review fixes — accessibility, tests, Android SSH key wiring ([6c37055](https://github.com/tstapler/stelekit/commit/6c37055752960ae557a61354a6cc49def607fa67))
* **linux:** use wrapper script to run AppImage without FUSE ([3179edb](https://github.com/tstapler/stelekit/commit/3179edb2a8b894046e31c5dfbb317b003116b01c))

## [0.40.4](https://github.com/tstapler/stelekit/compare/v0.40.3...v0.40.4) (2026-06-11)


### Bug Fixes

* **android:** prevent crash when value class used as LazyColumn item key ([#143](https://github.com/tstapler/stelekit/issues/143)) ([3b0caa6](https://github.com/tstapler/stelekit/commit/3b0caa62e64e74d9a3d31eb2827bc0b088f8e0c2))

## [0.40.3](https://github.com/tstapler/stelekit/compare/v0.40.2...v0.40.3) (2026-06-11)


### Bug Fixes

* **android:** stop large-graph startup crash from uncaught Throwables on the ViewModel scope ([#141](https://github.com/tstapler/stelekit/issues/141)) ([dd96cd1](https://github.com/tstapler/stelekit/commit/dd96cd17c92322ce00f28a3752f9737f918afa12))

## [0.40.2](https://github.com/tstapler/stelekit/compare/v0.40.1...v0.40.2) (2026-06-09)


### Bug Fixes

* **android:** prevent OOM crash during large-graph warm reconciliation ([3d674c6](https://github.com/tstapler/stelekit/commit/3d674c6963dda80800499d20a1361ade6af6e853))

## [0.40.1](https://github.com/tstapler/stelekit/compare/v0.40.0...v0.40.1) (2026-06-09)


### Bug Fixes

* **stability:** crash-prevention refactors + fatalError recovery screen ([#138](https://github.com/tstapler/stelekit/issues/138)) ([3c0efac](https://github.com/tstapler/stelekit/commit/3c0efacdd2820664ff1fc6856ef3b1083a0109ad))

## [0.40.0](https://github.com/tstapler/stelekit/compare/v0.39.0...v0.40.0) (2026-06-09)


### Features

* **export:** unified Share/Export dialog with native share sheet and Google Docs ([#133](https://github.com/tstapler/stelekit/issues/133)) ([b5a755a](https://github.com/tstapler/stelekit/commit/b5a755ab9c4e9d9a307ad3cf2ae3fbe11fd558e8))


### Bug Fixes

* **android:** guard switchGraph against cancelling an in-progress init ([275291b](https://github.com/tstapler/stelekit/commit/275291b3047443384b831c70fc8f7d0cc465ba25))

## [0.39.0](https://github.com/tstapler/stelekit/compare/v0.38.4...v0.39.0) (2026-06-09)


### Features

* **android:** add Android Auto audiobook notes screen ([#134](https://github.com/tstapler/stelekit/issues/134)) ([fd08824](https://github.com/tstapler/stelekit/commit/fd088247e2e5770fd12ffa11415a4dcae7225076))


### Bug Fixes

* **android:** stop double-switchGraph race that crashes v0.38.4 on startup ([fd9e073](https://github.com/tstapler/stelekit/commit/fd9e07324e0bc1cd7fc7bff0a599495a8528bab2))
* **ci:** fix fdroid artifact structure and restore F-Droid Pages content ([4a1f1de](https://github.com/tstapler/stelekit/commit/4a1f1ded7063afdd35d00ce79912681f64386c92))
* **ci:** queue pages.yml runs instead of cancelling to protect build-demo ([554e22b](https://github.com/tstapler/stelekit/commit/554e22b8c2c6bd46874ed71093b126de97b66744))
* **ci:** raise build-demo timeout to 60 min and cache Kotlin/Native compiler ([84916cf](https://github.com/tstapler/stelekit/commit/84916cf0e92dc0183f6b2f4fb6e4cae0c986b037))
* **ci:** remove Playwright from build-demo — E2E tests already run in ci.yml ([82d8ffc](https://github.com/tstapler/stelekit/commit/82d8ffc533b9741e205ab9c1a29bc172741fc74a))
* **fdroid:** include APKs in Pages deployment so F-Droid downloads work ([9786555](https://github.com/tstapler/stelekit/commit/9786555ee08e25b8904198623ddcaa8fab38ba83))

## [0.38.4](https://github.com/tstapler/stelekit/compare/v0.38.3...v0.38.4) (2026-06-07)


### Bug Fixes

* **ci:** prevent Pages deploy failures from artifact size and deployment conflicts ([3c23053](https://github.com/tstapler/stelekit/commit/3c23053cd620a12c9e02b43bd172755cc214a8c1))

## [0.38.3](https://github.com/tstapler/stelekit/compare/v0.38.2...v0.38.3) (2026-06-07)


### Bug Fixes

* **android:** complete catchDbError coverage across all repository flows + add regression test ([9e09932](https://github.com/tstapler/stelekit/commit/9e09932b8c191cdb5f7dc05c3c4a14314968cafc))

## [0.38.2](https://github.com/tstapler/stelekit/compare/v0.38.1...v0.38.2) (2026-06-07)


### Bug Fixes

* **android:** add catchDbError to annotation and span repository flows ([e709864](https://github.com/tstapler/stelekit/commit/e709864108705a186c20f1f748f56ac56d1f1eb8))
* **ci:** bump release concurrency group to unblock queued runs ([4d0c112](https://github.com/tstapler/stelekit/commit/4d0c1121b18c9153ce66a485b19e251332f4a359))
* **ci:** cache Playwright browsers to prevent download hang in build-demo ([dc53ee2](https://github.com/tstapler/stelekit/commit/dc53ee2a00d7d56e0b3ee68f201b787a5280d57e))
* **ci:** prevent Playwright install from hanging indefinitely ([01e420b](https://github.com/tstapler/stelekit/commit/01e420ba74503a39948dee1fbc48e8e106cf0a46))
* **ci:** remove release workflow concurrency gate ([dfc0350](https://github.com/tstapler/stelekit/commit/dfc03504b0d87216452ccaf65f0872b94bd61082))
* **ci:** retry Homebrew formula push to handle concurrent release builds ([9cf9692](https://github.com/tstapler/stelekit/commit/9cf96928dba83539a20998d0d4b4b81e2d933809))
* **db:** replace pool.take() with polling loop to fix 58-min test hang ([fac90b2](https://github.com/tstapler/stelekit/commit/fac90b2636c9634ff8927ecf728437ed3f978c9a))
* **test:** resolve detekt violation and flaky GalleryViewModelTest ([78a6174](https://github.com/tstapler/stelekit/commit/78a61745e76f1153ba63c5411930b768743131f5))

## [0.38.1](https://github.com/tstapler/stelekit/compare/v0.38.0...v0.38.1) (2026-06-06)


### Bug Fixes

* **ci:** stop cancel-in-progress from tearing benchmark runs, fix 33-min test hang ([e12c9cb](https://github.com/tstapler/stelekit/commit/e12c9cb8e198596f519661c91a5d3f9ef12015c0))

## [0.38.0](https://github.com/tstapler/stelekit/compare/v0.37.0...v0.38.0) (2026-06-05)


### Features

* **google:** add isAuthenticated/getConnectedEmail/saveEmail/getEmail to auth interfaces ([9629373](https://github.com/tstapler/stelekit/commit/9629373002d90ff0a172ddba9e4486c2f6f2bb55))


### Bug Fixes

* **android:** catch Throwable in Application.onCreate to prevent Error crashes ([d943a77](https://github.com/tstapler/stelekit/commit/d943a7706a90811dca49b0a7b8ce96b19bd9fce2))
* **android:** fix startup crash — closed DB race in graph lifecycle ([1e8f66f](https://github.com/tstapler/stelekit/commit/1e8f66fba9b01a4d45a607e28c9a28393b7afad2))
* **ci:** remove pages concurrency group to prevent deploy cancellations ([ec25fd1](https://github.com/tstapler/stelekit/commit/ec25fd130505618a381e9e080b1ba95df7431e14))
* **fdroid:** always run update-fdroid job when workflow is triggered ([6dc1439](https://github.com/tstapler/stelekit/commit/6dc14392cac7e998fbf67472ffeca62bad5d26bd))

## [0.37.0](https://github.com/tstapler/stelekit/compare/v0.36.1...v0.37.0) (2026-06-04)


### Features

* **image-meter:** activate image meter — BLE panel, calibration UX, imperial units, full accessibility ([#126](https://github.com/tstapler/stelekit/issues/126)) ([8c9b314](https://github.com/tstapler/stelekit/commit/8c9b314fe7cadbfcfec3bdf310902e6edc6feb3a))


### Bug Fixes

* **android:** restore graph on process wake after hibernation ([#127](https://github.com/tstapler/stelekit/issues/127)) ([7d5cbdf](https://github.com/tstapler/stelekit/commit/7d5cbdfb583f3d0c5c906740b5cc10063be3ae1b))

## [0.36.1](https://github.com/tstapler/stelekit/compare/v0.36.0...v0.36.1) (2026-06-03)


### Bug Fixes

* **ci:** correct fdroid artifact path — upload-artifact strips common prefix ([2f46110](https://github.com/tstapler/stelekit/commit/2f461105079499018b0730e9b8496d0e479c44b4))
* **ci:** correct scan_apks.py path — uv --directory changes cwd ([3ffc909](https://github.com/tstapler/stelekit/commit/3ffc9099bed81d1695ea3debff2a62b9a18f945a))
* **ci:** ensure uv is available before syncing fdroid project ([50a84d5](https://github.com/tstapler/stelekit/commit/50a84d503b125dd801015fbbfc900ce434923755))
* **ci:** exclude v0.1.0 APK from fdroid repo — androguard can't parse it ([7df3c91](https://github.com/tstapler/stelekit/commit/7df3c9131ec6df90b96cee1f4c29d9babc8f6ea3))
* **ci:** make zizmor non-blocking until unpinned actions are SHA-pinned ([67fdaed](https://github.com/tstapler/stelekit/commit/67fdaed4bfa9f068ff9dad7504970ffbf8338c9e))
* **ci:** pin androguard==3.3.5 to fix APK v3 signature parsing crash ([a7a17b5](https://github.com/tstapler/stelekit/commit/a7a17b58f58acc869a3063d56a7e7f94e1370c88))
* **ci:** pre-scan APKs with androguard before fdroid update ([c5ccab1](https://github.com/tstapler/stelekit/commit/c5ccab112f2b661b6fb444c704935a95130b9121))
* **ci:** resolve shellcheck SC2015 in fdroid.yml and pages.yml ([094cf0b](https://github.com/tstapler/stelekit/commit/094cf0b7388d284e369c6da276e2de279b9f141b))
* **ci:** unblock jvmTestFast and Android benchmark compile errors ([cdc6165](https://github.com/tstapler/stelekit/commit/cdc616513c6c76366b893b58560a51a8a7137871))
* **ci:** uv project for fdroid — reproducible deps + own concurrency group ([c2d6c2b](https://github.com/tstapler/stelekit/commit/c2d6c2b4c4855f9974464ebbc0a4e69ca12412e1))
* **fdroid:** also test v3 signature parsing in pre-scan ([96690ee](https://github.com/tstapler/stelekit/commit/96690ee7e7d3b5c4ce05de6d8f0a6c114eb098bb))
* **fdroid:** patch NoOverwriteDict to support append+__iter__ for androguard 4.x ([913128f](https://github.com/tstapler/stelekit/commit/913128fed06f88d395567636347106015656cd77))
* **fdroid:** scan deeper — test get_android_resources not just APK init ([7bd47d2](https://github.com/tstapler/stelekit/commit/7bd47d2db7ad93009defcde649e5e718e9f046b4))
* **fdroid:** switch to androguard 4.x — 3.3.5 fails on all modern APK resource tables ([ed431ee](https://github.com/tstapler/stelekit/commit/ed431ee3ef5bec7ff48e41462f5ec7ae5f106e83))
* **model:** use expect/actual for value classes to fix JVM + Wasm compilation ([c61b64c](https://github.com/tstapler/stelekit/commit/c61b64cb8ebae499fd6a6dbd745a4040b4c20dd2))
* **wasm:** remove @JvmInline from commonMain value classes ([4bafe78](https://github.com/tstapler/stelekit/commit/4bafe781518f23cd3133993631b18cec366e0875))
* **web:** fix SQLite WASM bind bug + wire demo graph fallback ([#125](https://github.com/tstapler/stelekit/issues/125)) ([6cefca5](https://github.com/tstapler/stelekit/commit/6cefca52a54220e3cc92d4f08c2f6ee40c604a78))


### Performance Improvements

* **db:** eliminate FTS5 trigger storm on warm-path block saves ([3965f94](https://github.com/tstapler/stelekit/commit/3965f946f7c5c7b2d19e08166968c88bb7b11cba))

## [0.36.0](https://github.com/tstapler/stelekit/compare/v0.35.1...v0.36.0) (2026-06-02)


### Features

* **ui:** add reload-from-disk button + fix stale page reads + fix fdroid 404 ([c05854b](https://github.com/tstapler/stelekit/commit/c05854b23dd18483400c87dbabcf9ba9b5d63a5a))


### Bug Fixes

* **db:** replace mtime guard with watcher-driven dirty set for cache invalidation ([443c4e1](https://github.com/tstapler/stelekit/commit/443c4e1d4971b86939fec829f77f8ec0f799e5ec))

## [0.35.1](https://github.com/tstapler/stelekit/compare/v0.35.0...v0.35.1) (2026-06-01)


### Bug Fixes

* **ci:** call fdroid workflow directly from release.yml ([ed631b9](https://github.com/tstapler/stelekit/commit/ed631b9610310c550bb70be2fba808aa3c1ddaa2))


### Performance Improvements

* **db:** fix blocks full-table-scan, batch loadExistingBlocks, eager perf UI ([#121](https://github.com/tstapler/stelekit/issues/121)) ([c4f33a8](https://github.com/tstapler/stelekit/commit/c4f33a88b4caf5e84ab37e74295f07d8815c2c81))

## [0.35.0](https://github.com/tstapler/stelekit/compare/v0.34.0...v0.35.0) (2026-05-31)


### Features

* **export:** page export — 4 formats, clipboard, loading state, 108 tests ([#119](https://github.com/tstapler/stelekit/issues/119)) ([d840725](https://github.com/tstapler/stelekit/commit/d8407250d66ddf392e3c1d613a1e1aac73083628))

## [0.34.0](https://github.com/tstapler/stelekit/compare/v0.33.0...v0.34.0) (2026-05-31)


### Features

* **image-meter:** activate image meter — wiring, full UX spec, accessibility, imperial units ([#113](https://github.com/tstapler/stelekit/issues/113)) ([69f95b1](https://github.com/tstapler/stelekit/commit/69f95b1874042afd85ec47c919a128796753317c))


### Bug Fixes

* **android:** make shadow cache reliably pick up external file changes ([3982384](https://github.com/tstapler/stelekit/commit/3982384966c57f16944d583bec93a20ae9557c20))
* **ci:** trigger F-Droid update on release publish, not every push ([7c3e2dd](https://github.com/tstapler/stelekit/commit/7c3e2ddf031c38e01fa579ca81c93d33ec3f1093))

## [0.33.0](https://github.com/tstapler/stelekit/compare/v0.32.0...v0.33.0) (2026-05-31)


### Features

* **git:** vault-integrated credentials, conflict resolution, git sync UX ([#116](https://github.com/tstapler/stelekit/issues/116)) ([5b4fc9d](https://github.com/tstapler/stelekit/commit/5b4fc9d4e432a81191ba651bd7a41e9832ca637a))

## [0.32.0](https://github.com/tstapler/stelekit/compare/v0.31.1...v0.32.0) (2026-05-31)


### Features

* **android:** add voice-capture home screen widget ([8953afc](https://github.com/tstapler/stelekit/commit/8953afcc44751003dfba9d7b19599fb6b971ae2f))


### Bug Fixes

* **android:** fix share target not capturing URL or page title ([a630631](https://github.com/tstapler/stelekit/commit/a630631f23550ea0f6d5fab237cec70ef6373107))
* **search:** treat hyphens as token boundaries in FtsQueryBuilder ([#114](https://github.com/tstapler/stelekit/issues/114)) ([1b4f0ae](https://github.com/tstapler/stelekit/commit/1b4f0ae9ce784b1cbd5e1eb167d4ca200c198928))
* **voice:** address all code review findings in voice pipeline ([cb32495](https://github.com/tstapler/stelekit/commit/cb324953df290f0109200d6ceb24ca0ce0e0250d))
* **voice:** address UX review findings and code quality issues ([a25cb9e](https://github.com/tstapler/stelekit/commit/a25cb9e1b9e1ba60aef0345aacf40ecf351cc660))


### Performance Improvements

* **rename:** 97% latency reduction + pagination fixes + editor improvements ([#115](https://github.com/tstapler/stelekit/issues/115)) ([82186f9](https://github.com/tstapler/stelekit/commit/82186f9ffc9b9b58a7a0b2d873f5501f43856103))

## [0.31.1](https://github.com/tstapler/stelekit/compare/v0.31.0...v0.31.1) (2026-05-30)


### Bug Fixes

* **db:** add migration 5 to create query_stats for existing databases ([f35bf24](https://github.com/tstapler/stelekit/commit/f35bf2457940beb3179e4b9d31c81c3dd75f76b9))
* **db:** register query_stats migration in MigrationRunner for JVM ([dda495c](https://github.com/tstapler/stelekit/commit/dda495c5f3fadaf6a1a4f434a725a1805972eab8))
* **desktop:** pass app.version to packaged distribution via jvmArgs ([a9d462e](https://github.com/tstapler/stelekit/commit/a9d462e0a42c5d7cb1e8bfb273a0c331091ce8c8))

## [0.31.0](https://github.com/tstapler/stelekit/compare/v0.30.2...v0.31.0) (2026-05-30)


### Features

* **ui:** render markdown bold/italic in page title, search results, and snippets ([#108](https://github.com/tstapler/stelekit/issues/108)) ([aa7e5a5](https://github.com/tstapler/stelekit/commit/aa7e5a525cffaf6f73e86a99a78c838baeb76a5d))


### Bug Fixes

* **watch:** shadow stale-read drops external sync changes; no new-day journal ([#107](https://github.com/tstapler/stelekit/issues/107)) ([4fade3d](https://github.com/tstapler/stelekit/commit/4fade3d620390961402af6772931ca7287b3d3eb))

## [0.30.2](https://github.com/tstapler/stelekit/compare/v0.30.1...v0.30.2) (2026-05-29)


### Bug Fixes

* **android:** sync shadow cache before file reads to fix stale-content bug ([#105](https://github.com/tstapler/stelekit/issues/105)) ([b479bca](https://github.com/tstapler/stelekit/commit/b479bca311748df3290737d35a7b9bb5e2b89112))


### Performance Improvements

* **db:** mmap_size PRAGMA, JVM PRAGMA parity, covering indexes for hot block queries ([#104](https://github.com/tstapler/stelekit/issues/104)) ([5cfb694](https://github.com/tstapler/stelekit/commit/5cfb69499c9007069f16aa15813cbb06b6b82f0f))

## [0.30.1](https://github.com/tstapler/stelekit/compare/v0.30.0...v0.30.1) (2026-05-28)


### Bug Fixes

* **homebrew:** remove invalid quarantine stanza from cask definition ([b6a13b6](https://github.com/tstapler/stelekit/commit/b6a13b646d81f79bb7b9362f9173a5c742d33e02))

## [0.30.0](https://github.com/tstapler/stelekit/compare/v0.29.0...v0.30.0) (2026-05-27)


### Features

* **perf:** wire editor_input histogram and add SQL Stats dashboard tab ([e59697e](https://github.com/tstapler/stelekit/commit/e59697e34b05bd4ea51e20b0d7f7211d0bda1828))


### Bug Fixes

* **lint:** add explicit color to Text inside dynamic-background Surface ([6309871](https://github.com/tstapler/stelekit/commit/6309871b825be9199f35f028cf591bcb8396a2a7))

## [0.29.0](https://github.com/tstapler/stelekit/compare/v0.28.1...v0.29.0) (2026-05-27)


### Features

* **perf:** dynamic histogram dashboard — shows all recorded operations ([4e1d292](https://github.com/tstapler/stelekit/commit/4e1d2928ec28571f38943c8605c6fda0f44ea12c))
* **perf:** EXPLAIN QUERY PLAN dashboard tab ([03bd38e](https://github.com/tstapler/stelekit/commit/03bd38e83c6c55ad18d7a9373685871768e7abe5))


### Bug Fixes

* **android:** disable LogDetector in androidApp and raise Metaspace limit ([f78312c](https://github.com/tstapler/stelekit/commit/f78312c61c3ab1b36ffba7bf19defe7e7f6551c4))
* **android:** disable LogDetector lint rules to prevent Metaspace OOM ([4b4f7a0](https://github.com/tstapler/stelekit/commit/4b4f7a05bbe3b5e90d7985e915178d2a8ee65a3b))
* **perf:** wire histogramWriter into GraphLoader so graph_load is recorded ([71e4600](https://github.com/tstapler/stelekit/commit/71e460058367aaafee50f778450ceabc150f9e2d))
* **search:** prevent Space key from dismissing the search dialog ([#99](https://github.com/tstapler/stelekit/issues/99)) ([d83d343](https://github.com/tstapler/stelekit/commit/d83d34336a2d5304df0c69659ede2f38049ed3f3))

## [0.28.1](https://github.com/tstapler/stelekit/compare/v0.28.0...v0.28.1) (2026-05-27)


### Bug Fixes

* **editor:** serialize structural block ops through DatabaseWriteActor ([#97](https://github.com/tstapler/stelekit/issues/97)) ([ab4e924](https://github.com/tstapler/stelekit/commit/ab4e924a923f9af92ceed793f2aa2b48e496bbed))
* **images:** restore correct aspect ratio and add fullscreen lightbox ([#100](https://github.com/tstapler/stelekit/issues/100)) ([4ac845d](https://github.com/tstapler/stelekit/commit/4ac845d7c4120980e0395b4b580e05581e07a9be))
* **perf:** resolve empty saved spans and OTel desync on reinitialisation ([#95](https://github.com/tstapler/stelekit/issues/95)) ([c61502a](https://github.com/tstapler/stelekit/commit/c61502a3265cc17c8090eb181a48a5fb9bb2cfaf))
* **ui:** fix table dark-mode theming; add UnthemedTextInBackgroundContainer lint rule ([#98](https://github.com/tstapler/stelekit/issues/98)) ([be1652e](https://github.com/tstapler/stelekit/commit/be1652e64249d510b20a63c90cd1b4614de6ec85))

## [0.28.0](https://github.com/tstapler/stelekit/compare/v0.27.1...v0.28.0) (2026-05-24)


### Features

* **git:** wire git UI — sync badge, setup wizard, conflict dialog ([5cdb732](https://github.com/tstapler/stelekit/commit/5cdb732de6fa975e9143d540e74388f581fa7f49))


### Bug Fixes

* **ci:** resolve Benchmark config-cache crash and F-Droid repo-icon path ([ddc1356](https://github.com/tstapler/stelekit/commit/ddc13569b59eef67afa7574b3db26674fd5ef346))
* **ci:** resolve detekt ComplexCondition and config-cache failures ([640de97](https://github.com/tstapler/stelekit/commit/640de97916e7b49c1a95ab6a8b8a96605b5d27e9))
* **fdroid:** add app icon and fastlane metadata to fix F-Droid listing ([85b1684](https://github.com/tstapler/stelekit/commit/85b1684e1d0e15b25129f5e776f4db3ae601735e))
* **fdroid:** set DEMO_AVAILABLE when building site, bump archive_older to 5 ([b4d13dd](https://github.com/tstapler/stelekit/commit/b4d13ddc0c10dfc9ff8928dec57fb4ae0d66853b))

## [0.27.1](https://github.com/tstapler/stelekit/compare/v0.27.0...v0.27.1) (2026-05-24)


### Bug Fixes

* **ci:** increase Metaspace to 1g for Android release build ([2b99076](https://github.com/tstapler/stelekit/commit/2b99076435f1bdebc581a571b50cfa1fbe417b3f))

## [0.27.0](https://github.com/tstapler/stelekit/compare/v0.26.4...v0.27.0) (2026-05-23)


### Features

* **android,vault:** wire Android OTel/Git/WorkManager, add vault settings UI ([b2bcb9f](https://github.com/tstapler/stelekit/commit/b2bcb9fb7792f7f19a95c31452c0ff2feed57466))
* **perf:** on-demand span capture, GZIP archive, and live performance dashboard ([#92](https://github.com/tstapler/stelekit/issues/92)) ([afb7b4d](https://github.com/tstapler/stelekit/commit/afb7b4dd6450260ce9345625569cacb7d3b95117))
* **search:** progressive page→block results with loading indicator ([93aa386](https://github.com/tstapler/stelekit/commit/93aa3868a72299f0fd4ef2400489fcf1d00c0bb2))


### Bug Fixes

* **images:** render Logseq size-hint images as actual images ([130fdb5](https://github.com/tstapler/stelekit/commit/130fdb5fdd8d9394f528c36487a1262e89eff596))
* **search:** update tests to use .last() for progressive search flow ([a3995a8](https://github.com/tstapler/stelekit/commit/a3995a8fd368f22887f70ed111bfce32f4ee5afb))

## [0.26.4](https://github.com/tstapler/stelekit/compare/v0.26.3...v0.26.4) (2026-05-21)


### Bug Fixes

* **android:** eliminate Initializing… permanent hang on large libraries ([9437abf](https://github.com/tstapler/stelekit/commit/9437abf45e73163a7633af6c7fbfc5ae42d136dc))
* **wasm:** remove @Volatile from commonMain — not available on Wasm/JS ([908822a](https://github.com/tstapler/stelekit/commit/908822a20061704f554365c47ed0f0693a25ed0f))

## [0.26.3](https://github.com/tstapler/stelekit/compare/v0.26.2...v0.26.3) (2026-05-21)


### Bug Fixes

* **android:** remove runBlocking from Application.onCreate and switchGraph ([46c9db1](https://github.com/tstapler/stelekit/commit/46c9db1564699e0902426882f993310e01fcb5bc))
* **detekt:** rethrow CancellationException in switchGraph deviceInfo catch ([57d3ff2](https://github.com/tstapler/stelekit/commit/57d3ff222c0f75870941668f5826a68381e36943))

## [0.26.2](https://github.com/tstapler/stelekit/compare/v0.26.1...v0.26.2) (2026-05-21)


### Bug Fixes

* **android:** remove runBlocking from Application.onCreate and switchGraph ([46c9db1](https://github.com/tstapler/stelekit/commit/46c9db1564699e0902426882f993310e01fcb5bc))
* **release:** generate icon.icns via Bazel genrule on macOS CI ([8c4dd7b](https://github.com/tstapler/stelekit/commit/8c4dd7b1bfdb47f83bafb2991c328518c686faf3))

## [0.26.1](https://github.com/tstapler/stelekit/compare/v0.26.0...v0.26.1) (2026-05-20)


### Bug Fixes

* **ui:** add border, column dividers, and header background to TableBlock ([c3f55ac](https://github.com/tstapler/stelekit/commit/c3f55ac7dd2572f7947914b50efe73a700120860))
* **ui:** move clickable after clip in TableBlock modifier chain ([a9e0aea](https://github.com/tstapler/stelekit/commit/a9e0aeafcd14376d214ebeff36ffb75afff53068))

## [0.26.0](https://github.com/tstapler/stelekit/compare/v0.25.0...v0.26.0) (2026-05-19)


### Features

* **android/test:** add StrictModeRule to catch main-thread disk I/O in instrumented tests ([0aebddc](https://github.com/tstapler/stelekit/commit/0aebddccdb46b719d448e2303b2177c195bb7bda))
* **image-meter:** native photo annotation with scaled measurements ([#85](https://github.com/tstapler/stelekit/issues/85)) ([64dca6d](https://github.com/tstapler/stelekit/commit/64dca6da44ccf660415fbe403f60ef17427c353f))
* **images:** image attachment, inline rendering, and Wayback Machine archiving ([#83](https://github.com/tstapler/stelekit/issues/83)) ([20adc0f](https://github.com/tstapler/stelekit/commit/20adc0f9037db2fe23e592e1a4b45877b5fcb0a7))


### Bug Fixes

* **android:** prevent ANR by moving addGraph SAF I/O off the main thread ([9453802](https://github.com/tstapler/stelekit/commit/9453802a07560af4590bdfdd7ea55e1284429923))


### Performance Improvements

* **android:** fix block insert latency — IO dispatcher + optimistic focus ([#84](https://github.com/tstapler/stelekit/issues/84)) ([3af9e5b](https://github.com/tstapler/stelekit/commit/3af9e5be83bffd65320a80673e1198eda2464466))

## [0.25.0](https://github.com/tstapler/stelekit/compare/v0.24.1...v0.25.0) (2026-05-15)


### Features

* **build:** add buildVariant property and macOS bundle config ([117dd8b](https://github.com/tstapler/stelekit/commit/117dd8b58adc1e3df774692742c1a4b7f6d1ba66))
* **clipboard:** add ClipboardBlock and BlockClipboard value objects ([599e8a9](https://github.com/tstapler/stelekit/commit/599e8a901b7d5a8578826579f581677717316238))
* **scripts:** add install-dev.sh for local dev build install ([e203f8d](https://github.com/tstapler/stelekit/commit/e203f8d6bd5f7490896b56ff5a53c35d89d6a04d))


### Bug Fixes

* **ci:** run benchmark jobs on push to main, not just non-draft PRs ([a197da8](https://github.com/tstapler/stelekit/commit/a197da8d13e4fa6803c36a32c4321ccef53075b3))
* **homebrew:** use quarantine false to bypass Gatekeeper on unsigned app ([be54852](https://github.com/tstapler/stelekit/commit/be54852944c518bab7c7a2634ee154169fc7ec78))
* **search:** resolve all 8 detekt linting failures from CI ([5e1d186](https://github.com/tstapler/stelekit/commit/5e1d186f47720b6958d55b5e47a3a36104f26d7f))
* **ui:** resolve unresolved reference 'modifier' in SearchResultRow ([1a6aa05](https://github.com/tstapler/stelekit/commit/1a6aa05a7d08883a6cad7578e4704460ca0c8c05))

## [0.24.1](https://github.com/tstapler/stelekit/compare/v0.24.0...v0.24.1) (2026-05-14)


### Bug Fixes

* **wasm/db/bench:** sqlite-wasm npm version, backlink_count migration repair, benchmark infra ([#79](https://github.com/tstapler/stelekit/issues/79)) ([9b5b383](https://github.com/tstapler/stelekit/commit/9b5b38368696ecf3cca4728c270695d5a1d2a866))

## [0.24.0](https://github.com/tstapler/stelekit/compare/v0.23.0...v0.24.0) (2026-05-12)


### Features

* **security:** paranoid mode with LUKS2-style keyslots and hidden volumes ([#73](https://github.com/tstapler/stelekit/issues/73)) ([574da4e](https://github.com/tstapler/stelekit/commit/574da4e4c8919e6a6292287472b8b022758c55a9))


### Bug Fixes

* **perf:** fix slow block creation at bottom of journal on Android (BUG-008) ([#78](https://github.com/tstapler/stelekit/issues/78)) ([639c3c3](https://github.com/tstapler/stelekit/commit/639c3c3b99d9199bd751853d2fe4d192c649446c))
* **wasm:** use [@sqlite](https://github.com/sqlite).org/sqlite-wasm 3.46.1-build1 (bare 3.46.1 not on npm) ([7a00909](https://github.com/tstapler/stelekit/commit/7a00909bdef52a920efd65d038de98bbadad70f9))

## [0.23.0](https://github.com/tstapler/stelekit/compare/v0.22.1...v0.23.0) (2026-05-08)


### Features

* **web:** OPFS-backed SQLite driver + local dev script ([#74](https://github.com/tstapler/stelekit/issues/74)) ([22516f5](https://github.com/tstapler/stelekit/commit/22516f54b3d2ce061c017cda2d97c65edf2d32bc))

## [0.22.1](https://github.com/tstapler/stelekit/compare/v0.22.0...v0.22.1) (2026-05-04)


### Bug Fixes

* **build:** lower jvmToolchain from 25 to 21 to match CI runtime ([aac9538](https://github.com/tstapler/stelekit/commit/aac953819efd913f2aa9b8b7a29ee2610022aee6))
* **ci:** add always() guards to release jobs for workflow_dispatch ([63fcb45](https://github.com/tstapler/stelekit/commit/63fcb45377d44e566fe4d72e52e5a20153f3113a))

## [0.22.0](https://github.com/tstapler/stelekit/compare/v0.21.0...v0.22.0) (2026-05-04)


### Features

* **search:** two-panel layout, FTS performance, precomputed backlink counts ([#70](https://github.com/tstapler/stelekit/issues/70)) ([651d006](https://github.com/tstapler/stelekit/commit/651d0061857556e52bd825a28b2377df2820ce4d))


### Bug Fixes

* **lint:** use mutableIntStateOf for selectedTab in ReferencesPanel ([dec72d3](https://github.com/tstapler/stelekit/commit/dec72d302ae72c5ecbc4e6871df8e1772c28a877))

## [0.21.0](https://github.com/tstapler/stelekit/compare/v0.20.0...v0.21.0) (2026-05-03)


### Features

* **git:** two-way git sync with in-app conflict resolution ([#63](https://github.com/tstapler/stelekit/issues/63)) ([7269c4e](https://github.com/tstapler/stelekit/commit/7269c4eea6abf6ca41ef1d6120c2058405cde33a))


### Bug Fixes

* remove hardcoded local JDK path from gradle.properties ([#69](https://github.com/tstapler/stelekit/issues/69)) ([ca27eda](https://github.com/tstapler/stelekit/commit/ca27eda1b6615ae7cb5913dc64ac9643ab047335))

## [0.20.0](https://github.com/tstapler/stelekit/compare/v0.19.4...v0.20.0) (2026-05-03)


### Features

* **ui:** add Unlinked Mentions tab to ReferencesPanel ([53ac786](https://github.com/tstapler/stelekit/commit/53ac78682e7e1ad560f432cb8ff2f2f03a9af2cb))
* **voice:** rich markdown formatting, transcript pages, current-page insertion, UX improvements ([#66](https://github.com/tstapler/stelekit/issues/66)) ([8155563](https://github.com/tstapler/stelekit/commit/8155563fbe10279d30554b7433a6f744978dbeab))

## [0.19.4](https://github.com/tstapler/stelekit/compare/v0.19.3...v0.19.4) (2026-05-03)


### Bug Fixes

* rethrow CancellationException in loadJournalsImmediate ([#62](https://github.com/tstapler/stelekit/issues/62)) ([222a638](https://github.com/tstapler/stelekit/commit/222a63831095cb1ecde6fa4301f1c585701eb8a6))
* **voice:** fix on-device STT always falling back to Whisper on Android ([#64](https://github.com/tstapler/stelekit/issues/64)) ([2f778ef](https://github.com/tstapler/stelekit/commit/2f778ef866350fb8a29e695a884f7e8644ca20af))

## [0.19.3](https://github.com/tstapler/stelekit/compare/v0.19.2...v0.19.3) (2026-05-01)


### Bug Fixes

* address Copilot review comments ([d297890](https://github.com/tstapler/stelekit/commit/d297890ba79162a46351d283c07a6594b13fd029))
* **bench:** move FileProvider to kmp androidMain so test APK gets correct authority ([3704288](https://github.com/tstapler/stelekit/commit/37042881b6d9ac494a99874aaa148b0d35c4bb73))


### Performance Improvements

* **android:** PRAGMA tuning, MEDIUM benchmark, SAF I/O overhead test ([52ee431](https://github.com/tstapler/stelekit/commit/52ee43118e260987a9eb9930f3179a4027c4e00f))
* **android:** SAF performance — race condition fix, DocumentFile elimination, lazy Phase 3 ([574c14a](https://github.com/tstapler/stelekit/commit/574c14a64f288e056882c19e9bfd8aaf6db5b5de))
* **android:** SAF performance stories 1-3 ([d2ab252](https://github.com/tstapler/stelekit/commit/d2ab2521814112d3f3aa784c4222978bf3970edd))
* **android:** SAF shadow copy eliminates Binder IPC for Phase 3 reads ([e26fa89](https://github.com/tstapler/stelekit/commit/e26fa89103b69ce496c7843edbbd98622d8aae8b))

## [0.19.2](https://github.com/tstapler/stelekit/compare/v0.19.1...v0.19.2) (2026-04-29)


### Bug Fixes

* **wasm:** fix wasmJs compilation errors breaking GitHub Pages deploy ([bde6814](https://github.com/tstapler/stelekit/commit/bde681481adf15a9c429b6a9b29c7eeb4cd3619c))

## [0.19.1](https://github.com/tstapler/stelekit/compare/v0.19.0...v0.19.1) (2026-04-28)


### Bug Fixes

* **android:** defer sanitizeDirectory off startup critical path; add native file pickers for export ([453f75c](https://github.com/tstapler/stelekit/commit/453f75c7563374ea1305b3f577ed132213b2b833))

## [0.19.0](https://github.com/tstapler/stelekit/compare/v0.18.1...v0.19.0) (2026-04-28)


### Features

* **homebrew:** add Casks/stelekit.rb for proper macOS GUI install ([afd2613](https://github.com/tstapler/stelekit/commit/afd26136a4b1f04b38b69a5e8eee2bbc9afe5600))


### Bug Fixes

* **homebrew:** symlink SteleKit.app to ~/Applications so it appears in Finder/Launchpad ([b006a51](https://github.com/tstapler/stelekit/commit/b006a51d508e17f79661713659b38f3014d48f4c))

## [0.18.1](https://github.com/tstapler/stelekit/compare/v0.18.0...v0.18.1) (2026-04-28)


### Bug Fixes

* **journals:** fix journals showing Untitled, missing today's journal, and Claude API response deserialization ([16d2fc7](https://github.com/tstapler/stelekit/commit/16d2fc7cb7ff16cb65fb8f1e877ffefa6ee776a3))

## [0.18.0](https://github.com/tstapler/stelekit/compare/v0.17.0...v0.18.0) (2026-04-28)


### Features

* **search:** visit-recency ranking, exact-title guarantee, FTS repair, and latency benchmarks ([#52](https://github.com/tstapler/stelekit/issues/52)) ([86da88e](https://github.com/tstapler/stelekit/commit/86da88e58416880fa58c7fdeccf5890937a208e0))


### Bug Fixes

* **ci:** repair pages workflow so the site actually deploys ([#53](https://github.com/tstapler/stelekit/issues/53)) ([782448b](https://github.com/tstapler/stelekit/commit/782448bcc0fb0054721f0b6a54c24110336aac5a))

## [0.17.0](https://github.com/tstapler/stelekit/compare/v0.16.3...v0.17.0) (2026-04-27)


### Features

* **editor:** Cmd+K with selected text opens search pre-filled ([412e055](https://github.com/tstapler/stelekit/commit/412e0559f0dd405e52d33d84aeedf51026812334))


### Bug Fixes

* **detekt:** add LocalOpenSearchWithText and GraphLoader pageUuid to baseline ([0c888fa](https://github.com/tstapler/stelekit/commit/0c888fabbeff051c0428baf27a3bb6f80cbfad2d))
* **editor:** Cmd+K only consumes for link wrap when text is selected ([602c783](https://github.com/tstapler/stelekit/commit/602c7835bcda7f498dd0d70e8bffecdba56adefa))

## [0.16.3](https://github.com/tstapler/stelekit/compare/v0.16.2...v0.16.3) (2026-04-27)


### Bug Fixes

* **startup:** track warm reconcile job in backgroundIndexJob ([833296a](https://github.com/tstapler/stelekit/commit/833296a6620b0cf6322dd16ae007486952442d8a))
* **writer:** surface writeFile exceptions and fix misleading success log ([ca898c7](https://github.com/tstapler/stelekit/commit/ca898c77098c992f877fcaa75f3cd7ea26008ab1))

## [0.16.2](https://github.com/tstapler/stelekit/compare/v0.16.1...v0.16.2) (2026-04-27)


### Performance Improvements

* **android:** fix 27s startup, wrong SAF directory creation, and memory pressure ([fe94e7d](https://github.com/tstapler/stelekit/commit/fe94e7d0d48ebcc7e4b84c3ecedf0bc0490ccda8))
* **cache:** don't populate page caches during bulk background indexing ([f8a9c10](https://github.com/tstapler/stelekit/commit/f8a9c106bfa31b473afd279ae6d8799c5b177404))
* **startup:** warm-start fast path in loadGraphProgressive + Arrow docs ([e251ada](https://github.com/tstapler/stelekit/commit/e251adade93eee5b58b0eac73eb521cad6f7425c))

## [0.16.1](https://github.com/tstapler/stelekit/compare/v0.16.0...v0.16.1) (2026-04-27)


### Bug Fixes

* **ux:** surface live logs on all loading screens ([f1d7de5](https://github.com/tstapler/stelekit/commit/f1d7de5940bf68e3153abc30fc92cc960f3221e8))

## [0.16.0](https://github.com/tstapler/stelekit/compare/v0.15.0...v0.16.0) (2026-04-27)


### Features

* **arrow:** full Arrow 2.x integration — typed errors, optics, saga, and resilience ([016bdf5](https://github.com/tstapler/stelekit/commit/016bdf532dc93e322e2e91e3585f49d3549ff730))


### Bug Fixes

* **build:** resolve Either/Result type mismatches after merging main ([1103a14](https://github.com/tstapler/stelekit/commit/1103a14fdeb5a301397f58bcb34402addacc6841))

## [0.15.0](https://github.com/tstapler/stelekit/compare/v0.14.0...v0.15.0) (2026-04-27)


### Features

* **android:** add emulator smoke tests and Roborazzi screenshot tests ([9eaa880](https://github.com/tstapler/stelekit/commit/9eaa88030d40f40fea6e651882898f91a3ad2ccc))
* **android:** defense-in-depth quality layer for SAF permissions and loading state ([8ce5db1](https://github.com/tstapler/stelekit/commit/8ce5db122de758df0ab41c6e29e143a3cc29647b))
* **perf:** BlockHound, LRU cache, query-plan CI gate, pg_stat_statements analogue, CPU flamegraphs ([d782dfb](https://github.com/tstapler/stelekit/commit/d782dfbecc5c92a50a4cc6f0dcdbfe0d5b6ed47c))
* **tools:** replace flamegraph.pl with committed Kotlin implementation ([30828de](https://github.com/tstapler/stelekit/commit/30828de8ad857003cdf325ec92f5ceb406f6e16e))


### Bug Fixes

* **android:** use fully-qualified SteleKitApplication name in manifest ([1317782](https://github.com/tstapler/stelekit/commit/1317782492c4e982309d303f6a9a5b7591240b42))
* **ci:** restore upload-artifact@v7, remove perl guard from local script ([1d67b49](https://github.com/tstapler/stelekit/commit/1d67b491ad4f1c8e18f700a50df66b2bc7e008f6))
* **ci:** switch Roborazzi to record mode — goldens differ across machines ([5b88085](https://github.com/tstapler/stelekit/commit/5b880851e7d69529d6766bb7a44e70b43327d389))
* **perf+ci:** eliminate GC pressure from QueryStatsCollector, fix benchmark CI ([6ea1b54](https://github.com/tstapler/stelekit/commit/6ea1b54a5be7b1c437e3cdfb6da0600787b5aa35))
* **test:** broaden TC-E2E-002 to cover onboarding and recovery screens ([07351bc](https://github.com/tstapler/stelekit/commit/07351bc48cd737833c4a242e5f1cf5a558f1ec3d))
* **test:** change isFullyLoaded default to false to fix race in loading test ([7c467eb](https://github.com/tstapler/stelekit/commit/7c467ebf2fe6811a4b4bfe89b52b3535b77243b8))
* **tools/flamegraph:** set workingDir to repo root so relative paths resolve ([714a4a0](https://github.com/tstapler/stelekit/commit/714a4a0ccf06c94428b84efc9e49df05aadfb16d))


### Performance Improvements

* **instrumentation:** cache parseSql by identifier; benchmark % change + direction ([ea4236e](https://github.com/tstapler/stelekit/commit/ea4236ec9355d218c3c4bda70da66c5075314429))

## [0.14.0](https://github.com/tstapler/stelekit/compare/v0.13.0...v0.14.0) (2026-04-26)


### Features

* **android:** widget, tile, and share-sheet capture entry points ([e8394ae](https://github.com/tstapler/stelekit/commit/e8394ae18b4d2e89fa87b948df9f195b03903f1e))


### Bug Fixes

* **bench:** fix pageCount=0 in PR comments and clarify benchmark titles ([c66d0b7](https://github.com/tstapler/stelekit/commit/c66d0b79c84f9447337a34ee98e37226d96feacf))
* **ci:** pull --rebase before push in benchmark workflows ([1979f4f](https://github.com/tstapler/stelekit/commit/1979f4f2497fea7719d564c21a39c4b090c66204))
* **ci:** remove stray conflict marker from benchmark.yml ([a61a6d7](https://github.com/tstapler/stelekit/commit/a61a6d761ab280b9b21d57f1939176f369b29da3))
* **ios:** replace String.format() with KMP-safe formatting helpers ([d4853d0](https://github.com/tstapler/stelekit/commit/d4853d0fe6dc51f6ab8576527b00827d6dcfe891))
* **test:** make loadGraph set isFullyLoaded=false synchronously ([038637a](https://github.com/tstapler/stelekit/commit/038637a5d0c0006c26164d82196def75a9425394))

## [0.13.0](https://github.com/tstapler/stelekit/compare/v0.12.0...v0.13.0) (2026-04-26)


### Features

* **db:** enforce @DirectRepositoryWrite on SpanRepository write methods ([768d616](https://github.com/tstapler/stelekit/commit/768d61690d3ae2fb0fe9bc0c7213aa4314a681a7))
* **detekt:** rule enforcing @DirectRepositoryWrite on all *Repository write methods ([f645a9a](https://github.com/tstapler/stelekit/commit/f645a9ad39a4dd4230ff2f27f446037593ab0d94))
* **detekt:** rule enforcing @DirectRepositoryWrite on all *Repository write methods ([c3a20c9](https://github.com/tstapler/stelekit/commit/c3a20c9f81e9bee2d2a941a6c9e27e6376c88037))


### Bug Fixes

* **desktop:** resolve SQLITE_BUSY and WindowInsets crash on startup ([f081157](https://github.com/tstapler/stelekit/commit/f0811574ea8e1028583655785e6bb6d7549b1756))
* **desktop:** resolve SQLITE_BUSY and WindowInsets crash on startup ([7b93052](https://github.com/tstapler/stelekit/commit/7b93052f8aaab5f3655f14b552834f7f6bb1c72b))
* **fdroid:** enforce latest version, add metadata, replace placeholder icons ([#42](https://github.com/tstapler/stelekit/issues/42)) ([cbc41ff](https://github.com/tstapler/stelekit/commit/cbc41ff9f39bb27f082c39bc795f8e8e1b3d23cb))

## [0.12.0](https://github.com/tstapler/stelekit/compare/v0.11.0...v0.12.0) (2026-04-26)


### Features

* **android/voice:** on-device STT and LLM via SpeechRecognizer + Gemini Nano ([#27](https://github.com/tstapler/stelekit/issues/27)) ([a275dbe](https://github.com/tstapler/stelekit/commit/a275dbe912fa5838f3715f44d9ed5115a27eb931))

## [0.11.0](https://github.com/tstapler/stelekit/compare/v0.10.0...v0.11.0) (2026-04-26)


### Features

* **android:** extract Application class and share GraphManager across process lifecycle ([0e7915e](https://github.com/tstapler/stelekit/commit/0e7915e95a34f214ba9e3a19906e8a051b19fa24))


### Bug Fixes

* **search:** create-page in link picker now inserts link and appears first ([2a85c6d](https://github.com/tstapler/stelekit/commit/2a85c6dc176324feaf50df0179ca646ac15f2000))


## [0.10.0](https://github.com/tstapler/stelekit/compare/v0.9.5...v0.10.0) (2026-04-25)


### Features

* **fdroid:** fix repo version history and show version in Settings ([#33](https://github.com/tstapler/stelekit/issues/33)) ([9bb8a77](https://github.com/tstapler/stelekit/commit/9bb8a773b966cd7f79cf4c0a764a73cfb0b918b9))

## [0.9.5](https://github.com/tstapler/stelekit/compare/v0.9.4...v0.9.5) (2026-04-25)


### Bug Fixes

* **resilience:** fix loading screen hang + isolate test settings ([dc1b51b](https://github.com/tstapler/stelekit/commit/dc1b51be926992ca348b9958d15f4721acb98bd9))


### Performance Improvements

* **android:** decompose Phase 3 chunk writes to allow HIGH-priority preemption ([#26](https://github.com/tstapler/stelekit/issues/26)) ([b5a51ab](https://github.com/tstapler/stelekit/commit/b5a51ab82e52b38ceb98662412956be692114803))

## [0.9.4](https://github.com/tstapler/stelekit/compare/v0.9.3...v0.9.4) (2026-04-24)


### Bug Fixes

* **cache:** rename LruCache → SteleLruCache to bypass K2 compiler bug ([ea70c86](https://github.com/tstapler/stelekit/commit/ea70c865aa8765d4c394304101e94b563dca7b2f))

## [0.9.3](https://github.com/tstapler/stelekit/compare/v0.9.2...v0.9.3) (2026-04-24)


### Bug Fixes

* **cache:** replace coroutine Mutex with synchronized in LruCache ([17345e4](https://github.com/tstapler/stelekit/commit/17345e46528fb5e89243f5bee7c6b57e4655b9dc))

## [0.9.2](https://github.com/tstapler/stelekit/compare/v0.9.1...v0.9.2) (2026-04-23)


### Bug Fixes

* **cache:** strengthen LruCache.class workaround for Linux CI ([59ae017](https://github.com/tstapler/stelekit/commit/59ae01737e0761cbc5f88dc913b2e798d771276b))

## [0.9.1](https://github.com/tstapler/stelekit/compare/v0.9.0...v0.9.1) (2026-04-23)


### Bug Fixes

* **cache:** work around Kotlin 2.3.10 K2 compiler bug in LruCache ([ba30932](https://github.com/tstapler/stelekit/commit/ba309320c4110970a5795b3325f0763324ed408d))

## [0.9.0](https://github.com/tstapler/stelekit/compare/v0.8.1...v0.9.0) (2026-04-23)


### Features

* **observability:** comprehensive instrumentation, SLO monitoring, and perf export ([a9770de](https://github.com/tstapler/stelekit/commit/a9770de16026a95bc7d7ef28b7dc839df7f8070a))
* **search:** AND semantics, field boosting, recency + graph distance ranking ([#22](https://github.com/tstapler/stelekit/issues/22)) ([8d23437](https://github.com/tstapler/stelekit/commit/8d2343751450d7c37098c8375a628a5564aa56c0))


### Bug Fixes

* **sync:** prevent data-loss races on mobile reload and external conflict handling ([#21](https://github.com/tstapler/stelekit/issues/21)) ([105928d](https://github.com/tstapler/stelekit/commit/105928d782b9d775ddb4a142206b41474f92c5bb))

## [0.8.1](https://github.com/tstapler/stelekit/compare/v0.8.0...v0.8.1) (2026-04-22)


### Bug Fixes

* **macos:** use Homebrew openjdk and clean build for macOS 26 compat ([#18](https://github.com/tstapler/stelekit/issues/18)) ([d90968d](https://github.com/tstapler/stelekit/commit/d90968d94e202f5cdf705a36ef8eafec618239c8))

## [0.8.0](https://github.com/tstapler/stelekit/compare/v0.7.1...v0.8.0) (2026-04-22)


### Features

* **cache:** add LruCache, RequestCoalescer, and platform-aware cache sizing ([05e982a](https://github.com/tstapler/stelekit/commit/05e982ac56ef5683bf1ee3c96b275ea428284537))
* **ci:** generate flamegraph PNG and embed it inline in benchmark PR comment ([7425ee8](https://github.com/tstapler/stelekit/commit/7425ee8ce151e89081aef3af0e50f89dcc8ee7ce))
* **ci:** upload flamegraph PNG via GitHub uploads API instead of repo commit ([d4ead21](https://github.com/tstapler/stelekit/commit/d4ead21083aef83e17af0f1f574fcc4e36f37042))
* **db:** add priority channels and bulk SavePages to DatabaseWriteActor ([c9dee34](https://github.com/tstapler/stelekit/commit/c9dee34f0deaa5453593a12b20bec316b1ee3c3d))
* **db:** add spans table to schema and extend migration infrastructure ([c216f0f](https://github.com/tstapler/stelekit/commit/c216f0fd7c98cf3202d262c229aad68f39580bf8))
* **db:** enforce all SQL writes through @DirectSqlWrite + RestrictedDatabaseQueries ([7a880d1](https://github.com/tstapler/stelekit/commit/7a880d12908324490d10654d853b5cd88952dd92))
* **performance:** add OpenTelemetry tracing, JFR benchmarks, and frame metrics ([5e55e1a](https://github.com/tstapler/stelekit/commit/5e55e1afd5767dfc0ea8298d8315b20ddd3dd015))
* **repository:** savePages bulk write, page LRU cache, block domain model cache ([0f31ee0](https://github.com/tstapler/stelekit/commit/0f31ee0ab544b96b62cf969085862851b29685ae))
* **ui:** add rename-page to command palette ([f898fff](https://github.com/tstapler/stelekit/commit/f898fff2c5af43cb198fd4d2b32bd1295458a0a5))
* **ui:** expand performance dashboard and wire OTel into compose layer ([ed045f4](https://github.com/tstapler/stelekit/commit/ed045f40d1cc85ca5daa12c8db889bb114499a63))


### Performance Improvements

* **db:** add pooled JDBC SQLite driver and PlatformDispatcher.DB abstraction ([e4001e9](https://github.com/tstapler/stelekit/commit/e4001e9600e8b38f448550fa6f56a5aa586926ee))
* **graph-loader:** bulk chunk writes, skip-unchanged pages, single-pass mtime scan ([a6261cb](https://github.com/tstapler/stelekit/commit/a6261cbe4ce2a39361b3b781584ed4cfa30829cf))

## [0.7.1](https://github.com/tstapler/stelekit/compare/v0.7.0...v0.7.1) (2026-04-20)


### Bug Fixes

* **android:** fix graph directory not found and write failures for sub-directory SAF paths ([4e42f5f](https://github.com/tstapler/stelekit/commit/4e42f5f381cb35c2c348a3ef26eb645077f6a2ee))
* **db:** DatabaseWriteActor owns its scope, survives graph switch ([51045d1](https://github.com/tstapler/stelekit/commit/51045d168c3e0d9baa6f6c9d7343d40c2db1a233))
* **db:** remove scope param, recover from unexpected exceptions in actor loop ([53cb69b](https://github.com/tstapler/stelekit/commit/53cb69b128321ea122ae953cc6fec66783e8d1cf))

## [0.7.0](https://github.com/tstapler/stelekit/compare/v0.6.0...v0.7.0) (2026-04-20)


### Features

* **site:** apply stone brand palette, fix copy, replace emoji icons, brand favicon ([257e82c](https://github.com/tstapler/stelekit/commit/257e82c374462507b2c1829d9031f2d1f6dc6826))


### Bug Fixes

* **android:** fix hasStoragePermission=false after SAF folder pick for sub-directory paths ([c711e85](https://github.com/tstapler/stelekit/commit/c711e85ccd35b24839fe16758f428e0e8f4f5cb2))
* **brew:** copy desktop entry to user share dir on Linux install ([cf0c8af](https://github.com/tstapler/stelekit/commit/cf0c8af858d7028f3bcd1e15561c477fec6e698f))

## [0.6.0](https://github.com/tstapler/stelekit/compare/v0.5.0...v0.6.0) (2026-04-20)


### Features

* **site:** apply brand palette, add logo, add F-Droid install instructions ([d3f9bcf](https://github.com/tstapler/stelekit/commit/d3f9bcf3c0c9c5d68077f304c58ef681b82fc790))


### Bug Fixes

* **android:** fix permission recovery — spinner forever, silent failures, no logs ([5a9feae](https://github.com/tstapler/stelekit/commit/5a9feae1e576e06d15166231a327dbf19e4c9a8c))
* **brew:** fix Homebrew tap, centralize install docs, add README sync CI ([#14](https://github.com/tstapler/stelekit/issues/14)) ([8665537](https://github.com/tstapler/stelekit/commit/86655378490e4f2cb5544676d209ddd914e78338))
* **fdroid:** install fdroidserver via pip to fix androguard API 36 parse error ([f5fe954](https://github.com/tstapler/stelekit/commit/f5fe954ff073b22d0a2e4f2d56ea78af1861f5ec))
* **release:** use find instead of glob for artifact upload ([9d3055f](https://github.com/tstapler/stelekit/commit/9d3055f6a602089eb63b9f3d3635877c33c15b33))

## [0.5.0](https://github.com/tstapler/stelekit/compare/v0.4.0...v0.5.0) (2026-04-20)


### Features

* navigate to today's journal on app startup ([#12](https://github.com/tstapler/stelekit/issues/12)) ([262de3c](https://github.com/tstapler/stelekit/commit/262de3c0a64d5c78723452ccbd1915fa815835e6))


### Bug Fixes

* **ci:** fix artifact upload condition — release-please not in needs chain ([aa33116](https://github.com/tstapler/stelekit/commit/aa331160671d4b6b68be7ca8db25b5f9c2c4f851))
* **ci:** fix fdroid workflow race condition, missing permission, Node version ([04a286b](https://github.com/tstapler/stelekit/commit/04a286bb919797c86ec183f3d6e1741f93d756f9))

## [0.4.0](https://github.com/tstapler/stelekit/compare/v0.3.2...v0.4.0) (2026-04-19)


### Features

* add fdroid self-hosted repo workflow and config ([8134007](https://github.com/tstapler/stelekit/commit/8134007bda742662176b26d60438a1c141fcef42))
* add fdroid self-hosted repo workflow and config ([a3768fe](https://github.com/tstapler/stelekit/commit/a3768fe74ad53b2c05b49d6200ac749ab619ac63))
* **voice:** Story 1 — Android voice capture → Whisper STT → journal insert ([815ed32](https://github.com/tstapler/stelekit/commit/815ed32811f086eb0000300a562afd782de57a08))


### Bug Fixes

* **ci:** rebase onto origin/main before Homebrew formula push ([8d92c7f](https://github.com/tstapler/stelekit/commit/8d92c7f676dd30498bf12cf869733e071df6ac58))
* pin Node.js to 24 LTS in pages workflow ([ffa739e](https://github.com/tstapler/stelekit/commit/ffa739ec003ea6fb0c67701e7606605148b18c25))
* **ui:** persist graph path before switchGraph to fix spinner-forever bug ([13cc5ea](https://github.com/tstapler/stelekit/commit/13cc5ea94a48702ab64acad27dccc3955951a256))
* upgrade Node.js to 24 LTS in GitHub Pages workflow ([470df18](https://github.com/tstapler/stelekit/commit/470df18823fe22e1001139f16e7789a47e089b3e))
* upgrade Node.js to lts/* in pages workflow ([4b9790a](https://github.com/tstapler/stelekit/commit/4b9790a40454ca00ddc7a6b35c89229bbfb07290))
* **voice:** fix iOS framework link check — remove JVM-only APIs from commonMain ([5c2437e](https://github.com/tstapler/stelekit/commit/5c2437ead13652b563d8f6dd041cd4dc5737f8c0))

## [0.3.2](https://github.com/tstapler/stelekit/compare/v0.3.1...v0.3.2) (2026-04-19)


### Bug Fixes

* **highlighting:** highlight multi-word unlinked page names in view mode ([e717fed](https://github.com/tstapler/stelekit/commit/e717fedc790aea98ce0c2ba22478a6a9343fa0f3))
* **highlighting:** highlight multi-word unlinked page names in view mode ([ec54c7d](https://github.com/tstapler/stelekit/commit/ec54c7d2fa6d98c4f677ea3d602dbd6c5d4f09cb))

## [0.3.1](https://github.com/tstapler/stelekit/compare/v0.3.0...v0.3.1) (2026-04-19)


### Bug Fixes

* **desktop:** fix JFileChooser crash and add installer/tap integration ([f3eca8f](https://github.com/tstapler/stelekit/commit/f3eca8f145336ddaf51d8dec05473b09019c2d8b))
* **release:** fix SHA256 update to replace existing values not just placeholders ([7fd170c](https://github.com/tstapler/stelekit/commit/7fd170c138c22854bdfc8eba7d2827f3558e64b0))
* **release:** prepend download table to Release Please changelog ([b0e0c3e](https://github.com/tstapler/stelekit/commit/b0e0c3e64c03f4486c7dd28ba36ff803789864c1))
* **release:** trigger on release event instead of tag push ([838aad2](https://github.com/tstapler/stelekit/commit/838aad2e850689ee1fc3102ad9dfcca2e4999cf0))

## [0.3.0](https://github.com/tstapler/stelekit/compare/v0.2.0...v0.3.0) (2026-04-19)


### Features

* wasmJs browser demo, Astro/Starlight docs site, and GitHub Pages CI/CD ([4774c51](https://github.com/tstapler/stelekit/commit/4774c5151da66f4c4bbc9b990fc7c99b1bc2a00d))


### Bug Fixes

* address PR review comments ([3867b15](https://github.com/tstapler/stelekit/commit/3867b15e7832e0e65369c033400da256cfbe176c))
* **ci:** revert GraphManager Clock import to kotlin.time.Clock ([56e33e5](https://github.com/tstapler/stelekit/commit/56e33e5ad6b95fb0c9f9b08c9352e958a48d44a0))

## [0.2.0](https://github.com/tstapler/stelekit/compare/v0.1.0...v0.2.0) (2026-04-18)


### Features

* **release:** add RPM, AppImage, and Homebrew tap support ([3915208](https://github.com/tstapler/stelekit/commit/391520846e2fbb7edc96bcbb8c185c7d6bd3e31b))


### Bug Fixes

* **ci:** downgrade Gradle to 8.7 to fix iOS KotlinNativeBundleBuildService error ([ac9ff53](https://github.com/tstapler/stelekit/commit/ac9ff530dc858be6cb5b821e0c86bab262d5a851))
* **ci:** mark iOS CI non-blocking due to two pre-existing failures ([6872bc0](https://github.com/tstapler/stelekit/commit/6872bc05d9ce232e5f557bf09e4cdeacab20cd09))
* **ci:** use compileCommonMainKotlinMetadata to avoid iOS toolchain property error ([c7ec54d](https://github.com/tstapler/stelekit/commit/c7ec54deff0d7a716e3f5a7c1397a0d7bf101559))
* **ci:** work around KotlinNativeBundleBuildService/Gradle 8.8+ incompatibility in iOS CI ([710062e](https://github.com/tstapler/stelekit/commit/710062e80fa1383098c8a6659bbcd043cd56add2))
* **desktop:** include java.sql module in jlink JRE for SQLDelight JDBC ([86ab81a](https://github.com/tstapler/stelekit/commit/86ab81a4afa7c4f47be4d183900c4a70261182eb))
* **journal:** normalize journal page names to underscore format ([d79f8f0](https://github.com/tstapler/stelekit/commit/d79f8f084e2036a59c2333b035679c6a549ef513))
* **journal:** normalize journal page names to underscore format ([c6e7c50](https://github.com/tstapler/stelekit/commit/c6e7c502f6ad8857cc5bae2631213b75392dc886))
* **migration:** preserve block UUIDs and fix iOS CI in journal normalization ([217af1f](https://github.com/tstapler/stelekit/commit/217af1f4faa789ac8bbad2f2dce482941efdd4ae))
* **release:** detect ARCH dynamically via uname -m for AppImage ([c537c4e](https://github.com/tstapler/stelekit/commit/c537c4eced3e59fbd39e25500f7a5e4a7dcbe7b7))
* **release:** fix AppImage desktop integration and icon paths ([1c055c8](https://github.com/tstapler/stelekit/commit/1c055c8187a0c49a4754f460d8788ca729a43f05))
* **release:** set ARCH=x86_64 for appimagetool and fix desktop Categories ([2a9627d](https://github.com/tstapler/stelekit/commit/2a9627dabea3a5b3c139f50158ff38a8b66f7ddd))
