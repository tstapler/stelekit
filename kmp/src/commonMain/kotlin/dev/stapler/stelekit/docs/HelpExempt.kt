package dev.stapler.stelekit.docs

/**
 * Exempts a Screen subclass from the @HelpPage requirement.
 *
 * Use for internal/diagnostic screens that are never user-initiated entry points
 * (e.g. debug menu, conflict resolution sub-steps, annotation editor opened only
 * from an image block). The reason parameter is required to make exemptions
 * intentional and reviewable in code review.
 *
 * Prefer @HelpPage over @HelpExempt wherever possible. See CLAUDE.md for the
 * approved list of exempt screen categories.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class HelpExempt(val reason: String)
