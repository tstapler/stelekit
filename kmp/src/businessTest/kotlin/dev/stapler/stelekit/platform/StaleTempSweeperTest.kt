package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleTempSweeperTest {
    private val now = 1_800_000_000_000L
    private val maxAge = StaleTempSweeper.MAX_AGE.inWholeMilliseconds

    /** Like an OPFS-only file system: no modification times, and real sub-directory listing. */
    private class OpfsLikeFs : FakeRelocationFileSystem() {
        val dirs = mutableMapOf<String, MutableList<String>>()
        val mtimes = mutableMapOf<String, Long>()
        override fun getLastModifiedTime(path: String): Long? = mtimes[path]
        override fun listDirectories(path: String): List<String> = dirs[path].orEmpty()
    }

    private fun v7(millis: Long): String {
        val hex = millis.toString(16).padStart(12, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8)}-7abc-8def-0123456789ab"
    }

    private fun tmp(dir: String, base: String, millis: Long) = "$dir/$base.${v7(millis)}.tmp"

    private fun fsWith(vararg paths: String) = OpfsLikeFs().also { fs -> paths.forEach { fs.writeFileBytes(it, byteArrayOf(1)) } }

    private fun sweeper(fs: FileSystem) = StaleTempSweeper(fs, ".tmp") { now }

    @Test
    fun nullTimeWithAnOldName_should_BeRemoved() {
        val old = tmp("/g/p", "a.json", now - maxAge - 1)
        val fs = fsWith(old)
        sweeper(fs).sweepOnce("/g/p")
        assertFalse(fs.fileExists(old))
    }

    @Test
    fun nullTimeWithAYoungName_should_BeKept() {
        val young = tmp("/g/p", "a.json", now - maxAge + 1_000)
        val fs = fsWith(young)
        sweeper(fs).sweepOnce("/g/p")
        assertTrue(fs.fileExists(young))
    }

    @Test
    fun nonUuidName_should_BeSkipped() {
        val odd = "/g/p/a.json.not-a-uuid.tmp"
        val notV7 = "/g/p/a.json.${v7(0).replace("-7abc-", "-4abc-")}.tmp"
        val fs = fsWith(odd, notV7)
        sweeper(fs).sweepOnce("/g/p")
        assertTrue(fs.fileExists(odd))
        assertTrue(fs.fileExists(notV7))
    }

    @Test
    fun realModificationTime_should_WinOverTheName() {
        val young = tmp("/g/p", "a.json", now - maxAge - 1)
        val fs = fsWith(young)
        fs.mtimes[young] = now
        sweeper(fs).sweepOnce("/g/p")
        assertTrue(fs.fileExists(young))
    }

    @Test
    fun filesWithoutTheSuffix_should_NeverBeRemoved() {
        val live = "/g/p/a.${v7(0)}.json"
        val fs = fsWith(live)
        sweeper(fs).sweepOnce("/g/p")
        assertTrue(fs.fileExists(live))
    }

    @Test
    fun sweepTree_should_RemoveATempTwoDirectoriesDeep() {
        val deep = tmp("/g/proj/sheets/inner", "s.json", now - maxAge - 1)
        val shallow = tmp("/g/proj", "project-model.json", now - maxAge - 1)
        val fs = fsWith(deep, shallow)
        fs.dirs["/g"] = mutableListOf("proj")
        fs.dirs["/g/proj"] = mutableListOf("sheets")
        fs.dirs["/g/proj/sheets"] = mutableListOf("inner")
        sweeper(fs).sweepTree("/g")
        assertFalse(fs.fileExists(deep))
        assertFalse(fs.fileExists(shallow))
    }

    @Test
    fun sweepTree_should_SweepAgain_EvenAfterSweepOnceMarkedTheDirectory() {
        val fs = OpfsLikeFs()
        val s = sweeper(fs)
        s.sweepOnce("/g/p")
        val late = tmp("/g/p", "a.json", now - maxAge - 1)
        fs.writeFileBytes(late, byteArrayOf(1))
        s.sweepOnce("/g/p")
        assertTrue(fs.fileExists(late))
        s.sweepTree("/g/p")
        assertFalse(fs.fileExists(late))
    }
}
