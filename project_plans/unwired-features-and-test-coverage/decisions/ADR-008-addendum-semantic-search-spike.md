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

## Verdict

- kNN latency: a persisted-embedding design fits the budget on JVM with a full scan and on Android only with the page-level pre-filter (ANN not needed). If built, Story 9.3 must use the pre-filter, store a page centroid per page, and add a recall test against full scan.
- Embedding model: NOT measured. The repo has ONNX Runtime (depth model, image labeler) but no text-embedding path: no tokenizer, no model, no `InferenceWorker` implementation. The plan's acceptance criteria (dimension, model size, per-block latency, peak heap for 1,000 blocks on JVM and the emulator) remain open.
- Because the model half is open, the build is NOT yet confirmed, and Stories 9.2-9.4 were not started. A feature that ships a toggle with no embedding source would be dead code behind a flag. Next step: a model spike (e.g. a small WordPiece-tokenised sentence encoder through the existing ONNX plumbing, with a licence and download-size check), then re-run this addendum's recall test on its output.

Spike sources: `scripts/spikes/KnnScanSpike.java`, `scripts/spikes/AndroidKnnSpike.java`.
