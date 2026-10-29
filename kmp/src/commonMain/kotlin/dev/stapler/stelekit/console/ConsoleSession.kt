// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.security.Redactor
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One console conversation: runs lines through the [registry], keeps [scrollback], and enforces the
 * developer-mode gate on every call to [run] (a restored `Screen.Console` route cannot bypass it).
 *
 * Scope ownership: the session owns its [CoroutineScope] (never a caller's `rememberCoroutineScope`)
 * and the scope has a [CoroutineExceptionHandler], because an uncaught `Throwable` such as
 * `OutOfMemoryError` kills the process on Android. One command runs at a time. Output is redacted
 * with [Redactor] before it reaches scrollback, and batched so a chatty command does not recompose
 * the screen per line.
 *
 * Turning developer mode off cancels the running command and clears scrollback and history.
 */
class ConsoleSession(
    private val developerMode: DeveloperMode,
    private val registry: ConsoleRegistry,
    private val exactSecrets: () -> Collection<String> = { emptyList() },
    private val batchMillis: Long = DEFAULT_BATCH_MS,
    dispatcher: CoroutineDispatcher = PlatformDispatcher.Default,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t -> emit(epoch, ScrollbackKind.ERROR, "Internal error: ${describe(t)}") },
    )
    /** One line per run naming the verb only, never the arguments: a statement can carry data the owner does not want in logs. */
    private val audit = Logger("ConsoleAudit")
    private val pendingAnswer = MutableStateFlow<CompletableDeferred<ConfirmAnswer>?>(null)
    private val confirmState = MutableStateFlow<ConfirmRequest?>(null)
    private val context = ConsoleContext(registry, confirmer = ConsoleConfirmer { request -> askOwner(request) })
    private val pending = Channel<Item>(Channel.UNLIMITED)
    private val running = MutableStateFlow(false)
    private val historyState = MutableStateFlow<List<String>>(emptyList())

    @Volatile private var epoch = 0
    @Volatile private var job: Job? = null

    val scrollback = ScrollbackBuffer()
    val isRunning: StateFlow<Boolean> = running.asStateFlow()
    val history: StateFlow<List<String>> = historyState.asStateFlow()
    val commands: List<ConsoleCommand> get() = registry.all

    /** The write confirm the screen must show, or null. A command waiting on it holds [isRunning] true. */
    val pendingConfirm: StateFlow<ConfirmRequest?> = confirmState.asStateFlow()

    /** The owner's answer to [pendingConfirm]; ignored when nothing is pending. */
    fun answerConfirm(answer: ConfirmAnswer) {
        pendingAnswer.value?.complete(answer)
    }

    private sealed interface Item {
        class Line(val epoch: Int, val kind: ScrollbackKind, val text: String, val table: ResultTable? = null) : Item
        class Barrier(val done: CompletableDeferred<Unit>) : Item
    }

    init {
        scope.launch { flushLoop() }
        scope.launch { developerMode.enabled.collect { on -> if (!on) reset() } }
    }

    /** Runs [line] to completion on the caller's coroutine. Returns Denied without executing when developer mode is off. */
    suspend fun run(line: String): Either<ConsoleError, Unit> {
        if (!developerMode.isEnabled()) return ConsoleError.Denied("developer mode off").left()
        if (!running.compareAndSet(false, true)) return ConsoleError.Failed("A command is already running. Cancel it first.").left()
        val runEpoch = epoch
        // The command runs in the session's scope so cancel()/reset() stop it without cancelling the caller.
        val command = scope.async { execute(line, runEpoch) }
        job = command
        try {
            return command.await()
        } catch (e: CancellationException) {
            command.cancel()
            currentCoroutineContext().ensureActive()
            return ConsoleError.Cancelled.left()
        } finally {
            running.value = false
            withContext(NonCancellable) { flush() }
        }
    }

    /** Fire-and-forget [run] for UI callers; progress shows through [scrollback] and [isRunning]. */
    fun submit(line: String) {
        scope.launch { run(line) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Stops the session for good; the screen calls this when it leaves composition. */
    fun close() {
        scope.cancel()
    }

    /** The scrollback as text for the export file. Already redacted. */
    fun exportText(): String = scrollback.lines.value.joinToString("\n") { line ->
        if (line.table != null) return@joinToString line.table.toText()
        when (line.kind) {
            ScrollbackKind.INPUT -> "> ${line.text}"
            ScrollbackKind.ERROR -> "ERR ${line.text}"
            ScrollbackKind.OUTPUT -> line.text
        }
    }

    fun clearScrollback() = scrollback.clear()

    /** `console-<yyyyMMdd-HHmmss>` in local time; the share provider adds `.txt`. */
    fun exportFileBaseName(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault(), prefix: String = "console"): String {
        val t = now.toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$prefix-${t.year}${two(t.monthNumber)}${two(t.dayOfMonth)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
    }

    private suspend fun execute(line: String, runEpoch: Int): Either<ConsoleError, Unit> {
        val tokens = CommandLineTokenizer.tokenize(line).fold({ return fail(runEpoch, it) }, { it })
        if (tokens.isEmpty()) return Unit.right()
        emit(runEpoch, ScrollbackKind.INPUT, line)
        historyState.value = (historyState.value + safe(line)).takeLast(MAX_HISTORY)
        val command = registry.find(tokens.first()) ?: return fail(runEpoch, ConsoleError.UnknownCommand(tokens.first()))
        command.disabledReason?.let { return fail(runEpoch, ConsoleError.Unsupported(it)) }
        audit.info("console run verb=${command.name}")
        val out = object : ConsoleOutput {
            override fun info(text: String) = emit(runEpoch, ScrollbackKind.OUTPUT, text)
            override fun error(text: String) = emit(runEpoch, ScrollbackKind.ERROR, text)
            override fun table(table: ResultTable) {
                pending.trySend(Item.Line(runEpoch, ScrollbackKind.OUTPUT, "", table.mapCells(::safe).let { it.copy(explainSql = it.explainSql?.let(::safe)) }))
            }
        }
        return try {
            command.run(tokens.drop(1), context, out).onLeft { fail(runEpoch, it) }
        } catch (e: CancellationException) {
            emit(runEpoch, ScrollbackKind.ERROR, ConsoleError.Cancelled.message)
            throw e
        } catch (t: Throwable) {
            fail(runEpoch, ConsoleError.Failed("Command failed: ${describe(t)}"))
        }
    }

    private suspend fun askOwner(request: ConfirmRequest): ConfirmAnswer {
        val answer = CompletableDeferred<ConfirmAnswer>()
        pendingAnswer.value = answer
        confirmState.value = request
        try {
            return answer.await()
        } finally {
            confirmState.value = null
            pendingAnswer.value = null
        }
    }

    private fun fail(runEpoch: Int, error: ConsoleError): Either<ConsoleError, Nothing> {
        emit(runEpoch, ScrollbackKind.ERROR, error.message)
        return error.left()
    }

    private fun emit(runEpoch: Int, kind: ScrollbackKind, text: String) {
        pending.trySend(Item.Line(runEpoch, kind, safe(text)))
    }

    private fun safe(text: String): String {
        val secrets = try { exactSecrets() } catch (_: Exception) { emptyList() }
        return Redactor.redact(text, secrets)
    }

    private fun describe(t: Throwable) = "${t::class.simpleName}: ${t.message.orEmpty()}"

    private suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        pending.trySend(Item.Barrier(done))
        done.await()
    }

    private fun reset() {
        epoch++
        job?.cancel()
        scrollback.clear()
        historyState.value = emptyList()
    }

    /** Collects lines for [batchMillis] after the first one arrives, then appends them as one batch. */
    private suspend fun flushLoop() {
        val batch = mutableListOf<Item.Line>()
        fun publish() {
            val live = batch.filter { it.epoch == epoch }
            scrollback.appendEntries(live.map { ScrollbackEntry(it.kind, it.text, it.table) })
            batch.clear()
        }
        for (first in pending) {
            if (first is Item.Barrier) {
                first.done.complete(Unit)
                continue
            }
            batch += first as Item.Line
            delay(batchMillis)
            while (true) {
                val next = pending.tryReceive().getOrNull() ?: break
                if (next is Item.Barrier) {
                    publish()
                    next.done.complete(Unit)
                } else {
                    batch += next as Item.Line
                }
            }
            publish()
        }
    }

    companion object {
        const val DEFAULT_BATCH_MS = 60L
        const val MAX_HISTORY = 200
    }
}
