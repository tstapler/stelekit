package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.right

sealed interface CommandError {
    data class Scene(val error: SceneError) : CommandError
    data class UnknownPrimitive(val id: String) : CommandError
    data class UnknownZone(val key: String) : CommandError
    data class DuplicateZone(val key: String) : CommandError
}

/** A user edit. Applying one yields the next [SketchDocument]; the caller commits it to the history. */
sealed interface SketchCommand {
    fun apply(doc: SketchDocument): Either<CommandError, SketchDocument>

    data class AddPrimitive(val primitive: SketchPrimitive) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            doc.scene.add(primitive).mapLeft { CommandError.Scene(it) }.map { doc.copy(scene = it) }
    }

    data class RemovePrimitive(val id: String) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.scene.primitives.none { it.id == id }) CommandError.UnknownPrimitive(id).left()
            else doc.copy(scene = doc.scene.remove(id)).right()
    }

    data class AddZone(val zone: Zone) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.zones.zones.any { it.key == zone.key }) CommandError.DuplicateZone(zone.key).left()
            else doc.copy(zones = ZoneSet(doc.zones.zones + zone)).right()
    }

    /** Types (or clears, with null) the length written on a zone, for example `12 3/16`. */
    data class SetZoneText(val key: String, val text: String?) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.zones.zones.none { it.key == key }) CommandError.UnknownZone(key).left()
            else doc.copy(zones = ZoneSet(doc.zones.zones.map { if (it.key == key) it.copy(text = text) else it })).right()
    }
}
