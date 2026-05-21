// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance

import io.opentelemetry.api.trace.Tracer

fun createAndroidSpanRecorder(): SpanRecorder =
    OtelSpanRecorder(OtelProvider.getTracer("compose.navigation") as Tracer)
