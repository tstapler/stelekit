package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
class SectionManifestWriter(private val fileSystem: FileSystem) {

    fun write(notesPath: EffectiveNotesPath, manifest: SectionManifest): Either<DomainError, Unit> {
        val path = notesPath.child(SectionManifest.FILENAME)
        return try {
            val content = encodeSectionManifestToml(manifest)
            if (fileSystem.writeFile(path, content)) Unit.right()
            else DomainError.FileSystemError.WriteFailed(path, "write returned false").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.FileSystemError.WriteFailed(path, e.message ?: "serialization failed").left()
        }
    }
}
