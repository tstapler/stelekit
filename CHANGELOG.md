# Changelog

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
