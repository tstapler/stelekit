@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spike 0.1.8a: throwaway OPFS probe for Option W, replaced by `OpfsAtomicReplaceTest` in Story 1.4.7.
 * Run: `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests '*OpfsAtomicWriteSpikeTest*'`.
 * The probe never fails on a missing browser feature; it prints one `OPFS-SPIKE-RESULT {json}` line
 * that research/opfs-spike.md records. The test only asserts the probe itself ran.
 */
class OpfsAtomicWriteSpikeTest {

    @Test
    fun probeOpfsAtomicWriteCapabilities() = runTest {
        val json = opfsSpikeProbe().await<JsString>().toString()
        println("OPFS-SPIKE-RESULT $json")
        assertTrue(json.contains("\"probeCompleted\":true"), "probe did not complete: $json")
    }
}

private fun opfsSpikeProbe(): kotlin.js.Promise<JsString> = js(
    """(async () => {
  const r = { env: {}, a: {}, b: {}, c: {}, d: {}, e: {}, lastModified: {} };
  const SIZE = 2 * 1024 * 1024;
  const fill = (seed) => { const u = new Uint8Array(SIZE); for (let i = 0; i < SIZE; i++) u[i] = (i * 31 + seed) & 255; return u; };
  const same = (x, y) => { if (x.length !== y.length) return false; for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false; return true; };
  const readAll = async (fh) => new Uint8Array(await (await fh.getFile()).arrayBuffer());
  const err = (e) => String((e && e.name) || '') + ': ' + String((e && e.message) || e);
  const now = () => performance.now();
  try {
    r.env.userAgent = navigator.userAgent;
    r.env.isSecureContext = self.isSecureContext;
    r.env.getDirectory = !!(navigator.storage && navigator.storage.getDirectory);
    r.env.persistFn = !!(navigator.storage && navigator.storage.persist);
    r.env.createWritableProto = typeof FileSystemFileHandle !== 'undefined' && typeof FileSystemFileHandle.prototype.createWritable === 'function';
    r.env.moveProto = typeof FileSystemFileHandle !== 'undefined' && typeof FileSystemFileHandle.prototype.move === 'function';
    r.env.createSyncAccessHandleProto = typeof FileSystemFileHandle !== 'undefined' && typeof FileSystemFileHandle.prototype.createSyncAccessHandle === 'function';
    r.env.userActivationIsActive = !!(navigator.userActivation && navigator.userActivation.isActive);
    if (!r.env.getDirectory) { r.probeCompleted = true; return JSON.stringify(r); }
    const root = await navigator.storage.getDirectory();
    const dirName = 'opfs-spike-' + Math.floor(Math.random() * 1e9);
    const dir = await root.getDirectoryHandle(dirName, { create: true });
    try {
      const A = fill(1), B = fill(2), C = fill(3);

      // (d) 2 MB write and read back, timed
      let t0 = now();
      const fh = await dir.getFileHandle('sheet.bin', { create: true });
      let w = await fh.createWritable(); await w.write(A); await w.close();
      r.d.writeMs = now() - t0; t0 = now();
      const back = await readAll(fh);
      r.d.readMs = now() - t0;
      r.d.bytes = SIZE; r.d.readBackEqual = same(back, A);
      try { const est = await navigator.storage.estimate(); r.d.quotaBytes = est.quota; r.d.usageBytes = est.usage; } catch (e) { r.d.estimateError = err(e); }

      // (a) invisible until close, new bytes after close
      w = await fh.createWritable(); await w.write(B);
      const mid = await readAll(fh);
      r.a.oldVisibleBeforeClose = same(mid, A);
      t0 = now(); await w.close(); r.a.closeMs = now() - t0;
      r.a.newVisibleAfterClose = same(await readAll(fh), B);
      // (a) abort leaves the old bytes
      w = await fh.createWritable(); await w.write(C);
      try { await w.abort(); r.a.abortOk = true; } catch (e) { r.a.abortError = err(e); }
      r.a.oldAfterAbort = same(await readAll(fh), B);
      // truncate-on-open check: a writable opened without keepExistingData and never written, then aborted
      w = await fh.createWritable(); try { await w.abort(); } catch (e) {}
      r.a.oldAfterEmptyAbort = same(await readAll(fh), B);

      // (b1) move() over an existing file
      if (r.env.moveProto) {
        const tmp = await dir.getFileHandle('sheet.tmp', { create: true });
        let tw = await tmp.createWritable(); await tw.write(C); await tw.close();
        try {
          t0 = now(); await tmp.move(dir, 'sheet.bin'); r.b.moveMs = now() - t0;
          const dest = await dir.getFileHandle('sheet.bin');
          r.b.moveDestHoldsNew = same(await readAll(dest), C);
          try { await dir.getFileHandle('sheet.tmp'); r.b.moveTempGone = false; } catch (e) { r.b.moveTempGone = (e && e.name === 'NotFoundError'); }
          r.b.moveWithDirArgs = true;
        } catch (e) { r.b.moveError = err(e); }
        // single-argument form move(name)
        try {
          const t2 = await dir.getFileHandle('sheet2.tmp', { create: true });
          let w2 = await t2.createWritable(); await w2.write(A); await w2.close();
          await t2.move('sheet.bin');
          r.b.moveNameOnlyDestHoldsNew = same(await readAll(await dir.getFileHandle('sheet.bin')), A);
        } catch (e) { r.b.moveNameOnlyError = err(e); }
      } else { r.b.moveUnsupported = true; }

      // (b2) copy-with-verify path (the move fallback), with a mid-copy abort leaving the old destination
      {
        const dst = await dir.getFileHandle('copy.bin', { create: true });
        let cw = await dst.createWritable(); await cw.write(A); await cw.close();
        const tmp = await dir.getFileHandle('copy.tmp', { create: true });
        cw = await tmp.createWritable(); await cw.write(B); await cw.close();
        const tmpBytes = await readAll(tmp);
        r.b.copyTempVerified = same(tmpBytes, B);
        cw = await dst.createWritable(); await cw.write(tmpBytes.subarray(0, SIZE / 2));
        await cw.abort();
        r.b.copyInterruptedKeepsOld = same(await readAll(dst), A);
        t0 = now();
        cw = await dst.createWritable(); await cw.write(tmpBytes); await cw.close();
        r.b.copyMs = now() - t0;
        r.b.copyDestVerified = same(await readAll(dst), B);
        await dir.removeEntry('copy.tmp');
        try { await dir.getFileHandle('copy.tmp'); r.b.copyTempDeleted = false; } catch (e) { r.b.copyTempDeleted = (e && e.name === 'NotFoundError'); }
      }

      // (c) slice read of the first 256 bytes
      {
        const f = await (await dir.getFileHandle('sheet.bin')).getFile();
        const head = new Uint8Array(await f.slice(0, 256).arrayBuffer());
        const whole = new Uint8Array(await f.arrayBuffer());
        r.c.sliceLength = head.length;
        r.c.sliceMatchesPrefix = head.length === 256 && same(head, whole.subarray(0, 256));
        r.lastModified.type = typeof f.lastModified; r.lastModified.value = f.lastModified;
        r.lastModified.available = typeof f.lastModified === 'number' && f.lastModified > 0;
      }

      // (e) persistence: recorded, never gating. No user gesture in a headless test run.
      try {
        r.e.persistedBefore = await navigator.storage.persisted();
        t0 = now();
        const granted = await Promise.race([navigator.storage.persist(), new Promise((res) => setTimeout(() => res('timeout-5s'), 5000))]);
        r.e.persistAnswer = granted; r.e.persistMs = now() - t0;
        r.e.persistedAfter = await navigator.storage.persisted();
        r.e.calledFromUserGesture = false;
      } catch (e) { r.e.persistError = err(e); }
    } finally {
      try { await root.removeEntry(dirName, { recursive: true }); } catch (e) {}
    }
    r.probeCompleted = true;
  } catch (e) { r.probeError = err(e); r.probeCompleted = true; }
  return JSON.stringify(r);
})()"""
)
