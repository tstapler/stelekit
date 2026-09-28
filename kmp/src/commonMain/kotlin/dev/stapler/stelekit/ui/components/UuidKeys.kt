package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid

/**
 * Extracts the underlying String for use as a `LazyColumn`/`LazyRow`/`LazyGrid` item key.
 *
 * Android's `SaveableStateHolder` only accepts Bundle-safe types as lazy list keys.
 * A boxed `PageUuid` is not Bundle-safe even though it wraps a `String`; this extension
 * makes the correct extraction explicit and findable by call-site readers.
 *
 * Use with the project wrappers in `TypedLazyItems.kt`:
 * ```kotlin
 * typedItems(pages, key = { it.uuid.asLazyKey() }) { page -> ... }
 * ```
 */
fun PageUuid.asLazyKey(): String = value

/**
 * Extracts the underlying String for use as a `LazyColumn`/`LazyRow`/`LazyGrid` item key.
 * See [PageUuid.asLazyKey] for the full explanation.
 */
fun BlockUuid.asLazyKey(): String = value
