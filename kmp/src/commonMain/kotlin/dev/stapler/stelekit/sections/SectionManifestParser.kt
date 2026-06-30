package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.serialization.serializer

class SectionManifestParser(private val fileSystem: FileSystem) {

    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

    fun parse(graphPath: String): Either<DomainError, SectionManifest> {
        val base = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val path = "$base${SectionManifest.FILENAME}"
        if (!fileSystem.fileExists(path)) return SectionManifest().right()

        val content = fileSystem.readFile(path)
            ?: return DomainError.FileSystemError.ReadFailed(path, "could not read manifest").left()

        return try {
            toml.decodeFromString(serializer<SectionManifest>(), content).right()
        } catch (e: Exception) {
            DomainError.ParseError.InvalidSyntax("${SectionManifest.FILENAME}: ${e.message ?: "invalid TOML"}").left()
        }
    }
}
