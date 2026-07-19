# BC-UR reference vectors — provenance

These vectors are ported from the official BlockchainCommons/bc-ur C++ reference
implementation's `test/test.cpp` (BSD-2-Clause Plus Patent License), commit tracked at
`master` as of 2026-07-11:

- `src/xoshiro256.cpp` / `xoshiro256.hpp` — deterministic RNG (seed = SHA-256 of input bytes).
- `src/random-sampler.cpp` — Walker-Vose alias method degree sampler.
- `src/fountain-encoder.cpp` / `fountain-utils.cpp` — LT fountain encoder (BCR-2020-012).
- `src/fountain-decoder.cpp` — LT peeling/reduction decoder.
- `test/test.cpp` — `test_rng_1`, `test_rng_2`, `test_rng_3`, `test_random_sampler`,
  `test_shuffle`, `test_find_fragment_length`, `test_partition_and_join`, `test_choose_degree`,
  `test_choose_fragments`, `test_fountain_encoder` (the primary 20-part reference vector for a
  256-byte `make_message("Wolf")` payload with `maxFragmentBytes=30`).

**Why these vectors are embedded as Kotlin source (`FountainCodecVectorTest.kt`) instead of read
from this directory at test runtime**: validation.md's Epic 1.2 row explicitly requires Layer 1
to have "no platform/I/O dependency" — reading a resource file at test time would itself be I/O,
and KMP's `commonTest` has no uniform cross-target (JVM/Android/iOS/JS/Wasm) resource-loading API
in this repo (see `kmp/src/commonMain/resources/demo-graph/` for the project's existing precedent
of codegen-ing embedded data instead of loading it at runtime). This directory exists for
provenance/documentation only, mirroring the exact byte data asserted in
`FountainCodecVectorTest.kt`.
