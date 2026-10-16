package dev.stapler.stelekit.platform.sensor
import kotlin.test.Test
class CpProbeTest { @Test fun probe() { val cp = System.getProperty("java.class.path"); java.io.File("/tmp/cpprobe.txt").writeText("${cp.split(':').size}\n${cp.take(300)}\n${ProcessHandle.current().info().command().orElse("?")}") } }
