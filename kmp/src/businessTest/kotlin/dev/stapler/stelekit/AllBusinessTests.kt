package dev.stapler.stelekit

import dev.stapler.stelekit.clipboard.BlockClipboardTest
import dev.stapler.stelekit.domain.ImportServiceTest
import dev.stapler.stelekit.editor.LinkInsertionTest
import dev.stapler.stelekit.flashcard.FlashcardPropertiesTest
import dev.stapler.stelekit.flashcard.FlashcardReviewTest
import dev.stapler.stelekit.performance.HistogramRegressionTest
import dev.stapler.stelekit.performance.HistogramWriterTest
import dev.stapler.stelekit.performance.PerfExporterPickerTest
import dev.stapler.stelekit.db.SplitJournalTest
import dev.stapler.stelekit.repository.BacklinkRepositoryTest
import dev.stapler.stelekit.sections.CrossSectionBacklinkRenderTest
import dev.stapler.stelekit.sections.NewPageAutoAssignmentTest
import dev.stapler.stelekit.ui.ToolbarActionTest
import dev.stapler.stelekit.voice.VoiceCaptureViewModelTest
import dev.stapler.stelekit.voice.VoiceNoteBlockFormatTest
import dev.stapler.stelekit.voice.VoicePipelineFactoryTest
import dev.stapler.stelekit.voice.VoiceSettingsTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    BlockClipboardTest::class,
    ImportServiceTest::class,
    LinkInsertionTest::class,
    FlashcardPropertiesTest::class,
    FlashcardReviewTest::class,
    HistogramRegressionTest::class,
    HistogramWriterTest::class,
    PerfExporterPickerTest::class,
    BacklinkRepositoryTest::class,
    CrossSectionBacklinkRenderTest::class,
    NewPageAutoAssignmentTest::class,
    SplitJournalTest::class,
    ToolbarActionTest::class,
    VoiceCaptureViewModelTest::class,
    VoiceNoteBlockFormatTest::class,
    VoicePipelineFactoryTest::class,
    VoiceSettingsTest::class,
)
class AllBusinessTests
