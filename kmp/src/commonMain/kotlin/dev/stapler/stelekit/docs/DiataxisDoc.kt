package dev.stapler.stelekit.docs

interface DiataxisDoc

interface HowToDoc : DiataxisDoc {
    val howTo: HowToContent
}

interface ReferenceDoc : DiataxisDoc {
    val reference: ReferenceContent
}

interface TutorialDoc : DiataxisDoc {
    val tutorial: TutorialContent
}

interface ExplanationDoc : DiataxisDoc {
    val explanation: ExplanationContent
}

/** Minimum required coverage for all user-facing features. */
interface MinimalFeatureDoc : HowToDoc, ReferenceDoc

data class HowToContent(
    val title: String,
    val description: String = "",
    val steps: List<String>,
    val tips: List<String> = emptyList()
)

data class ReferenceContent(
    val title: String,
    val description: String,
    val sections: List<ReferenceSection> = emptyList()
)

data class ReferenceSection(val heading: String, val body: String)

data class TutorialContent(
    val title: String,
    val description: String,
    val steps: List<String>
)

data class ExplanationContent(
    val title: String,
    val body: String
)
