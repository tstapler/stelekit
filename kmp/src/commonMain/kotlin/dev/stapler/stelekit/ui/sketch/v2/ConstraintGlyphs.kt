// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

object ConstraintGlyphTags {
    const val OVERLAY = "sketch.glyphs"
    fun glyph(id: ConstraintId) = "sketch.glyph.${id.value}"
    fun badge(index: Int) = "sketch.glyph.badge.$index"
    fun badgeRow(id: ConstraintId) = "sketch.glyph.badge.row.${id.value}"
}

/** What one mark shows and says: its text, its screen-reader description and its test tag. */
private class MarkFace(val text: String, val description: String, val tag: String)

/**
 * The glyph overlay (Task 4.2.3b/c, UX-106): each mark of [layout] is drawn 24 dp visible inside a hit area of `layout.hitDp`.
 * A lone glyph selects its constraint; a count badge opens the list of the constraints it stands for. Marks carry their state
 * as text (a letter or symbol and a description), the colours are the theme's `onSurface` on `surface`, which keep 3:1.
 */
@Composable
fun ConstraintGlyphOverlay(
    doc: SketchDocumentV2,
    layout: GlyphLayout,
    technical: Boolean,
    onSelect: (ConstraintId) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: ((ConstraintId) -> Unit)? = null,
) {
    Box(modifier.testTag(ConstraintGlyphTags.OVERLAY).semantics { contentDescription = SketchConstraintStrings.Panel.GLYPHS_REGION }) {
        layout.marks.forEachIndexed { index, mark ->
            val hit = layout.hitDp
            Box(Modifier.offset((mark.x - hit / 2).dp, (mark.y - hit / 2).dp).size(hit.dp)) {
                MarkTarget(doc, mark, index, technical, onSelect, onEdit)
            }
        }
    }
}

@Composable
private fun MarkTarget(doc: SketchDocumentV2, mark: GlyphMark, index: Int, technical: Boolean, onSelect: (ConstraintId) -> Unit, onEdit: ((ConstraintId) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val face = faceOf(doc, mark, index, technical) ?: return
    Box {
        MarkFaceBox(face, mark, onSelect, editOf(doc, mark, onEdit)) { open = true }
        if (mark !is GlyphMark.Glyph) BadgeList(doc, mark, open, onSelect) { open = false }
    }
}

/** The edit action of a dimension's glyph (tap its label, or Enter or F2 while it has focus), or null for any other mark (UX-99). */
private fun editOf(doc: SketchDocumentV2, mark: GlyphMark, onEdit: ((ConstraintId) -> Unit)?): (() -> Unit)? {
    val id = (mark as? GlyphMark.Glyph)?.id ?: return null
    val dimension = doc.constraints.firstOrNull { it.id == id }?.isDimensional == true
    return if (dimension && onEdit != null) ({ onEdit(id) }) else null
}

@Composable
private fun MarkFaceBox(face: MarkFace, mark: GlyphMark, onSelect: (ConstraintId) -> Unit, onEdit: (() -> Unit)?, onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxSize().testTag(face.tag)
            .onKeyEvent { e -> (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.F2) && onEdit != null).also { if (it) onEdit?.invoke() } }
            .clickable { if (onEdit != null) onEdit() else if (mark is GlyphMark.Glyph) onSelect(mark.id) else onOpen() }
            .semantics { contentDescription = face.description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(ConstraintGlyphLayout.VISIBLE_DP.dp).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Text(face.text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BadgeList(doc: SketchDocumentV2, mark: GlyphMark, open: Boolean, onSelect: (ConstraintId) -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        for (id in mark.ids) {
            val constraint = doc.constraints.firstOrNull { it.id == id } ?: continue
            DropdownMenuItem(
                text = { Text(SketchConstraintStrings.Panel.kindLabel(constraint.kind)) },
                onClick = { onClose(); onSelect(id) },
                modifier = Modifier.testTag(ConstraintGlyphTags.badgeRow(id)),
            )
        }
    }
}

private fun faceOf(doc: SketchDocumentV2, mark: GlyphMark, index: Int, technical: Boolean): MarkFace? = when (mark) {
    is GlyphMark.Glyph -> doc.constraints.firstOrNull { it.id == mark.id }?.let { c ->
        val value = ConstraintValueText.of(c, technical)
        val kind = SketchConstraintStrings.Panel.kindLabel(c.kind)
        MarkFace(value ?: SketchConstraintStrings.Panel.glyphOf(c.kind), SketchConstraintStrings.Panel.rowText(kind, value, "", null), ConstraintGlyphTags.glyph(c.id))
    }
    is GlyphMark.Badge -> MarkFace(mark.ids.size.toString(), SketchConstraintStrings.Panel.badgeSemantics(mark.ids.size), ConstraintGlyphTags.badge(index))
    is GlyphMark.ElementBadge -> {
        val name = doc.scene[mark.elementId]?.let { ElementNames.nameOf(it) }.orEmpty()
        MarkFace(mark.ids.size.toString(), SketchConstraintStrings.Panel.elementBadgeSemantics(name, mark.ids.size), ConstraintGlyphTags.badge(index))
    }
}
