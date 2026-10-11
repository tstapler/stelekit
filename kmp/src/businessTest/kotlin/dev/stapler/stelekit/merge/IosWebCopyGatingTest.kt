package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.copy.CapabilityDestinationProbe
import dev.stapler.stelekit.ui.screens.copy.CopyFlowGateway
import dev.stapler.stelekit.ui.screens.copy.CopyPagesState
import dev.stapler.stelekit.ui.screens.copy.CopyPagesViewModel
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS/Web capability fakes (with Spike 0.1.5 flags simulated as verified, so the flow works the day
 * they flip): only Pull is offered, push destinations are disabled, unreadable sources are disabled
 * with their reason, and a disabled attempt creates no staging directory and no queue entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosWebCopyGatingTest {
    private fun graph(id: String, path: String = "/g/$id", encrypted: Boolean = false) =
        GraphInfo(id = GraphId(id), path = path, displayName = id.uppercase(), addedAt = 0, isParanoidMode = encrypted)

    private val writer = TargetWriterCapabilities(platformSupportsOffGraphWrite = false)

    private fun reader(platform: SourcePlatform, verified: Boolean) = SourceReadCapabilities(
        platform = platform,
        iosVerified = verified,
        webVerified = verified,
        hasGrant = { it.graphId.value != "revoked" },
        canRegrant = true,
        folderExists = { it != "/g/gone" },
        isReadable = { it != "/g/io" },
    )

    private class CountingGateway : CopyFlowGateway {
        var plans = 0
        override val progress: StateFlow<MergeProgress> = MutableStateFlow(MergeProgress())
        override suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan> { plans++; error("must not plan") }
        override suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult> = error("must not apply")
        override fun cancel() = Unit
    }

    private class EmptySource : PageSource {
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int) = emptyList<Page>().right()
        override suspend fun countPages(filter: SelectionFilter, search: String?) = 0L.right()
        override suspend fun readPages(uuids: List<PageUuid>) = emptyList<SourcePage>().right()
    }

    private val graphs = listOf(
        graph("a"), graph("ok"), graph("vault", encrypted = true), graph("revoked"), graph("gone"), graph("io"),
    )

    private fun TestScope.vm(direction: CopyDirection, platform: SourcePlatform, verified: Boolean, gateway: CountingGateway) =
        CopyPagesViewModel(
            source = EmptySource(),
            activeGraphId = GraphId("a"),
            graphRegistry = MutableStateFlow(GraphRegistry(activeGraphId = GraphId("a"), graphs = graphs)),
            gateway = gateway,
            probe = CapabilityDestinationProbe(writer, reader(platform, verified)),
            direction = direction,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun `only Pull is offered on iOS and Web and only Push on Android and Desktop`() {
        assertEquals(listOf(CopyDirection.Pull), offeredDirections(SourcePlatform.Ios))
        assertEquals(listOf(CopyDirection.Pull), offeredDirections(SourcePlatform.Web))
        assertEquals(listOf(CopyDirection.Push), offeredDirections(SourcePlatform.Android))
        assertEquals(listOf(CopyDirection.Push), offeredDirections(SourcePlatform.Desktop))
    }

    @Test
    fun `push destinations are disabled with PlatformUnsupported and a link to the pull direction`() = runTest {
        val gateway = CountingGateway()
        val vm = vm(CopyDirection.Push, SourcePlatform.Ios, verified = true, gateway)
        advanceUntilIdle()
        val others = vm.state.value.destinations.filterNot { it.isCurrentGraph || it.graphId.value == "vault" }
        assertEquals(4, others.size)
        for (row in others) {
            val status = assertIs<DestinationStatus.Disabled>(row.status, row.name)
            assertEquals(DisabledKind.PlatformUnsupported, status.kind)
            assertEquals(DestinationActionKind.SwitchToPull, status.action?.kind)
            assertTrue(status.text.startsWith("Can't copy into ${row.name} from here on this device. Open ${row.name}"))
            vm.chooseDestination(row.graphId)
        }
        assertNull(vm.state.value.destinationId)
        vm.close()
    }

    @Test
    fun `pull sources are enabled only when readable, each unreadable one with its reason`() = runTest {
        val gateway = CountingGateway()
        val vm = vm(CopyDirection.Pull, SourcePlatform.Web, verified = true, gateway)
        advanceUntilIdle()
        val byId = vm.state.value.destinations.associateBy { it.graphId.value }
        fun kind(id: String) = (byId.getValue(id).status as DestinationStatus.Disabled).kind

        assertEquals(DestinationStatus.Available, byId.getValue("ok").status)
        assertEquals(DisabledKind.Encrypted, kind("vault"))
        assertEquals(DisabledKind.NoGrant, kind("revoked"))
        assertEquals(CopyPagesState.RESELECT_FOLDER, (byId.getValue("revoked").status as DestinationStatus.Disabled).action?.label)
        assertEquals(DisabledKind.FolderMissing, kind("gone"))
        assertEquals(DisabledKind.Unreadable, kind("io"))
        vm.close()
    }

    @Test
    fun `with the spike flags unverified every pull source is PlatformUnsupported and none is available`() = runTest {
        val vm = vm(CopyDirection.Pull, SourcePlatform.Ios, verified = false, CountingGateway())
        advanceUntilIdle()
        val kinds = vm.state.value.destinations.filterNot { it.isCurrentGraph }
            .map { (it.status as DestinationStatus.Disabled).kind }.toSet()
        assertEquals(setOf(DisabledKind.PlatformUnsupported, DisabledKind.Encrypted), kinds)
        assertTrue(vm.state.value.noSourceAvailable)
        vm.close()
    }

    @Test
    fun `a disabled attempt never reaches the merge engine, so nothing is staged or queued`() = runTest {
        val gateway = CountingGateway()
        val vm = vm(CopyDirection.Push, SourcePlatform.Ios, verified = true, gateway)
        advanceUntilIdle()
        vm.toggleRow(PageUuid("00000000-0000-0000-0000-000000000001"))
        vm.chooseDestination(GraphId("ok"))
        vm.review()
        advanceUntilIdle()

        assertEquals(0, gateway.plans)
        assertNull(vm.state.value.destinationId)
        vm.close()
    }
}
