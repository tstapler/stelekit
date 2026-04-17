package dev.stapler.stelekit.docs

/**
 * Registers all feature documentation with [FeatureDocRegistry] at app startup.
 * Call this once from each platform's entry point before navigating to any screen.
 */
object AppDocsInitializer {
    fun initialize() {
        FeatureDocRegistry.register(JournalsDocs::class) { JournalsDocs() }
        FeatureDocRegistry.register(FlashcardsDocs::class) { FlashcardsDocs() }
        FeatureDocRegistry.register(AllPagesDocs::class) { AllPagesDocs() }
        FeatureDocRegistry.register(PageViewDocs::class) { PageViewDocs() }
        FeatureDocRegistry.register(SearchDocs::class) { SearchDocs() }
    }
}
