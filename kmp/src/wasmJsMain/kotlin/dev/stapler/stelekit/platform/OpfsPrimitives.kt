package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.await

// Story 1.4.7 primitives (Task 1.4.7e). Each JS body resolves to '' on success or to the rejection's `name`, so no
// exception shape has to cross the Kotlin/JS boundary and a QuotaExceededError is told apart by its name only.

private fun jsOpfsMove(from: String, to: String): kotlin.js.Promise<JsString> = js(
    """(async function() {
  try {
    const walk = async function(p, create) {
      let dir = await navigator.storage.getDirectory();
      const segs = p.replace(/^\//, '').split('/');
      for (let i = 0; i < segs.length - 1; i++) dir = await dir.getDirectoryHandle(segs[i], { create: create });
      return { dir: dir, name: segs[segs.length - 1] };
    };
    const src = await walk(from, false);
    const dst = await walk(to, true);
    const handle = await src.dir.getFileHandle(src.name);
    await handle.move(dst.dir, dst.name);
    return '';
  } catch (e) { return String((e && e.name) || 'Error'); }
})()"""
)

private fun jsOpfsCopyVerify(from: String, to: String): kotlin.js.Promise<JsString> = js(
    """(async function() {
  try {
    const walk = async function(p, create) {
      let dir = await navigator.storage.getDirectory();
      const segs = p.replace(/^\//, '').split('/');
      for (let i = 0; i < segs.length - 1; i++) dir = await dir.getDirectoryHandle(segs[i], { create: create });
      return { dir: dir, name: segs[segs.length - 1] };
    };
    const readAll = async function(fh) { return new Uint8Array(await (await fh.getFile()).arrayBuffer()); };
    const src = await walk(from, false);
    const dst = await walk(to, true);
    const tmp = await src.dir.getFileHandle(src.name);
    const bytes = await readAll(tmp);
    const dest = await dst.dir.getFileHandle(dst.name, { create: true });
    const w = await dest.createWritable();
    try { await w.write(bytes); await w.close(); } catch (e) { try { await w.abort(); } catch (ignored) {} throw e; }
    const back = await readAll(dest);
    if (back.length !== bytes.length) return 'VerifyMismatch';
    for (let i = 0; i < bytes.length; i++) if (back[i] !== bytes[i]) return 'VerifyMismatch';
    await src.dir.removeEntry(src.name);
    return '';
  } catch (e) { return String((e && e.name) || 'Error'); }
})()"""
)

private fun jsOpfsReadSlice(path: String, maxBytes: Int): kotlin.js.Promise<JsAny?> = js(
    """(async function() {
  try {
    let dir = await navigator.storage.getDirectory();
    const segs = path.replace(/^\//, '').split('/');
    for (let i = 0; i < segs.length - 1; i++) dir = await dir.getDirectoryHandle(segs[i], { create: false });
    const file = await (await dir.getFileHandle(segs[segs.length - 1])).getFile();
    return await file.slice(0, maxBytes).arrayBuffer();
  } catch (e) { return null; }
})()"""
)

private fun jsOpfsPersist(): kotlin.js.Promise<JsString> = js(
    """(async function() {
  try {
    if (typeof navigator === 'undefined' || !navigator.storage || typeof navigator.storage.persist !== 'function') return 'unsupported';
    if (typeof navigator.storage.persisted === 'function' && await navigator.storage.persisted()) return 'granted';
    return (await navigator.storage.persist()) ? 'granted' : 'denied';
  } catch (e) { return 'denied'; }
})()"""
)

private fun jsOpfsQuota(): kotlin.js.Promise<JsAny?> = js(
    """(async function() {
  try {
    const est = await navigator.storage.estimate();
    return (est && typeof est.quota === 'number') ? String(Math.floor(est.quota)) : null;
  } catch (e) { return null; }
})()"""
)

private fun jsOpfsRootPresent(): Boolean = js(
    "typeof navigator !== 'undefined' && !!navigator.storage && typeof navigator.storage.getDirectory === 'function'"
)

private fun opfsResult(path: String, errorName: String): Either<DomainError, Unit> =
    if (errorName.isEmpty()) Unit.right() else OpfsErrors.write(path, errorName)

/** Moves the file at [from] over [to] with `FileSystemFileHandle.move()`: atomic where the browser supports it. */
internal suspend fun opfsMoveFile(from: String, to: String): Either<DomainError, Unit> =
    opfsResult(to, jsOpfsMove(from, to).await<JsString>().toString())

/**
 * The fallback replace: copies [from] over [to], reads [to] back, compares, and only then deletes [from]. An
 * interrupted copy aborts its writable, so [to] keeps its old bytes.
 */
internal suspend fun opfsCopyVerify(from: String, to: String): Either<DomainError, Unit> =
    opfsResult(to, jsOpfsCopyVerify(from, to).await<JsString>().toString())

/** The first [maxBytes] bytes of the file at [path] without reading the rest; null when the file or the slice read is unavailable. */
internal suspend fun opfsReadSlice(path: String, maxBytes: Int): ByteArray? =
    jsOpfsReadSlice(path, maxBytes).await<JsAny?>()?.toKotlinByteArray()

/** Asks the browser to keep this origin's storage (`navigator.storage.persist()`); call it from a user gesture. */
internal suspend fun opfsRequestPersist(): PersistenceState = when (jsOpfsPersist().await<JsString>().toString()) {
    "granted" -> PersistenceState.Granted
    "unsupported" -> PersistenceState.Unsupported
    else -> PersistenceState.Denied
}

/** `navigator.storage.estimate().quota` in bytes, or null when the browser gives none. */
internal suspend fun opfsQuotaBytes(): Long? = jsOpfsQuota().await<JsAny?>()?.toString()?.toLongOrNull()

/** True when the browser exposes `navigator.storage.getDirectory` at all (the first probe fact, before any call). */
internal fun opfsApiPresent(): Boolean = jsOpfsRootPresent()
