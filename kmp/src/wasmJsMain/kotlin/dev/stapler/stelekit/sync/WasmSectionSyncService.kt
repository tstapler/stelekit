package dev.stapler.stelekit.sync

import arrow.core.Either
import dev.stapler.stelekit.git.GitHostAdapter
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.time.Clock

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.
private fun jsFetchWithHeader(url: String, headerName: String, headerValue: String): kotlin.js.Promise<JsAny> =
    js("""fetch(url, { headers: { [headerName]: headerValue, 'Accept': 'application/vnd.github+json' } })""")

private fun jsFetchAnon(url: String): kotlin.js.Promise<JsAny> =
    js("""fetch(url, { headers: { 'Accept': 'application/vnd.github+json' } })""")

private fun jsResponseStatus(response: JsAny): Int = js("response.status | 0")
private fun jsResponseHeader(response: JsAny, name: String): String? =
    js("response.headers.get(name) || null")
private fun jsResponseText(response: JsAny): kotlin.js.Promise<JsAny> = js("response.text()")
private fun jsStringValue(v: JsAny): String = js("String(v)")

/**
 * WASM-only service: fetches a GitHub repo tree for a section and inserts INDEX_ONLY stub pages.
 *
 * Callers set companion fields (owner, repo, branch, token) at startup via Main.kt.
 * Each call to [syncSection] is independent and safe to run concurrently for different sections.
 */
class WasmSectionSyncService(private val pageRepository: PageRepository) {

    private val logger = Logger("WasmSectionSync")

    @OptIn(DirectRepositoryWrite::class)
    suspend fun syncSection(section: SectionDefinition) {
        val owner = githubOwner.ifEmpty { return }
        val repo = githubRepo.ifEmpty { return }
        val branch = githubBranch
        val token = githubToken

        val treeUrl = "${GitHostAdapter.apiBase(GitHostType.GITHUB, owner, repo)}/git/trees/$branch?recursive=1"
        val treeJson = githubFetch(treeUrl, token) ?: run {
            logger.error("Failed to fetch tree for section ${section.id}")
            return
        }

        val paths = extractTreePaths(treeJson)
        for (path in paths) {
            if (!path.endsWith(".md")) continue
            val inSection = path.startsWith(section.pagePathPrefix) || path.startsWith(section.journalPathPrefix)
            if (!inSection) continue
            val isJournal = path.startsWith(section.journalPathPrefix)
            val name = path.substringAfterLast("/").removeSuffix(".md")
            val fullPath = "/stelekit/$graphId/$path"
            val stubPage = Page(
                uuid = PageUuid(UuidGenerator.generateV7()),
                name = name,
                filePath = fullPath,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                isJournal = isJournal,
                journalDate = if (isJournal) JournalUtils.parseJournalDate(name) else null,
                isContentLoaded = false,
                sectionId = dev.stapler.stelekit.model.SectionId.fromDbString(section.id),
            )
            val result = pageRepository.savePage(stubPage)
            if (result is Either.Left) {
                logger.error("Failed to save stub for $fullPath: ${result.value.message}")
            }
        }
    }

    companion object {
        var githubOwner: String = ""
        var githubRepo: String = ""
        var githubBranch: String = "main"
        var githubToken: String? = null
        var graphId: String = "default"
        private val companionLogger = Logger("WasmSectionSync")

        suspend fun githubFetch(url: String, token: String?, retryCount: Int = 0): String? {
            return try {
                val fetchPromise = if (token != null) {
                    val (headerName, headerValue) = GitHostAdapter.authHeader(GitHostType.GITHUB, token)
                    jsFetchWithHeader(url, headerName, headerValue)
                } else {
                    jsFetchAnon(url)
                }
                val response = fetchPromise.await<JsAny>()
                val status = jsResponseStatus(response)
                if (status == 429) {
                    val retryAfter = jsResponseHeader(response, "Retry-After")?.toIntOrNull()
                        ?: (1 shl retryCount).coerceAtMost(60)
                    if (retryCount < 4) {
                        delay(retryAfter * 1000L)
                        return githubFetch(url, token, retryCount + 1)
                    }
                    companionLogger.error("Rate limit exhausted for $url after ${retryCount + 1} retries")
                    return null
                }
                if (status < 200 || status >= 300) return null
                jsStringValue(jsResponseText(response).await<JsAny>())
            } catch (e: Throwable) {
                companionLogger.error("fetch error for $url: ${e.message}")
                null
            }
        }

        private fun extractTreePaths(json: String): List<String> {
            val results = mutableListOf<String>()
            var i = json.indexOf("\"path\"")
            while (i != -1) {
                val colon = json.indexOf(':', i)
                if (colon == -1) break
                val q1 = json.indexOf('"', colon + 1)
                if (q1 == -1) break
                val q2 = json.indexOf('"', q1 + 1)
                if (q2 == -1) break
                results.add(json.substring(q1 + 1, q2))
                i = json.indexOf("\"path\"", q2 + 1)
            }
            return results
        }
    }
}
