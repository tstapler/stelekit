package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.serialization.serializer

class SectionManifestWriter(private val fileSystem: FileSystem) {

    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

    fun write(graphPath: String, manifest: SectionManifest): Either<DomainError, Unit> {
        val base = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val path = "$base${SectionManifest.FILENAME}"
        return try {
            val content = toml.encodeToString(serializer<SectionManifest>(), manifest)
            if (fileSystem.writeFile(path, content)) Unit.right()
            else DomainError.FileSystemError.WriteFailed(path, "write returned false").left()
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(path, e.message ?: "serialization failed").left()
        }
    }
}
