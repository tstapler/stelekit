package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Command
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.library.LibraryFilter

/**
 * Palette navigation commands derived from the same [NavModel] as the sidebar and sheet (ADR-007), so a
 * destination added to the model gets a "Go to ..." command without a second list to keep in sync.
 */
object NavCommands {
    const val SHOW_ANNOTATED_ID = "nav.library.show-annotated"

    fun goToId(spec: NavDestinationSpec) = "nav.go-to.${spec.id}"

    /** Developer rows (Logs, Performance) stay out of the palette; they are only listed while active. */
    fun build(
        specs: List<NavDestinationSpec> = NavModel.allSpecs,
        navigate: (Screen) -> Unit,
        openGitSetup: () -> Unit,
    ): List<Command> {
        val goTo = specs.filter { it.id != NavModel.logs.id && it.id != NavModel.performance.id }.map { spec ->
            Command(
                id = goToId(spec),
                label = "${I18n.get(I18n.NAV_GO_TO)} ${I18n.get(spec.labelKey)}",
                action = {
                    when (val a = spec.action) {
                        is NavAction.Open -> navigate(a.screen)
                        NavAction.GitSetup -> openGitSetup()
                    }
                },
            )
        }
        val showAnnotated = Command(
            id = SHOW_ANNOTATED_ID,
            label = I18n.get(I18n.LIBRARY_CMD_SHOW_ANNOTATED),
            action = { navigate(Screen.Library(initialFilter = LibraryFilter.Annotated)) },
        )
        return goTo + showAnnotated
    }
}
