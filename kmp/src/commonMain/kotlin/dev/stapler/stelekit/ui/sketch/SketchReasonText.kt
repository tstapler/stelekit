// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.ModeDisabled
import dev.stapler.stelekit.projectbridge.V2Storage

/** Why a new sheet is created classic: the ids of the `ClassicEditor` toast's Details (ux.md 14.1). */
sealed interface ClassicReason {
    data object ModeOff : ClassicReason
    data object BuildCapped : ClassicReason
    data object ProjectNotNew : ClassicReason
    data object TechnicalData : ClassicReason
    data class Storage(val storage: V2Storage) : ClassicReason
}

/**
 * The closed set of reasons of the `ClassicEditor` toast (ux.md 14.1 table) in PRECEDENCE order: a toast names only the
 * first applicable one (UX-131). [settingsCanChange] is true only for the New-sketch-projects-only reasons, where choosing
 * All sketches (Upgrade) in Settings would change the outcome.
 */
enum class ClassicNoticeReason(val settingsCanChange: Boolean) {
    Platform(false),
    BuildCapped(false),
    TechnicalData(false),
    ModeOff(false),
    NpoClassicSheets(true),
    NpoEmptyProject(true),
    NpoUnreadable(true),
    UserChose(false),
}

/**
 * The ONE table of reason strings the router, the mode control and the render dispatcher show. Storage texts follow
 * the web reason table of ux.md 14.1; the rest are the strings quoted by the router matrix of plan Story 1.4.3.
 */
object SketchReasonText {
    const val READ_ONLY_TURN_ON = "Turn on the new editor to edit."
    const val NEWER_SCHEMA = "Update SteleKit to view this sketch"
    const val BUILD_CAPPED = "Not available in this build yet"
    const val OTHER_TAB = "Edited in another tab."
    const val CHECKING_STORAGE = "Checking whether this browser can save new-editor sketches..."
    const val HAS_TECHNICAL_DATA = "This sketch has zones or dimensions the new editor can't edit yet."
    const val UPGRADE_UNAVAILABLE_PREFIX = "Can't upgrade this sketch: "

    const val STORAGE_IOS = "The new editor can't save sketches on iOS yet."
    const val STORAGE_NO_STORE = "The new editor isn't available in the demo graph or while no graph is open."

    private const val DESKTOP_OR_ANDROID = "use the desktop or Android app."
    private const val UNSAFE_BROWSER = "This browser can't save new-editor sketches safely. Try another browser, or $DESKTOP_OR_ANDROID"
    private const val IOS_WEBKIT =
        "On iPhone and iPad every browser uses Safari's engine, which can't save new-editor sketches yet. Use the desktop or Android app."
    private const val NO_OPFS =
        "This browser window can't keep new-editor sketches. Private windows often can't. " +
            "Try a normal window, another browser, or the desktop or Android app."

    /** The text of one web storage reason ([iosWebKit] only chooses the `NoOpfs` wording). */
    fun webStorage(reason: StorageReason, iosWebKit: Boolean): String = when (reason) {
        StorageReason.NoOpfs -> if (iosWebKit) IOS_WEBKIT else NO_OPFS
        StorageReason.NoCreateWritable, StorageReason.NoAtomicReplace, StorageReason.NoSliceRead -> UNSAFE_BROWSER
        StorageReason.PreloadFailed ->
            "SteleKit couldn't read this browser's saved sketches. Reload the page. If it keeps happening, copy the details."
        StorageReason.PreloadTooLarge ->
            "This project's sketches are too large to open in a browser (over 128 MB). Open it on the desktop or Android app."
        StorageReason.CheckTimedOut -> "This browser didn't finish checking its storage. Reload the page to try again."
    }

    /** `V2StorageUnavailable`: one text per case. [V2Storage.Available] has none and returns an empty string. */
    fun v2StorageUnavailable(storage: V2Storage): String = when (storage) {
        V2Storage.Available -> ""
        V2Storage.Checking -> CHECKING_STORAGE
        V2Storage.NoStore -> STORAGE_NO_STORE
        V2Storage.PlatformUnsupported -> STORAGE_IOS
        is V2Storage.Browser -> webStorage(storage.reason, storage.iosWebKit)
    }

    /** The reason shown beside a disabled mode option. */
    fun disabled(reason: ModeDisabled): String = when (reason) {
        ModeDisabled.Checking -> CHECKING_STORAGE
        is ModeDisabled.Storage -> v2StorageUnavailable(reason.storage)
        ModeDisabled.BuildCapped -> BUILD_CAPPED
    }

    /** The reason of a sheet created classic (the "<reason>" of "Created with the classic editor because <reason>"). */
    fun classic(reason: ClassicReason): String = when (reason) {
        ClassicReason.ModeOff -> "the new editor is off for this graph."
        ClassicReason.BuildCapped -> BUILD_CAPPED
        ClassicReason.ProjectNotNew -> "this project already holds classic sketches, so an older version of SteleKit can still open it."
        ClassicReason.TechnicalData -> HAS_TECHNICAL_DATA
        is ClassicReason.Storage -> v2StorageUnavailable(reason.storage)
    }

    private const val NPO_ROUTE =
        "To use the new editor in an existing project, choose All sketches (Upgrade) in Settings: sketches you add to that " +
            "project then use the new editor (after a one-time confirmation), and each classic sketch offers Upgrade."

    /** The `<reason>` of "Created with the classic editor because <reason>." (ux.md 14.1 reason table). */
    fun noticeReason(reason: ClassicNoticeReason, storage: V2Storage = V2Storage.NoStore): String = when (reason) {
        ClassicNoticeReason.Platform ->
            if (storage == V2Storage.NoStore) "this graph can't hold new-editor sketches" else "this device can't save new-editor sketches yet"
        ClassicNoticeReason.BuildCapped -> "this version of SteleKit can't use the new editor yet"
        ClassicNoticeReason.TechnicalData -> "this project has dimensions or zones, and the new editor can't hold those yet"
        ClassicNoticeReason.ModeOff -> "the new editor is off for this graph"
        ClassicNoticeReason.NpoClassicSheets ->
            "this project already has sketches from before the new editor, and New sketch projects only limits the new editor " +
                "to projects created from now on"
        ClassicNoticeReason.NpoEmptyProject ->
            "this project already existed, and New sketch projects only limits the new editor to projects created from now on"
        ClassicNoticeReason.NpoUnreadable -> "SteleKit couldn't confirm that every sketch in this project uses the new editor"
        ClassicNoticeReason.UserChose -> "you chose it"
    }

    /** The Details of the `ClassicEditor` toast; every New-sketch-projects-only reason ends with the route to the new editor. */
    fun classicDetails(reason: ClassicNoticeReason, storage: V2Storage = V2Storage.NoStore): String {
        val line = "Created with the classic editor because ${noticeReason(reason, storage)}."
        return if (reason.settingsCanChange) "$line $NPO_ROUTE" else line
    }
}
