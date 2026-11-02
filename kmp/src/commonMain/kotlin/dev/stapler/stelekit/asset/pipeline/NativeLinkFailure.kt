package dev.stapler.stelekit.asset.pipeline

private val LINK_FAILURE_NAMES = setOf("UnsatisfiedLinkError", "NoClassDefFoundError", "ExceptionInInitializerError", "LinkageError")

/** A native library or class that could not load (JVM `LinkageError` family); not recoverable per asset. */
internal fun Throwable.isNativeLinkFailure(): Boolean = this::class.simpleName in LINK_FAILURE_NAMES
