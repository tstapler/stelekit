// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Story 3.3 "Android Auto Backup exclusions": new on-device state (console backups, recovery
 * directories, the settings stores holding consent and run markers) must not leave the device.
 * `allowBackup` is false today; the rules files make that hold even if someone later turns it on.
 */
class BackupExclusionManifestTest {
    private fun file(relative: String): File? = listOf("src/main/$relative", "androidApp/src/main/$relative")
        .map(::File).firstOrNull { it.isFile }

    private val manifestText: String
        get() = requireNotNull(file("AndroidManifest.xml")) { "AndroidManifest.xml not found from ${File(".").absoluteFile}" }.readText()

    private val required = listOf(
        "database" to "console-backups",
        "file" to "console-backups",
        "database" to "console-recovery",
        "file" to "console-recovery",
        "file" to "git-abort-recovery",
        "sharedpref" to "stelekit_secure_prefs.xml",
        "sharedpref" to "stelekit_prefs.xml",
    )

    private fun excludes(xml: String): Set<Pair<String, String>> =
        Regex("""<exclude\s+domain="([^"]+)"\s+path="([^"]+)"\s*/>""").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toSet()

    @Test
    fun manifest_should_EitherDisableBackupOrPointAtRulesFiles() {
        val application = Regex("""<application[^>]*>""").find(manifestText)?.value
        assertNotNull("<application> not found", application)
        val disabled = application!!.contains("""android:allowBackup="false"""")
        val rules = application.contains("""android:fullBackupContent="@xml/backup_rules"""") &&
            application.contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        assertTrue("allowBackup must stay false, or both rules files must be referenced", disabled || rules)
        // Defense in depth: the rules files are referenced even though allowBackup is false.
        assertTrue("rules files not referenced from <application>", rules)
    }

    @Test
    fun fullBackupRules_should_ExcludeEveryNewStateLocation() {
        val xml = requireNotNull(file("res/xml/backup_rules.xml")) { "backup_rules.xml missing" }.readText()
        assertEquals(required.toSet(), excludes(xml))
    }

    @Test
    fun dataExtractionRules_should_ExcludeEveryNewStateLocationFromCloudAndTransfer() {
        val xml = requireNotNull(file("res/xml/data_extraction_rules.xml")) { "data_extraction_rules.xml missing" }.readText()
        val cloud = xml.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = xml.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertEquals(required.toSet(), excludes(cloud))
        assertEquals(required.toSet(), excludes(transfer))
    }
}
