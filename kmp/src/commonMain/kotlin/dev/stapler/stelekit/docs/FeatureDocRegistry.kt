package dev.stapler.stelekit.docs

import kotlin.reflect.KClass

/**
 * Cross-platform factory registry for feature documentation.
 * Uses explicit factory lambdas instead of reflection to work on iOS/JS/Android.
 * Register all features at app startup via [AppDocsInitializer].
 */
object FeatureDocRegistry {
    private val registry = mutableMapOf<KClass<out DiataxisDoc>, () -> DiataxisDoc>()

    fun <T : DiataxisDoc> register(klass: KClass<T>, factory: () -> T) {
        @Suppress("UNCHECKED_CAST")
        registry[klass] = factory as () -> DiataxisDoc
    }

    fun get(klass: KClass<out DiataxisDoc>): DiataxisDoc? = registry[klass]?.invoke()

    fun registeredClasses(): Set<KClass<out DiataxisDoc>> = registry.keys.toSet()

    internal fun clearForTesting() { registry.clear() }
}
