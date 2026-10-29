// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleSessionConfirmTest {
    private class OnSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = true
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) = Unit
        override fun containsKey(key: String) = true
    }

    private val request = ConfirmRequest("Run this write?", "update t set a=1", listOf("1 row will change"), ConfirmTier.Standard, "Update 1 row")

    private class AskCommand(private val request: ConfirmRequest, val answers: MutableList<ConfirmAnswer>) : ConsoleCommand {
        override val name = "ask"
        override val summary = "asks"
        override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
            answers += context.confirmer.confirm(request)
            return Unit.right()
        }
    }

    private fun TestScope.session(command: ConsoleCommand) = ConsoleSession(
        DeveloperMode(OnSettings()), ConsoleRegistry(listOf(command)), batchMillis = 1, dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun confirm_should_SurfaceRequestAndResumeCommand_When_OwnerAnswers() = runTest {
        val answers = mutableListOf<ConfirmAnswer>()
        val session = session(AskCommand(request, answers))
        session.submit("ask")
        runCurrent()
        assertEquals(request, session.pendingConfirm.value)
        assertTrue(session.isRunning.value, "a command waiting on the owner still holds the session")
        session.answerConfirm(ConfirmAnswer.Proceed())
        advanceUntilIdle()
        assertEquals(listOf<ConfirmAnswer>(ConfirmAnswer.Proceed()), answers)
        assertNull(session.pendingConfirm.value)
        session.close()
    }

    @Test
    fun confirm_should_ClearAndAnswerNothing_When_CommandCancelledWhileModalOpen() = runTest {
        val answers = mutableListOf<ConfirmAnswer>()
        val session = session(AskCommand(request, answers))
        session.submit("ask")
        runCurrent()
        session.cancel()
        advanceUntilIdle()
        assertNull(session.pendingConfirm.value)
        assertTrue(answers.isEmpty(), "a cancelled command must not proceed")
        session.answerConfirm(ConfirmAnswer.Proceed())
        advanceUntilIdle()
        assertTrue(answers.isEmpty(), "an answer with nothing pending is ignored")
        session.close()
    }

    @Test
    fun noConfirmer_should_Cancel_When_ContextHasNoUi() = runTest {
        assertEquals(ConfirmAnswer.Cancel, ConsoleContext(ConsoleRegistry(emptyList())).confirmer.confirm(request))
    }
}
