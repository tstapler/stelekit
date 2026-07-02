package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException

class SectionManifestParser(private val fileSystem: FileSystem) {

    fun parse(graphPath: String): Either<DomainError, SectionManifest> {
        val base = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val path = "$base${SectionManifest.FILENAME}"
        if (!fileSystem.fileExists(path)) return SectionManifest().right()

        val content = fileSystem.readFile(path)
            ?: return DomainError.FileSystemError.ReadFailed(path, "could not read manifest").left()

        return try {
            (decodeSectionManifestToml(content) ?: SectionManifest()).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.ParseError.InvalidSyntax("${SectionManifest.FILENAME}: ${e.message ?: "invalid TOML"}").left()
        }
    }
}

// ktoml doesn't support Kotlin/Wasm — platform actuals provide real parsing/writing on JVM/iOS,
// stubs on WASM (parser falls back to empty SectionManifest; writer catches the exception).
internal expect fun decodeSectionManifestToml(content: String): SectionManifest?
internal expect fun encodeSectionManifestToml(manifest: SectionManifest): String
