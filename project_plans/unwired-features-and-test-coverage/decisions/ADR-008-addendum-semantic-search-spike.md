# ADR-008 addendum: Spike 9.1 results (OD-1, semantic search / U15)

Scope: the kNN-latency half of Spike 9.1. The embedding-model half is NOT done (see Verdict).
Workload: 100,000 stored vectors (9,500 pages x about 10.5 blocks; the block count is an assumption, INFERRED), 384 dimensions (MiniLM-class), little-endian float32 BLOBs in SQLite, 500 rows per chunk, running top-20 heap, no `List<Block>` materialised. Budget: p95 300 ms to first results (the plan's proposed figure, INFERRED).

## Commands and numbers

JVM (VERIFIED, run 2026-11-01, JDK 25.0.2, sqlite-jdbc 3.51.3.0, `taskset -c 0-3`, `-Xmx512m`, 20 warm runs, DB 226 MB):

```
java -Xmx512m -cp sqlite-jdbc-3.51.3.0.jar:slf4j-api-2.0.18.jar scripts/spikes/KnnScanSpike.java 100000 384 9500
full-float           min=120ms median=133ms p95=150ms max=160ms
full-int8            min=106ms median=112ms p95=119ms max=122ms
prefilter(50 pages)  min=13ms  median=14ms  p95=18ms  max=19ms
```

Android runtime (VERIFIED for the emulator; Pixel_Fold AVD, API 34 x86_64 under KVM, 4 vCPUs, ART + `android.database.sqlite`, run via `app_process` with `-Xmx512m`, 12 warm runs). `adb logcat` was not used.

```
javac --release 17 -cp android-34/android.jar scripts/spikes/AndroidKnnSpike.java; d8 ... ; adb push classes.dex /data/local/tmp/knn.dex
adb shell "CLASSPATH=/data/local/tmp/knn.dex app_process -Xmx512m /system/bin AndroidKnnSpike 100000 384 9500 /data/local/tmp/knn.db"
full-float          min=261ms median=270ms max=281ms
prefilter(50 pages) min=46ms  median=52ms  max=58ms
```

## Interpretation

- Desktop JVM: full scan fits the budget (p95 150 ms). VERIFIED.
- Android: the emulator is an x86 guest on a 24-core desktop host, so it is a lower bound for a real phone. Full scan is already 270 ms median there, with only 30 ms of headroom, and a phone core is likely 2-4x slower (INFERRED, not measured: no physical device). Full scan is therefore expected to MISS the 300 ms budget on a real phone.
- The page-centroid pre-filter (score 9.5k page centroids, then scan the blocks of the top 50 pages) costs 52 ms on the emulator (about 6x under budget), which leaves room for a phone-class slowdown. VERIFIED on the emulator, phone figure INFERRED.
- Caveat: the vectors are random unit vectors, so this measures cost only. Recall of the pre-filter on real text embeddings is UNMEASURED and could not be measured here (random data has no cluster structure). A centroid pre-filter can miss a relevant block on an otherwise off-topic page; this needs a recall check against full scan on real embeddings before it ships.
- Storage: 100k x 384 float32 = 154 MB of BLOB (226 MB DB with the page index and a second int8 column used by the spike); int8 quantisation cuts the vector payload 4x and the scan time about 15% on JVM.

## Model spike and recall (second pass)

Model (VERIFIED): `sentence-transformers/all-MiniLM-L6-v2`, licence apache-2.0 (HF API tag), 384 dimensions, BERT WordPiece vocab (`vocab.txt`, 231,508 bytes, sha256 07eced37...). Files from `https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/`: `onnx/model_qint8_arm64.onnx` 23,026,053 bytes (sha256 4278337f...) and `onnx/model.onnx` fp32 90,405,214 bytes. The int8 ARM64 file was the one benchmarked.

Embedding cost (VERIFIED, JVM only; `onnxruntime` 1.26.0, 4 CPUs via `taskset -c 0-3`, `-Xmx512m`, batch 16, max 128 tokens, own WordPiece tokenizer in the spike):

```
java -Xmx512m -Dthreads=4 -cp onnxruntime-1.26.0.jar scripts/spikes/EmbedSpike.java model_qint8_arm64.onnx vocab.txt <dir> 3000 timing
dim=384 embed 1000 blocks: total=3883ms per-block=3.88ms peakJavaHeap=193MB VmHWM=505MB   (30k-block corpus run: 3.65 ms/block, heap 307MB, VmHWM 621MB)
```

Not done: the same measurement on the Pixel_Fold AVD. `onnxruntime-android` needs its native library packaged in an APK, which `app_process` cannot load; it needs an instrumented test. A phone is expected to be several times slower per block (INFERRED), so indexing 100k blocks (about 6 min of 4-thread desktop CPU) is a long background job on a phone; the plan's idle/charging gate is mandatory. Peak Java heap was above what a 512 MB Android heap tolerates comfortably without a smaller batch (INFERRED; only the JVM was measured).

Recall on real embeddings (VERIFIED, same command without `timing`): corpus = this repo's `*.md` files, 944 pages = files, 30,005 blocks = paragraphs of 40-600 chars; 300 queries = random 3-6 word fragments of random blocks; truth = full-scan top 20.

```
page-centroid pre-filter, recall@20:
  top 10 pages (1.1%)  0.303     top 25 (2.6%)  0.431     top 50 (5.3%)  0.540
  top 100 (10.6%)      0.659     top 200 (21.2%) 0.790    top 400 (42.4%) 0.910
  pages needed for full recall: p50=504 p90=770 p95=839 of 944
IVF (spherical k-means over blocks, 173 lists = sqrt(N), 12 iterations), recall@20:
  nprobe=4  2.6% scanned 0.654    nprobe=8  4.9% 0.738    nprobe=16 9.4% 0.821
  nprobe=32 18.5% 0.903           nprobe=64 36.6% 0.961
```

Corpus caveat: technical English docs, not personal notes; fragment queries are easy matches; one corpus only.

## Verdict

- Page-centroid pre-filter FAILS: recall 0.54 at 5% of pages, and it needs more than 40% of pages for 0.91 (no point below the cost of a full scan). The first-pass proposal is withdrawn.
- Full scan: fits on JVM (p95 150 ms), marginal on the emulator (270 ms vs 300 ms) and expected over budget on a phone (INFERRED).
- Only block-level IVF reaches recall >= 0.95 (0.961 at nprobe=64, 36.6% of blocks scanned), which is about 100 ms on the emulator (INFERRED by scaling the measured 270 ms full scan) and 200-400 ms on a phone (INFERRED). Margin against the 300 ms budget is thin and unmeasured on a device; it also adds a k-means build, incremental list assignment and re-clustering to the indexer.
- Model: licence, size (23 MB int8) and dimension are acceptable; JVM latency is acceptable; Android latency and heap are unmeasured.
- Decision: the build is NOT confirmed. Stories 9.2-9.4 were not started. Recommendation: DEFER (keep `VectorSearch` and family, unwired, flag-free) until a physical-phone or instrumented-test run measures (a) ORT-Android per-block embed latency and heap and (b) IVF query latency at nprobe=64 on 100k stored vectors. If both fit, build Epic 9 with IVF, not the page pre-filter. Delete only if the owner prefers to close OD-1 now.

Spike sources: `scripts/spikes/KnnScanSpike.java`, `scripts/spikes/AndroidKnnSpike.java`, `scripts/spikes/EmbedSpike.java`.
