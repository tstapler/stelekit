package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.text.TextWrap
import kotlinx.coroutines.runBlocking
import stelekit.kmp.generated.resources.Res
import androidx.compose.ui.text.platform.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Task 2.1.2b: the Compose adapter measures with the bundled fonts and reports load state through Compose state. */
class ComposeTextMetricsProviderTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
    private val hand = TextStyle(fontFamily = 1, fontSize = 20.0)
    private val normal = TextStyle(fontFamily = 2, fontSize = 20.0)
    private val code = TextStyle(fontFamily = 3, fontSize = 20.0)

    private fun loadedFamilies(): SketchFontFamilies = runBlocking {
        suspend fun family(file: String) = FontFamily(Font(file, Res.readBytes("font/$file")))
        SketchFontFamilies(family("sketch_hand_drawn.ttf"), family("sketch_normal.otf"), family("sketch_code.otf"))
    }

    @Test
    fun fontStatus_should_FollowTheFontState_When_LoadingThenLoaded() {
        val state = mutableStateOf<SketchFontState>(SketchFontState.Loading)
        val provider = ComposeTextMetricsProvider(measurer, state)
        assertEquals(FontStatus.Loading, provider.fontStatus)
        state.value = SketchFontState.Loaded(loadedFamilies())
        assertEquals(FontStatus.Loaded, provider.fontStatus)
        state.value = SketchFontState.Failed
        assertEquals(FontStatus.Failed, provider.fontStatus)
    }

    @Test
    fun advance_should_UseTheBundledFamilyOfTheStyle_When_FontsAreLoaded() {
        val provider = ComposeTextMetricsProvider(measurer, mutableStateOf(SketchFontState.Loaded(loadedFamilies())))
        val text = "Authentication Service"
        val widths = listOf(hand, normal, code).map { provider.advance(text, it) }
        assertTrue(widths.all { it > 0.0 }, widths.toString())
        assertEquals(3, widths.toSet().size, "three distinct families must measure differently: $widths")
        assertEquals(widths, listOf(hand, normal, code).map { provider.advance(text, it) }, "measuring twice is stable")
        assertEquals(0.6 * 20.0 * 12, provider.advance("a".repeat(12), code), 1.0, "Source Code Pro advances 0.6 em")
    }

    @Test
    fun layout_should_WrapAtTheSameBreaks_When_MeasuredTwice() {
        val provider = ComposeTextMetricsProvider(measurer, mutableStateOf(SketchFontState.Loaded(loadedFamilies())))
        val width = provider.advance("Authentication", normal) + 1.0
        val first = TextWrap.layout("Authentication Service", width, normal, provider)
        val second = TextWrap.layout("Authentication Service", width, normal, provider)
        assertEquals(first, second)
        assertEquals(listOf("Authentication", "Service"), first.lines)
        assertFalse(first.usedFallback)
    }

    @Test
    fun hasGlyph_should_BeFalseForCjk_When_TheBundledFontsLackIt() {
        val provider = ComposeTextMetricsProvider(measurer, mutableStateOf(SketchFontState.Loaded(loadedFamilies())))
        assertTrue(provider.hasGlyph('A'.code, normal))
        assertTrue(provider.hasGlyph('é'.code, hand))
        assertFalse(provider.hasGlyph('デ'.code, normal))
        val layout = TextWrap.layout("データ", null, normal, provider)
        assertTrue(layout.usedFallback)
        assertNotEquals(0.0, layout.width)
    }
}
