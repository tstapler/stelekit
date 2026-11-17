// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.browser

import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

// js() calls must be top-level functions in Kotlin/Wasm.

/** True when the browser can wait on a Web Lock with an abort signal, the only way the page can reload itself when the lock frees. */
private fun jsCanWaitOnLock(): Boolean = js(
    "typeof navigator !== 'undefined' && !!navigator.locks && typeof navigator.locks.request === 'function' && typeof AbortController === 'function'"
)

/**
 * Builds the plain-DOM boot-refusal screen into `#loading` (ux.md 14.9, the `boot-refusal` block): a region named by its
 * level-1 heading, a message with `role="alert"` as the one announcement (focus is NOT moved), [Reload] as the first tab stop
 * and [Open temporarily] as the second, every size in rem. Returns nothing; the screen has no graph name, path or id.
 */
private fun jsRenderBootRefusal(
    heading: String,
    message: String,
    waiting: String,
    action: String,
    actionName: String,
    secondary: String,
    secondaryName: String,
    target: String,
    showWaiting: Boolean,
): Unit = js(
    """
    (function() {
        window.__stelekit_boot_error = true;
        var loading = document.getElementById('loading');
        if (!loading) return;
        loading.innerHTML = '';
        loading.style.overflowY = 'auto';
        loading.style.justifyContent = 'flex-start';
        loading.style.alignItems = 'stretch';
        var style = document.createElement('style');
        style.textContent = '#boot-refusal button:focus-visible, #boot-refusal a:focus-visible { outline: 2px solid #ffffff; outline-offset: 2px; }';
        loading.appendChild(style);
        var section = document.createElement('section');
        section.id = 'boot-refusal';
        section.setAttribute('aria-labelledby', 'boot-refusal-heading');
        section.style.cssText = 'padding:1rem;font-size:1rem;line-height:1.5;color:#cccccc;max-width:40rem;margin:0 auto;overflow-wrap:anywhere;';
        var h = document.createElement('h1');
        h.id = 'boot-refusal-heading';
        h.textContent = heading;
        h.style.cssText = 'font-size:1.5rem;line-height:1.5;margin:0 0 1rem 0;font-weight:600;';
        var msg = document.createElement('p');
        msg.id = 'boot-refusal-message';
        msg.setAttribute('role', 'alert');
        msg.textContent = message;
        msg.style.cssText = 'margin:0 0 1rem 0;';
        var reload = document.createElement('button');
        reload.id = 'boot-refusal-reload';
        reload.type = 'button';
        reload.textContent = action;
        reload.setAttribute('aria-label', actionName);
        reload.style.cssText = 'min-height:2.75rem;min-width:2.75rem;padding:0 1rem;font-size:1rem;color:#ffffff;background:#2f6fd6;border:0;border-radius:0.25rem;margin:0 1rem 1rem 0;cursor:pointer;';
        reload.addEventListener('click', function() { location.reload(); });
        var other = document.createElement('a');
        other.id = 'boot-refusal-temporary';
        other.href = target;
        other.textContent = secondary;
        other.setAttribute('aria-label', secondaryName);
        other.style.cssText = 'display:inline-flex;align-items:center;min-height:2.75rem;min-width:2.75rem;color:#8ab4f8;margin:0 0 1rem 0;';
        var row = document.createElement('div');
        row.appendChild(reload);
        row.appendChild(other);
        section.appendChild(h);
        section.appendChild(msg);
        section.appendChild(row);
        if (showWaiting) {
            var wait = document.createElement('p');
            wait.id = 'boot-refusal-waiting';
            wait.textContent = waiting;
            wait.style.cssText = 'margin:0;';
            section.appendChild(wait);
        }
        var status = document.createElement('p');
        status.id = 'boot-refusal-status';
        status.setAttribute('role', 'status');
        status.style.cssText = 'margin:0;';
        section.appendChild(status);
        loading.appendChild(section);
    })()
    """
)

/** Waits for [lockName] with an abort signal; once granted, shows [freeText] as a polite status and reloads after 1 s. */
private fun jsReloadWhenLockFrees(lockName: String, freeText: String): Unit = js(
    """
    (function() {
        var controller = new AbortController();
        navigator.locks.request(lockName, { signal: controller.signal }, function() {
            var wait = document.getElementById('boot-refusal-waiting');
            if (wait) wait.textContent = '';
            var status = document.getElementById('boot-refusal-status');
            if (status) status.textContent = freeText;
            setTimeout(function() { location.reload(); }, 1000);
            return Promise.resolve();
        }).catch(function() {});
    })()
    """
)

/** The tab-lifetime driver lock of a graph (`DriverFactory.createDriverAsync`); a refusal waits on this name. */
internal fun driverLockName(graphId: String): String = "stelekit-sqlite-driver-$graphId"

/** Draws the screen: with [retryAvailable] the message promises the automatic reload and the waiting line shows. */
internal fun renderBootRefusal(retryAvailable: Boolean) {
    val strings = SketchMigrationStrings.BootRefusal
    jsRenderBootRefusal(
        heading = strings.HEADING,
        message = if (retryAvailable) strings.MESSAGE else strings.MESSAGE_NO_RETRY,
        waiting = strings.STATUS_WAITING,
        action = strings.ACTION,
        actionName = strings.ACTION_ACCESSIBLE_NAME,
        secondary = strings.SECONDARY_ACTION,
        secondaryName = strings.SECONDARY_ACCESSIBLE_NAME,
        target = strings.SECONDARY_TARGET,
        showWaiting = retryAvailable,
    )
}

/**
 * Replaces the plain `<p>` of the boot error with the boot-refusal screen of ux.md 14.9 (UX-152, D-149). With the Web Locks
 * API the page waits on [lockName] and reloads when it frees; without it the `message-no-retry` text is shown and nothing waits.
 */
internal fun showBootRefusal(lockName: String) {
    val retryAvailable = jsCanWaitOnLock()
    renderBootRefusal(retryAvailable)
    if (retryAvailable) jsReloadWhenLockFrees(lockName, SketchMigrationStrings.BootRefusal.STATUS_FREE)
}
