package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.units.Inches
import kotlin.math.sign

sealed interface CommandError {
    data class Scene(val error: SceneError) : CommandError
    data class UnknownPrimitive(val id: String) : CommandError
    data class UnknownZone(val key: String) : CommandError
    data class DuplicateZone(val key: String) : CommandError
    data class UnknownLayer(val name: String) : CommandError
    data class DuplicateLayer(val name: String) : CommandError
    data class InvalidLayerName(val name: String) : CommandError
    data class LayerLocked(val name: String) : CommandError
    data object TooManyLayers : CommandError
    data class NotALine(val id: String) : CommandError

    /** Only horizontal and vertical lines take a typed length; a slanted one has no exact-sixteenth length. */
    data class NotAxisAligned(val id: String) : CommandError
    data class ZeroLengthLine(val id: String) : CommandError
    data class InvalidScale(val sixteenths: Long) : CommandError
    data class DirectionNotAllowed(val kind: SketchSheetKind, val direction: ViewDirection) : CommandError
}

/** Plain-language reason a command was refused, for the status line. */
fun CommandError.message(): String = when (this) {
    is CommandError.Scene -> when (val e = error) {
        is SceneError.CapacityExceeded -> "the sheet is full (${e.max} shapes)"
        is SceneError.DuplicateId -> "duplicate shape"
        is SceneError.CoordinateOutOfRange -> "that is too far from the origin"
    }
    is CommandError.UnknownPrimitive -> "no such shape"
    is CommandError.UnknownZone -> "no such zone"
    is CommandError.DuplicateZone -> "zone $key already exists"
    is CommandError.UnknownLayer -> "no such layer"
    is CommandError.DuplicateLayer -> "layer $name already exists"
    is CommandError.InvalidLayerName -> "layer names cannot be blank or contain ;"
    is CommandError.LayerLocked -> if (name.isEmpty()) "the default layer is locked" else "layer $name is locked"
    CommandError.TooManyLayers -> "too many layers"
    is CommandError.NotALine -> "only lines have a length"
    is CommandError.NotAxisAligned -> "only horizontal or vertical lines take a typed length"
    is CommandError.ZeroLengthLine -> "the line has no direction yet"
    is CommandError.InvalidScale -> "scale out of range"
    is CommandError.DirectionNotAllowed -> "${kind.wire} sheets cannot look ${direction.wire}"
}

private fun SketchDocument.primitive(id: String): SketchPrimitive? = scene.primitives.firstOrNull { it.id == id }

private fun SketchDocument.lockedLayerOf(primitive: SketchPrimitive): CommandError? =
    if (sheet.isLocked(primitive.layer)) CommandError.LayerLocked(primitive.layer) else null

/** The primitive with vertex [index] moved to [to]; null when there is no such vertex. */
fun SketchPrimitive.withVertex(index: Int, to: SketchPoint): SketchPrimitive? = when (this) {
    is SketchPrimitive.Line -> when (index) {
        0 -> copy(a = to)
        1 -> copy(b = to)
        else -> null
    }
    is SketchPrimitive.Rect -> when (index) {
        0 -> copy(a = to)
        1 -> copy(a = SketchPoint(a.x, to.y), b = SketchPoint(to.x, b.y))
        2 -> copy(b = to)
        3 -> copy(a = SketchPoint(to.x, a.y), b = SketchPoint(b.x, to.y))
        else -> null
    }
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
            doc.primitive(id).let { target ->
                when {
                    target == null -> CommandError.UnknownPrimitive(id).left()
                    else -> doc.lockedLayerOf(target)?.left() ?: doc.copy(scene = doc.scene.remove(id)).right()
                }
            }
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

    /** Marks [key] as the zone the others must add up to (at most one total per sheet), or clears it. */
    data class SetZoneTotal(val key: String, val isTotal: Boolean) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.zones.zones.none { it.key == key }) CommandError.UnknownZone(key).left()
            else doc.copy(zones = ZoneSet(doc.zones.zones.map { it.copy(isTotal = isTotal && it.key == key) })).right()
    }

    data class RemoveZone(val key: String) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.zones.zones.none { it.key == key }) CommandError.UnknownZone(key).left()
            else doc.copy(zones = ZoneSet(doc.zones.zones.filterNot { it.key == key })).right()
    }

    /** Sets a horizontal or vertical line to [length], keeping its first end and direction. */
    data class SetLineLength(val id: String, val length: Inches) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> {
            val line = doc.primitive(id) as? SketchPrimitive.Line ?: return (doc.primitive(id)?.let { CommandError.NotALine(id) } ?: CommandError.UnknownPrimitive(id)).left()
            doc.lockedLayerOf(line)?.let { return it.left() }
            val dx = line.b.x.sixteenths - line.a.x.sixteenths
            val dy = line.b.y.sixteenths - line.a.y.sixteenths
            val moved = when {
                dx != 0L && dy != 0L -> return CommandError.NotAxisAligned(id).left()
                dx == 0L && dy == 0L -> return CommandError.ZeroLengthLine(id).left()
                dx != 0L -> line.copy(b = SketchPoint(Inches(line.a.x.sixteenths + length.sixteenths * dx.sign), line.b.y))
                else -> line.copy(b = SketchPoint(line.b.x, Inches(line.a.y.sixteenths + length.sixteenths * dy.sign)))
            }
            return doc.scene.replace(moved).mapLeft { CommandError.Scene(it) }.map { doc.copy(scene = it) }
        }
    }

    /** Moves one vertex (line end, or rectangle corner in [SketchPrimitive.vertices] order) to [to]. */
    data class MoveVertex(val id: String, val index: Int, val to: SketchPoint) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> {
            val target = doc.primitive(id) ?: return CommandError.UnknownPrimitive(id).left()
            doc.lockedLayerOf(target)?.let { return it.left() }
            val moved = target.withVertex(index, to) ?: return CommandError.UnknownPrimitive(id).left()
            return doc.scene.replace(moved).mapLeft { CommandError.Scene(it) }.map { doc.copy(scene = it) }
        }
    }

    data class AddLayer(val name: String) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> = when {
            !SketchLayer.isValidName(name) -> CommandError.InvalidLayerName(name).left()
            doc.sheet.layer(name) != null -> CommandError.DuplicateLayer(name).left()
            doc.sheet.layers.size >= SheetProps.MAX_LAYERS -> CommandError.TooManyLayers.left()
            else -> doc.copy(sheet = doc.sheet.copy(layers = doc.sheet.layers + SketchLayer(name))).right()
        }
    }

    data class SetLayerVisible(val name: String, val visible: Boolean) : SketchCommand {
        override fun apply(doc: SketchDocument) = editLayer(doc, name) { it.copy(visible = visible) }
    }

    data class SetLayerLocked(val name: String, val locked: Boolean) : SketchCommand {
        override fun apply(doc: SketchDocument) = editLayer(doc, name) { it.copy(locked = locked) }
    }

    /** Moves a primitive to layer [layer] (`""` is the default layer); neither the old nor the new layer may be locked. */
    data class SetPrimitiveLayer(val id: String, val layer: String) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> {
            val target = doc.primitive(id) ?: return CommandError.UnknownPrimitive(id).left()
            if (layer.isNotEmpty() && doc.sheet.layer(layer) == null) return CommandError.UnknownLayer(layer).left()
            doc.lockedLayerOf(target)?.let { return it.left() }
            if (doc.sheet.isLocked(layer)) return CommandError.LayerLocked(layer).left()
            return doc.scene.replace(target.withLayer(layer)).mapLeft { CommandError.Scene(it) }.map { doc.copy(scene = it) }
        }
    }

    /** Changes the sheet type and resets direction and scale to its defaults; a no-op for the current type. */
    data class SetSheetKind(val kind: SketchSheetKind) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (doc.sheet.kind == kind) doc.right() else doc.copy(sheet = doc.sheet.withKind(kind)).right()
    }

    data class SetViewDirection(val direction: ViewDirection) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (direction !in doc.sheet.kind.directions) CommandError.DirectionNotAllowed(doc.sheet.kind, direction).left()
            else doc.copy(sheet = doc.sheet.copy(viewDirection = direction)).right()
    }

    data class SetScale(val sixteenths: Long) : SketchCommand {
        override fun apply(doc: SketchDocument): Either<CommandError, SketchDocument> =
            if (sixteenths !in 1..SheetProps.MAX_SCALE_SIXTEENTHS) CommandError.InvalidScale(sixteenths).left()
            else doc.copy(sheet = doc.sheet.copy(scaleSixteenths = sixteenths)).right()
    }
}

private fun editLayer(doc: SketchDocument, name: String, edit: (SketchLayer) -> SketchLayer): Either<CommandError, SketchDocument> =
    if (doc.sheet.layer(name) == null) CommandError.UnknownLayer(name).left()
    else doc.copy(sheet = doc.sheet.copy(layers = doc.sheet.layers.map { if (it.name == name) edit(it) else it })).right()
