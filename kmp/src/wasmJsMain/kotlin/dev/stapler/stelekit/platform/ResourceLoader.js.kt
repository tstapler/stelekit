package dev.stapler.stelekit.platform

actual fun platformResourceLoader(): ResourceLoader = object : ResourceLoader {
    override fun readResource(path: String): String? = null
    override fun listResourceDirectory(path: String): List<String> = emptyList()
}
