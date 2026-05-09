// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.stapler.stelekit.domain.UrlFetcherJvm
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.ui.theme.setSystemDarkTheme
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.error.JvmErrorTracker
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.OtelProvider
import dev.stapler.stelekit.performance.OtelExporterConfig
import dev.stapler.stelekit.performance.OtelLogSink
import dev.stapler.stelekit.performance.OtelSpanRecorder
import io.opentelemetry.api.trace.Tracer
import javax.swing.UIManager

fun main() {
    // Initialize file logging before anything else
    val fileLogSink = dev.stapler.stelekit.logging.FileLogSink()
    dev.stapler.stelekit.logging.LogManager.addSink(fileLogSink)

    // Desktop builds are always developer builds
    DebugBuildConfig.isDebugBuild = true

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
        val graphPath = fileSystem.getDefaultGraphPath()
        val spanRecorder = remember {
            OtelSpanRecorder(OtelProvider.getTracer("compose.navigation") as Tracer)
        }

        logger.info("Starting Desktop Application with graph: $graphPath")
        errorTracker.recordBreadcrumb("Graph path resolved: $graphPath", "SYSTEM")

        Window(
            onCloseRequest = {
                logger.info("Application shutting down")
                dev.stapler.stelekit.logging.LogManager.flush()
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

            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                urlFetcher = UrlFetcherJvm(),
                spanRecorder = spanRecorder,
                cryptoEngine = dev.stapler.stelekit.vault.JvmCryptoEngine(),
            )
        }
    }
}
