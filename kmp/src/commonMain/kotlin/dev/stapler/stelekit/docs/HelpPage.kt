package dev.stapler.stelekit.docs

import kotlin.reflect.KClass

/**
 * Marks a user-facing feature with its Diataxis documentation class.
 *
 * The [docs] class must implement at least [HowToDoc] and [ReferenceDoc].
 * [DemoGraphCoverageTest] (jvmTest) uses JVM reflection to verify that
 * corresponding .md files exist in the bundled demo graph for every
 * @HelpPage-annotated class.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val docs: KClass<out DiataxisDoc>)
