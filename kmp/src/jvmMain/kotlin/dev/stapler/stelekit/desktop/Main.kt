// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.stapler.stelekit.capture.CaptureController
import dev.stapler.stelekit.capture.CapturePopupWindow
import dev.stapler.stelekit.capture.CaptureSocketClient
import dev.stapler.stelekit.capture.CaptureSocketListener
import dev.stapler.stelekit.capture.GlobalHotkeyListener
import dev.stapler.stelekit.capture.JKeymasterHotkeyListener
import dev.stapler.stelekit.capture.PendingCapturePoller
import dev.stapler.stelekit.capture.PendingCaptureWriter
import dev.stapler.stelekit.domain.UrlFetcherJvm
import dev.stapler.stelekit.service.JvmMediaAttachmentService
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.ui.StelekitAppCaptureDeps
import dev.stapler.stelekit.ui.StelekitAppCoreServices
import dev.stapler.stelekit.ui.StelekitAppDeps
import dev.stapler.stelekit.ui.StelekitAppLifecycleHooks
import dev.stapler.stelekit.ui.StelekitAppPlatformIntegrations
import dev.stapler.stelekit.ui.theme.setSystemDarkTheme
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.error.JvmErrorTracker
import dev.stapler.stelekit.performance.BuildInfo
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.OtelProvider
import dev.stapler.stelekit.performance.OtelExporterConfig
import dev.stapler.stelekit.performance.OtelLogSink
import dev.stapler.stelekit.performance.OtelSpanRecorder
import io.opentelemetry.api.trace.Tracer
import java.awt.KeyboardFocusManager
import javax.swing.UIManager

fun main(args: Array<String>) {
    // Headless CLI capture mode: try the socket fast path first (delivers immediately if
    // SteleKit is already running), falling back to the pending-capture queue — reusing the
    // same captureId so a lost ack can't double-record — and exit before any Compose/OTel/
    // logging setup runs, so `stelekit --capture-text "..."` stays fast and side-effect-free
    // (no window, no log file, no OpenTelemetry SDK spun up).
    parseCaptureArgs(args)?.let { text ->
        val result = CaptureSocketClient.trySend(text)
        if (!result.delivered) {
            PendingCaptureWriter.write(text, result.captureId)
        }
        println("Captured.")
        return
    }

    // Initialize file logging before anything else
    val fileLogSink = dev.stapler.stelekit.logging.FileLogSink()
    dev.stapler.stelekit.logging.LogManager.addSink(fileLogSink)

    // Desktop builds are always developer builds
    DebugBuildConfig.isDebugBuild = true
    BuildInfo.commitHash = System.getProperty("app.commit", "unknown")
    BuildInfo.appVersion = System.getProperty("app.version", "dev")

    // Initialize OpenTelemetry SDK early so instrumented code can emit spans
    OtelProvider.initialize(OtelExporterConfig(enableStdout = false, enableRingBuffer = true))

    // Enrich log lines with active trace/span IDs for log-trace correlation
    dev.stapler.stelekit.logging.LogManager.addSink(OtelLogSink(fileLogSink))

    // JVM shutdown hook — ensures logs are flushed and OTel is shut down on exit
    Runtime.getRuntime().addShutdownHook(Thread {
        dev.stapler.stelekit.logging.LogManager.flush()
        OtelProvider.shutdown()
    })

    // Initialize error tracking early
    val errorTracker = JvmErrorTracker()
    errorTracker.recordBreadcrumb("Application starting", "SYSTEM")

    val logger = Logger("DesktopMain")
    logger.info("Log file: ${dev.stapler.stelekit.logging.FileLogSink.currentLogPath()}")
    
    // AWT's default KeyboardFocusManager treats Tab/Shift+Tab as forward/backward focus
    // traversal keys and consumes them before they ever reach a component's key listeners.
    // Compose owns all Tab/Shift+Tab handling itself (BlockEditor's onPreviewKeyEvent), so
    // clearing the default traversal keystrokes lets both reach Compose uninterrupted. This
    // is JVM-wide, so the standalone JFileChooser dialogs (PlatformFileSystem.kt,
    // DesktopFilePicker.kt) explicitly restore their own local Tab/Shift+Tab traversal keys
    // via JFileChooser.restoreDefaultTabTraversal() — otherwise they'd inherit this empty
    // default too and lose Tab navigation between their fields/list/buttons.
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .setDefaultFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .setDefaultFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())

    application {
        try {
            val defaults = UIManager.getDefaults()
            val isDark = defaults.keys.toList().any { key ->
                key.toString().contains("dark", ignoreCase = true)
            }
            setSystemDarkTheme(isDark)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to detect system theme", e)
            setSystemDarkTheme(false)
        }

        val windowState = rememberWindowState(
            width = 1200.dp,
            height = 800.dp
        )

        val fileSystem = PlatformFileSystem()
        // Set only by `./gradlew run` (see kmp/build.gradle.kts) so dev/test launches use an
        // isolated scratch graph instead of the real default graph path — running the dev build
        // against real notes let multiple JVM instances (a real running install + repeated test
        // launches) contend for the same SQLite file, and repeated dev sessions silently bloated
        // its WAL to several GB. Packaged/installed builds never set this property.
        val graphPath = System.getProperty("stelekit.devGraphPath") ?: fileSystem.getDefaultGraphPath()
        val spanRecorder = remember {
            OtelSpanRecorder(OtelProvider.getTracer("compose.navigation") as Tracer)
        }

        // Desktop quick-capture: one CaptureController/hotkey listener for the whole process
        // lifetime, registered once and unregistered on window close (see onCloseRequest below).
        val captureController = remember(fileSystem) { CaptureController(fileSystem) }
        val hotkeyListener = remember { JKeymasterHotkeyListener() }
        LaunchedEffect(Unit) { captureController.start(hotkeyListener) }

        // Cold-start drain of any pending-capture files left on disk (headless CLI captures,
        // or a capture taken before the graph finished loading) — replayed through the same
        // CaptureWriter chain the live popup uses, every 5s and once immediately on start.
        val poller = remember(fileSystem) { PendingCapturePoller(fileSystem) }
        LaunchedEffect(Unit) { poller.start() }

        // Unix-domain-socket fast path: a CLI capture delivered while SteleKit is already
        // running skips the poller's up-to-5s latency entirely (see CaptureSocketClient).
        val socketListener = remember(fileSystem) { CaptureSocketListener(fileSystem) }
        LaunchedEffect(Unit) { socketListener.start() }

        logger.info("Starting Desktop Application with graph: $graphPath")
        errorTracker.recordBreadcrumb("Graph path resolved: $graphPath", "SYSTEM")

        Window(
            onCloseRequest = {
                logger.info("Application shutting down")
                captureController.stop(hotkeyListener)
                socketListener.stop()
                poller.stop()
                dev.stapler.stelekit.logging.LogManager.flush()
                // Closing the main window exits the whole JVM anyway, so these stop() calls are
                // for orderly shutdown (flushing logs, closing the socket file) rather than
                // strictly required — but if background residency (a Tray icon) is ever added
                // in a later phase, capture would keep working after window close. This is an
                // accepted v1 scope cut, not a bug: see
                // project_plans/desktop-quick-capture/decisions/ADR-002-v1-scope-cut-in-process-popup-only.md.
                exitApplication()
            },
            state = windowState,
            title = "SteleKit",
            icon = painterResource("icons/icon.png")
        ) {
            var otelStdoutEnabled by remember { mutableStateOf(false) }

            MenuBar {
                Menu("Developer") {
                    CheckboxItem(
                        text = "OTel Stdout",
                        checked = otelStdoutEnabled,
                        onCheckedChange = { enabled ->
                            otelStdoutEnabled = enabled
                            OtelProvider.shutdown()
                            OtelProvider.initialize(
                                OtelExporterConfig(enableStdout = enabled, enableRingBuffer = true)
                            )
                            logger.info("OTel stdout ${if (enabled) "enabled" else "disabled"}")
                        }
                    )
                }
            }

            val attachmentService = remember { JvmMediaAttachmentService() }
            val gitRepository = remember { JvmGitRepository() }
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                deps = StelekitAppDeps(
                    coreServices = StelekitAppCoreServices(
                        urlFetcher = UrlFetcherJvm(),
                        spanRecorder = spanRecorder,
                    ),
                    platformIntegrations = StelekitAppPlatformIntegrations(
                        cryptoEngine = dev.stapler.stelekit.vault.JvmCryptoEngine(),
                        attachmentService = attachmentService,
                        gitRepository = gitRepository,
                    ),
                    lifecycleHooks = StelekitAppLifecycleHooks(
                        onGraphManagerReady = { gm ->
                            captureController.attachGraphManager(gm)
                            poller.attachGraphManager(gm)
                            socketListener.attachGraphManager(gm)
                        },
                        onNotificationManagerReady = { nm -> captureController.attachNotificationManager(nm) },
                    ),
                    captureDeps = StelekitAppCaptureDeps(
                        hotkeyComboLabel = GlobalHotkeyListener.DEFAULT_COMBO_LABEL,
                        hotkeyRegistrationFailure = hotkeyListener.registrationFailure,
                    ),
                ),
            )
        }

        CapturePopupWindow(captureController)
    }
}
