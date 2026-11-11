package dev.stapler.stelekit.ui.sketch

import androidx.compose.runtime.RememberObserver
import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchCommand
import dev.stapler.stelekit.sketch.message
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchHistory
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.Zone
import dev.stapler.stelekit.sketch.ZoneConstraints
import dev.stapler.stelekit.sketch.ZoneReport
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.stapler.stelekit.model.SketchPrimitive as StoredPrimitive

enum class SketchTool { LINE, RECTANGLE, ZONE_SELECT }

enum class SketchPhase { LOADING, READY, FAILED }

enum class SaveStatus { SAVED, PENDING, FAILED }

data class SketchUiState(
    val phase: SketchPhase = SketchPhase.LOADING,
    val error: String? = null,
    val history: SketchHistory = SketchHistory.of(),
    val tool: SketchTool = SketchTool.LINE,
    /** First corner of the shape being placed; cleared by undo, redo and tool changes. */
    val pendingStart: SketchPoint? = null,
    val selectedId: String? = null,
    val zoneReport: ZoneReport? = null,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
) {
    val document: SketchDocument get() = history.present
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
}

/**
 * Editor state for one sketch sheet. Owns its scope (see AssetBrowserViewModel): store it with
 * `remember { }` only. Commits go through [SketchHistory]; a debounced autosave writes the diff
 * through the repositories carrying the revision token, and a rebuilt project (the sidecar
 * reconcile) cancels the pending save and reloads.
 */
class SketchViewModel(
    private val repos: SketchRepositories,
    private val projectUuid: ProjectUuid?,
    private val writeActor: DatabaseWriteActor? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = { UuidGenerator.generateV7() },
) : RememberObserver {
    private val logger = Logger("SketchViewModel")
    private val store = SketchStore(repos, writeActor)
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, t ->
                logger.error("SketchViewModel uncaught: ${t.message}")
                _uiState.update { it.copy(phase = SketchPhase.FAILED, error = t.message ?: "unexpected error") }
            },
    )

    private val _uiState = MutableStateFlow(SketchUiState())
    val uiState: StateFlow<SketchUiState> = _uiState.asStateFlow()

    private var project: ProjectUuid? = projectUuid
    private var token: RevisionToken? = null
    private var sheet: SketchSheet? = null
    private var persisted: Map<String, StoredPrimitive> = emptyMap()
    private var saveJob: Job? = null
    private var loadJob: Job? = null

    init {
        load()
        scope.launch {
            repos.projects.invalidations.collect { rebuilt ->
                if (rebuilt == project) {
                    saveJob?.cancel()
                    load()
                }
            }
        }
    }

    // ---- editing ----

    fun setTool(tool: SketchTool) = _uiState.update { it.copy(tool = tool, pendingStart = null, selectedId = null) }

    fun select(id: String?) = _uiState.update { it.copy(selectedId = id) }

    /** A snapped placement tap for the line and rectangle tools: the first sets the anchor, the second commits. */
    fun placePoint(point: SketchPoint) {
        val state = _uiState.value
        if (state.phase != SketchPhase.READY) return
        val start = state.pendingStart
        when {
            state.tool == SketchTool.ZONE_SELECT -> Unit
            start == null -> _uiState.update { it.copy(pendingStart = point) }
            start == point -> Unit
            else -> {
                val id = newId()
                val shape = if (state.tool == SketchTool.LINE) SketchPrimitive.Line(id, start, point) else SketchPrimitive.Rect(id, start, point)
                _uiState.update { it.copy(pendingStart = null) }
                commit(SketchCommand.AddPrimitive(shape))
            }
        }
    }

    fun deleteSelected() {
        _uiState.value.selectedId?.let { commit(SketchCommand.RemovePrimitive(it)); select(null) }
    }

    fun addZone(zone: Zone) = commit(SketchCommand.AddZone(zone))

    /** Types a length such as `12 3/16` on a zone; dependents re-solve and a violated sum is flagged in [SketchUiState.zoneReport]. */
    fun typeZoneText(key: String, text: String?) = commit(SketchCommand.SetZoneText(key, text))

    fun undo() = stepHistory { it.undo() }

    fun redo() = stepHistory { it.redo() }

    /** Applies [command] and commits the result as one history step. */
    fun commit(command: SketchCommand) {
        var failure: String? = null
        _uiState.update { state ->
            if (state.phase != SketchPhase.READY) return@update state
            command.apply(state.document).fold(
                ifLeft = { failure = it.message(); state },
                ifRight = { next -> withHistory(state, state.history.commit(next)) },
            )
        }
        failure?.let { msg -> _uiState.update { it.copy(error = msg) } } ?: scheduleSave()
    }

    private fun stepHistory(step: (SketchHistory) -> SketchHistory) {
        _uiState.update { state -> withHistory(state, step(state.history)).copy(pendingStart = null, selectedId = null) }
        scheduleSave()
    }

    // ---- persistence ----

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            save()
        }
    }

    /** Writes any pending change now; used by tests and when the screen leaves. */
    suspend fun flush() {
        saveJob?.cancel()
        if (_uiState.value.saveStatus == SaveStatus.PENDING) save()
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun save() {
        val doc = _uiState.value.document
        val tok = token ?: return
        val currentSheet = sheet ?: return
        val wanted = doc.scene.primitives.mapIndexed { z, p -> p.toStored(currentSheet.uuid, z.toLong()) }
        val upserts = wanted.filter { persisted[it.uuid] != it }
        val removed = persisted.keys - wanted.map { it.uuid }.toSet()
        val desiredSheet = doc.sheet.applyTo(currentSheet).copy(zones = doc.zones.toJson())
        val result: Either<DomainError, Unit> = either {
            if (desiredSheet != currentSheet) {
                store.write { repos.sheets.saveSheet(desiredSheet, tok) }.bind()
                sheet = desiredSheet
            }
            if (upserts.isNotEmpty()) store.write { repos.sheets.savePrimitives(upserts, tok) }.bind()
            for (id in removed) store.write { repos.sheets.deletePrimitive(id, tok) }.bind()
        }
        result.fold(
            ifLeft = { err ->
                logger.warn("sketch autosave failed: ${err.message}")
                _uiState.update { it.copy(saveStatus = SaveStatus.FAILED, error = err.message) }
                if (err.message?.contains("stale-editor") == true) load()
            },
            ifRight = {
                persisted = wanted.associateBy { it.uuid }
                _uiState.update { if (it.document.sameContent(doc)) it.copy(saveStatus = SaveStatus.SAVED) else it }
            },
        )
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.update { it.copy(phase = SketchPhase.LOADING) }
            val outcome = either {
                val uuid = project ?: store.findOrCreateProject(newId).bind().also { project = it }
                val (loaded, first) = store.loadSheet(uuid, newId).bind()
                Triple(loaded, first, store.readAllPrimitives(first.uuid).bind())
            }
            outcome.fold(ifLeft = { fail(it.message) }, ifRight = { (loaded, first, stored) -> apply(loaded.token, first, stored) })
        }
    }

    private fun apply(newToken: RevisionToken, first: SketchSheet, stored: List<StoredPrimitive>) {
        token = newToken
        sheet = first
        persisted = stored.associateBy { it.uuid }
        val scene = stored.mapNotNull { it.toScenePrimitive() }.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: s }
        val zones = ZoneSet.fromJson(first.zones)
        val props = first.toSheetProps()
        _uiState.update {
            it.copy(
                phase = SketchPhase.READY, error = null, history = SketchHistory.of(SketchDocument(scene, zones, props)), pendingStart = null,
                selectedId = null, zoneReport = ZoneConstraints.resolve(zones, props.viewDirection).getOrNull(), saveStatus = SaveStatus.SAVED,
            )
        }
    }

    private fun fail(message: String) = _uiState.update { it.copy(phase = SketchPhase.FAILED, error = message) }

    override fun onRemembered() { /* scope already started */ }
    override fun onForgotten() { scope.cancel() }
    override fun onAbandoned() { scope.cancel() }

    companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}

private fun withHistory(state: SketchUiState, history: SketchHistory): SketchUiState =
    state.copy(history = history, zoneReport = ZoneConstraints.resolve(history.present.zones, history.present.sheet.viewDirection).getOrNull(), saveStatus = SaveStatus.PENDING, error = null)

private fun SketchSheet.toSheetProps(): SheetProps = SheetProps.fromStored(kind, viewDirection, scaleSixteenthsPerUnit, layers)

private fun SheetProps.applyTo(stored: SketchSheet): SketchSheet = stored.copy(
    kind = kind.wire, viewDirection = viewDirection.wire, scaleSixteenthsPerUnit = scaleSixteenths, layers = layers.map { it.encode() },
)

private fun SketchPrimitive.toStored(sheet: SheetUuid, z: Long): StoredPrimitive = when (this) {
    is SketchPrimitive.Line -> StoredPrimitive(id, sheet, "line", a.x.sixteenths, a.y.sixteenths, b.x.sixteenths, b.y.sixteenths, layer = layer, z = z)
    is SketchPrimitive.Rect -> StoredPrimitive(id, sheet, "rect", a.x.sixteenths, a.y.sixteenths, b.x.sixteenths, b.y.sixteenths, layer = layer, z = z)
}

private fun StoredPrimitive.toScenePrimitive(): SketchPrimitive? {
    val a = SketchPoint(Inches(x1), Inches(y1))
    val b = SketchPoint(Inches(x2), Inches(y2))
    return when (kind) {
        "line" -> SketchPrimitive.Line(uuid, a, b, layer)
        "rect" -> SketchPrimitive.Rect(uuid, a, b, layer)
        else -> null
    }
}

private fun ZoneSet.toJson(): JsonArray = JsonArray(
    zones.map { z ->
        JsonObject(buildMap {
            put("key", JsonPrimitive(z.key))
            z.text?.let { put("text", JsonPrimitive(it)) }
            if (z.isTotal) put("total", JsonPrimitive(true))
        })
    },
)

private fun ZoneSet.Companion.fromJson(array: JsonArray): ZoneSet = ZoneSet(
    array.mapNotNull { e ->
        val o = (e as? JsonObject) ?: return@mapNotNull null
        val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        Zone(key, o["text"]?.jsonPrimitive?.contentOrNull, o["total"]?.jsonPrimitive?.booleanOrNull == true)
    },
)

/** Builds the editor for a `Screen.Sketch` route; [projectUuid] null opens the first project. */
fun sketchViewModelFor(repos: SketchRepositories, writeActor: DatabaseWriteActor?, projectUuid: String?): SketchViewModel =
    SketchViewModel(repos, projectUuid?.let(::ProjectUuid), writeActor)
