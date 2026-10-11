package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.cache.SteleLruCache

val mermaidRenderCache = SteleLruCache<MermaidRenderKey, MermaidRenderResult.Rendered>(maxWeight = 50)
