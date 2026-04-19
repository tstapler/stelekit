# Changelog

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
