package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.model.GraphId

class MarkdownTargetWriterContractTest : TargetWriterContractSuite() {
    override fun newHarness(): TargetHarness = Harness()

    private class Harness : TargetHarness {
        private val root = "/graphs/b"
        private val fs = FakeRelocationFileSystem()
        override val writer = MarkdownTargetWriter(
            fs,
            OffGraphTarget(GraphId("b"), root, isActive = false),
            TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
            MarkdownTargetWriter.NoSymlinks,
        )

        private fun path(key: PageKey) = "$root/pages/${key.name}.md"

        override suspend fun seed(key: PageKey, text: String) {
            fs.writeFileBytes(path(key), text.encodeToByteArray())
        }

        override suspend fun editContent(key: PageKey, from: String, to: String) =
            seed(key, checkNotNull(fs.readFile(path(key))).replace(from, to))

        private fun parse(key: PageKey) = fs.readFile(path(key))?.let {
            MergeConverters.parseMarkdown(it, path(key), key.name, key.isJournal)
        }

        override suspend fun snapshot(key: PageKey): MergePage? = parse(key)?.mergePage

        override suspend fun blockUuids(key: PageKey): List<String> = parse(key)?.blocks?.map { it.uuid.value }.orEmpty()

        override suspend fun close() = Unit
    }
}
