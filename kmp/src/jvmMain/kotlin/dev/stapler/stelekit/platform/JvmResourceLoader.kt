package dev.stapler.stelekit.platform

import java.io.File

class JvmResourceLoader : ResourceLoader {
    private val classLoader = javaClass.classLoader

    override fun readResource(path: String): String? = runCatching {
        classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
    }.getOrNull()

    override fun listResourceDirectory(path: String): List<String> = runCatching {
        val url = classLoader.getResource(path) ?: return emptyList()
        File(url.toURI()).listFiles()?.map { it.name } ?: emptyList()
    }.getOrElse { emptyList() }
}

actual fun platformResourceLoader(): ResourceLoader = JvmResourceLoader()
