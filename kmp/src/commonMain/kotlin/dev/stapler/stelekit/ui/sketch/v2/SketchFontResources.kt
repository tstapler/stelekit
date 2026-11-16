// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.Font
import stelekit.kmp.generated.resources.Res
import stelekit.kmp.generated.resources.sketch_code
import stelekit.kmp.generated.resources.sketch_hand_drawn
import stelekit.kmp.generated.resources.sketch_normal

// Gradle-only: this file uses the generated `Res` of Compose Multiplatform resources, which Bazel does not
// generate. The Bazel build excludes it from `kt_srcs` and compiles `bazel_stubs/SketchFontResources.kt` instead.

private val logger = Logger("SketchFonts")

private val FONT_FILES = listOf("font/sketch_hand_drawn.ttf", "font/sketch_normal.otf", "font/sketch_code.otf")

/**
 * `Loading` until the three bundled font files are readable and `Font(resource)` has replaced its placeholder
 * (the skiko targets, wasm included, return one shared placeholder until the async load finishes, so three
 * distinct instances mean three loaded fonts); `Failed` if a file cannot be read.
 */
@Composable
fun rememberSketchFontState(): State<SketchFontState> {
    val hand = Font(Res.font.sketch_hand_drawn)
    val normal = Font(Res.font.sketch_normal)
    val code = Font(Res.font.sketch_code)
    val fonts = rememberUpdatedState(Triple(hand, normal, code))
    val readable = produceState<Boolean?>(null) {
        value = try {
            FONT_FILES.forEach { Res.readBytes(it) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("bundled sketch fonts did not load: ${e::class.simpleName}")
            false
        }
    }
    return remember {
        derivedStateOf<SketchFontState> {
            when (readable.value) {
                null -> SketchFontState.Loading
                false -> SketchFontState.Failed
                true -> {
                    val (h, n, c) = fonts.value
                    if (h !== n && n !== c && h !== c) {
                        SketchFontState.Loaded(SketchFontFamilies(FontFamily(h), FontFamily(n), FontFamily(c)))
                    } else {
                        SketchFontState.Loading
                    }
                }
            }
        }
    }
}
