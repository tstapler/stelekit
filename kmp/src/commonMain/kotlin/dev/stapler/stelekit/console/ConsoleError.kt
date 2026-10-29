// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/** Why a console command did not run to completion. Rendered as an ERR block in scrollback. */
sealed interface ConsoleError {
    val message: String

    /** Refused before executing anything (developer mode off, build flag off). */
    data class Denied(val reason: String) : ConsoleError {
        override val message: String get() = "Denied: $reason"
    }

    data class UnknownCommand(val name: String) : ConsoleError {
        override val message: String get() = "Unknown command '$name'. Type 'help' for the list."
    }

    /** Wrong arguments or an unparsable line; [detail] says how. */
    data class Usage(val detail: String) : ConsoleError {
        override val message: String get() = detail
    }

    data class Failed(val detail: String) : ConsoleError {
        override val message: String get() = detail
    }

    data object Cancelled : ConsoleError {
        override val message: String get() = "Cancelled."
    }
}
