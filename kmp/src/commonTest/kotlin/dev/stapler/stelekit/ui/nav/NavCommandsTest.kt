package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.library.LibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavCommandsTest {
    private fun build(navigated: MutableList<Screen> = mutableListOf(), git: () -> Unit = {}) =
        NavCommands.build(navigate = { navigated += it }, openGitSetup = git)

    @Test
    fun goToLibrary_navigatesToLibraryWithNoFilter() {
        val nav = mutableListOf<Screen>()
        val cmd = build(nav).first { it.label == "Go to Library" }
        cmd.action()
        assertEquals(listOf<Screen>(Screen.Library()), nav)
    }

    @Test
    fun showAnnotatedPhotos_opensLibraryWithTheAnnotatedFilter() {
        val nav = mutableListOf<Screen>()
        val cmd = build(nav).first { it.id == NavCommands.SHOW_ANNOTATED_ID }
        assertEquals(I18n.get(I18n.LIBRARY_CMD_SHOW_ANNOTATED), cmd.label)
        cmd.action()
        assertEquals(listOf<Screen>(Screen.Library(initialFilter = LibraryFilter.Annotated)), nav)
    }

    @Test
    fun everySpecInTheModelGetsAGoToCommand_exceptDeveloperRows() {
        val ids = build().map { it.id }.toSet()
        for (spec in NavModel.allSpecs) {
            if (spec.id == "logs" || spec.id == "performance") continue
            assertTrue(NavCommands.goToId(spec) in ids, "missing command for ${spec.id}")
        }
    }

    @Test
    fun aSpecAddedLater_getsACommandWithoutAnotherList() {
        val extra = NavDestinationSpec("extra", NavAction.Open(Screen.Console), I18n.NAV_LOGS, I18n.NAV_LOGS_DESC, NavIconKey.LOGS)
        val cmds = NavCommands.build(NavModel.allSpecs + extra, navigate = {}, openGitSetup = {})
        assertNotNull(cmds.firstOrNull { it.id == NavCommands.goToId(extra) })
    }

    @Test
    fun gitSetupCommandOpensTheDialogRatherThanAScreen() {
        var opened = false
        build(git = { opened = true }).first { it.id == NavCommands.goToId(NavModel.gitSetup) }.action()
        assertTrue(opened)
    }
}
