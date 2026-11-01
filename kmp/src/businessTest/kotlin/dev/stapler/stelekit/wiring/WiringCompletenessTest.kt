package dev.stapler.stelekit.wiring

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-002 layer 1: every implementation of a [WiringAudit.SEAMS] interface must be constructed
 * (`Name(`) in a composition-root file, or sit in the expiring `wiring-allowlist.txt`.
 *
 * Limits (stated): a text match, so a constructor inside dead code (a never-called function of a
 * root) still passes; the observable-effect catalog ([WiringCatalog], `PlantedDeadCodeConstructorTest`)
 * is the layer that catches that. Comments and string literals are stripped, so mentioning
 * `FooPlugin(` in a comment does not count as construction. Lives in `businessTest`, so it needs no
 * display and runs under `bazel test //kmp:business_tests` with sources bundled as classpath resources.
 */
class WiringCompletenessTest {
    private val today = LocalDate.now()

    private fun allowlist() = WiringInputs.dataFile(ALLOWLIST)

    private val goodRoot = mapOf("commonMain/dev/stapler/stelekit/ui/GraphContentFooSetup.kt" to "fun a() { FooPlugin() }")
    private val fooPluginSource = mapOf(
        "commonMain/dev/stapler/stelekit/FooPlugin.kt" to "class FooPlugin : AssetPipelinePlugin {\n}\n",
    )

    @Test
    fun wiringCompleteness_should_Pass_When_EverySeamImplementationIsConstructedInARoot() {
        val roots = WiringRoots.resolve(WiringInputs.productionSources).requireComplete()
        val violations = WiringAudit.evaluate(WiringInputs.productionSources, roots, allowlist(), today)
        assertTrue(violations.isEmpty(), "wiring violations:\n  " + violations.joinToString("\n  "))
    }

    @Test
    fun wiringCompleteness_should_FailNamingClassAndRootsSearched_When_ImplementationNeverConstructed() {
        val violations = WiringAudit.evaluate(fooPluginSource, mapOf("ui/GraphContentOtherSetup.kt" to "fun a() {}"), "", today)
        assertEquals(1, violations.size, violations.toString())
        assertTrue("FooPlugin" in violations[0], violations[0])
        assertTrue("ui/GraphContentOtherSetup.kt" in violations[0], "must list roots searched: ${violations[0]}")
    }

    @Test
    fun wiringCompleteness_should_NotCountACommentOrStringMention_When_RootOnlyMentionsTheClass() {
        val roots = mapOf("ui/GraphContentOtherSetup.kt" to "// FooPlugin()\nval s = \"FooPlugin()\"\n/* FooPlugin( */")
        val violations = WiringAudit.evaluate(fooPluginSource, roots, "", today)
        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun wiringCompleteness_should_FindTransitiveImplementations_And_SkipAbstractTypes() {
        val sources = mapOf(
            "a.kt" to "abstract class Base : CameraProvider\nclass Child : Base()\ninterface Sub : CameraProvider\n" +
                "class Multi(\n  val x: Int,\n) : Comparable<CameraProvider>,\n  WaybackMachineService {\n}\n",
        )
        assertEquals(listOf("Child", "Multi"), WiringAudit.findImplementations(sources).map { it.name })
    }

    @Test
    fun wiringCompleteness_should_FailRootNotFound_When_AnyRootAbsentFromClasspath() {
        val all = WiringInputs.productionSources
        for (path in WiringRoots.EXACT) {
            val without = all - path
            val e = assertFailsWith<IllegalStateException>("missing $path must fail") { WiringRoots.resolve(without).requireComplete() }
            assertTrue("root not found: $path; roots searched: [" in e.message.orEmpty(), e.message)
        }
        assertFailsWith<IllegalStateException> { WiringRoots.resolve(emptyMap()).requireComplete() }
    }

    @Test
    fun wiringCompleteness_should_FailWhenGraphContentSetupFilesAbsent() {
        val without = WiringInputs.productionSources.filterKeys { !Regex("""ui/GraphContent\w*Setup\.kt$""").containsMatchIn(it) }
        assertFailsWith<IllegalStateException> { WiringRoots.resolve(without).requireComplete() }
    }

    @Test
    fun wiringAllowlist_should_FailNamingEntry_When_AllowlistEntryOlderThan90Days() {
        val entry = "FooPlugin  temporary reason  2026-10-27  renewals=0"
        val onDay90 = WiringAudit.evaluate(fooPluginSource, goodRoot.mapValues { "" }, entry, LocalDate.parse("2027-01-25"))
        assertTrue(onDay90.none { "expired" in it }, "day 90 is still inside the window: $onDay90")
        val late = WiringAudit.evaluate(fooPluginSource, goodRoot.mapValues { "" }, entry, LocalDate.parse("2027-02-01"))
        assertTrue(late.any { "expired allowlist entry: FooPlugin" in it }, late.toString())
        val noRenewalsField = WiringAudit.evaluate(fooPluginSource, goodRoot.mapValues { "" }, "FooPlugin  temporary reason  2026-10-27", LocalDate.parse("2027-02-01"))
        assertTrue(noRenewalsField.any { "expired allowlist entry: FooPlugin" in it }, noRenewalsField.toString())
    }

    @Test
    fun wiringAllowlist_should_NeverExpire_When_ReasonClassOutOfScopeIosAndDateIsNever() {
        val sources = mapOf("iosMain/CoreMlImageLabeler.kt" to "class CoreMlImageLabeler : ImageLabeler\n")
        val ios = "CoreMlImageLabeler  out-of-scope-ios  never"
        assertEquals(emptyList(), WiringAudit.evaluate(sources, goodRoot, ios, LocalDate.parse("2030-01-01")))
        val dated = "CoreMlImageLabeler  out-of-scope-ios  2026-10-27"
        assertTrue(WiringAudit.evaluate(sources, goodRoot, dated, today).isNotEmpty(), "a dated ios entry is malformed")
        val otherNever = WiringAudit.evaluate(fooPluginSource, goodRoot.mapValues { "" }, "FooPlugin  awaiting-unit U8  never", today)
        assertTrue(otherNever.any { "only out-of-scope-ios" in it }, otherNever.toString())
    }

    @Test
    fun seamList_should_EqualGlossaryList_When_Parsed() {
        assertEquals(
            listOf("BackgroundSyncScheduler", "AssetPipelinePlugin", "ConsoleCommand", "MeasurementDeviceFactory", "WaybackMachineService", "CameraProvider"),
            WiringAudit.SEAMS,
        )
        assertTrue("Plugin" !in WiringAudit.SEAMS, "Plugin is not a seam (Task 5.5d deletes it)")
    }

    @Test
    fun wiringCatalog_should_Fail_When_EntryPointTestMissingOrDoesNotCallWiredAppFixture() {
        val catalog = "AssetPipelinePlugin  FooEntryPointTest  effect:foo-ran"
        assertTrue(WiringCatalog.check(catalog, emptyMap()).single().contains("does not exist"))
        val commentOnly = mapOf(
            "androidUnitTest/FooEntryPointTest.kt" to "// WiredAppFixture.android() GraphContentAssetSetup\nclass FooEntryPointTest\n",
        )
        val v = WiringCatalog.check(catalog, commentOnly)
        assertTrue(v.any { "never calls WiredAppFixture." in it }, v.toString())
    }

    @Test
    fun plantedOrphan_should_FailUnderBazelSandbox_When_RunViaBazelTestBusinessTests() {
        // Runs on whichever loader is active (disk under Gradle, classpath jars under Bazel): a
        // seam implementation planted next to the real sources, and constructed by no root, must fail.
        val roots = WiringRoots.resolve(WiringInputs.productionSources).requireComplete()
        val planted = WiringInputs.productionSources + ("commonMain/dev/stapler/stelekit/PlantedOrphanPlugin.kt" to
            "class PlantedOrphanPlugin : AssetPipelinePlugin {\n}\n")
        val v = WiringAudit.evaluate(planted, roots, allowlist(), today)
        assertTrue(v.any { "PlantedOrphanPlugin" in it }, "planted orphan was not caught (loader=${WiringInputs.mode}): $v")
        assertTrue(roots.size >= WiringRoots.EXACT.size + 1, "root set suspiciously small (loader=${WiringInputs.mode}): ${roots.keys}")
    }

    @Test
    fun businessTestData_should_BeVisibleOnClasspathUnderBazel_When_AllowlistCatalogEgressBaselineHygieneAndCredentialFilesLoaded() {
        // Grows as units add data files (egress-allowlist.txt: Story 6.3, console-credential-columns.txt: Story 3.x).
        for (name in listOf("wiring-allowlist.txt", "wiring-catalog.txt", "first-open-screens.txt", "test-hygiene-baseline.txt")) {
            assertTrue(WiringInputs.dataFile(name).isNotBlank(), "$name must be non-empty")
        }
        assertTrue(
            WiringInputs.dataFile("reachability-baseline.txt", "scripts/ci/reachability-baseline.txt").lines().count { it.isNotBlank() } > 0,
            "reachability baseline copy must be visible",
        )
        val e = assertFailsWith<IllegalStateException> { WiringInputs.dataFile("no-such-data-file.txt") }
        assertTrue("data file not found" in e.message.orEmpty())
    }

    @Test
    fun seededAllowlist_should_PassAtHeadRoots_And_FailNamingClass_When_AnyAwaitingUnitLineRemoved() {
        val roots = WiringRoots.resolve(WiringInputs.productionSources).requireComplete()
        val text = allowlist()
        assertEquals(emptyList(), WiringAudit.evaluate(WiringInputs.productionSources, roots, text, today))
        val awaiting = text.lines().filter { it.contains("awaiting-unit") }
        assertTrue(awaiting.isNotEmpty(), "U1 seeds awaiting-unit entries")
        for (line in awaiting) {
            val cls = line.trim().split(Regex("""\s{2,}"""))[0]
            val without = text.lines().filter { it != line }.joinToString("\n")
            val v = WiringAudit.evaluate(WiringInputs.productionSources, roots, without, today)
            assertTrue(v.any { it.startsWith(cls) }, "removing $cls must fail naming it: $v")
        }
    }

    @Test
    fun realCatalog_should_PassAgainstRealTestSources() {
        val v = WiringCatalog.check(WiringInputs.dataFile("wiring-catalog.txt"), WiringInputs.testSources)
        assertTrue(v.isEmpty(), v.toString())
    }

    private companion object {
        const val ALLOWLIST = "wiring-allowlist.txt"
    }
}
