// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.model

import arrow.optics.Lens
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

object PageOptics {
    val uuid: Lens<Page, String> = Lens(get = { it.uuid }, set = { p, v -> p.copy(uuid = v) })
    val name: Lens<Page, String> = Lens(get = { it.name }, set = { p, v -> p.copy(name = v) })
    val namespace: Lens<Page, String?> = Lens(get = { it.namespace }, set = { p, v -> p.copy(namespace = v) })
    val filePath: Lens<Page, String?> = Lens(get = { it.filePath }, set = { p, v -> p.copy(filePath = v) })
    val createdAt: Lens<Page, Instant> = Lens(get = { it.createdAt }, set = { p, v -> p.copy(createdAt = v) })
    val updatedAt: Lens<Page, Instant> = Lens(get = { it.updatedAt }, set = { p, v -> p.copy(updatedAt = v) })
    val version: Lens<Page, Long> = Lens(get = { it.version }, set = { p, v -> p.copy(version = v) })
    val properties: Lens<Page, Map<String, String>> = Lens(get = { it.properties }, set = { p, v -> p.copy(properties = v) })
    val isFavorite: Lens<Page, Boolean> = Lens(get = { it.isFavorite }, set = { p, v -> p.copy(isFavorite = v) })
    val isJournal: Lens<Page, Boolean> = Lens(get = { it.isJournal }, set = { p, v -> p.copy(isJournal = v) })
    val journalDate: Lens<Page, LocalDate?> = Lens(get = { it.journalDate }, set = { p, v -> p.copy(journalDate = v) })
    val isContentLoaded: Lens<Page, Boolean> = Lens(get = { it.isContentLoaded }, set = { p, v -> p.copy(isContentLoaded = v) })
}

object BlockOptics {
    val uuid: Lens<Block, String> = Lens(get = { it.uuid }, set = { b, v -> b.copy(uuid = v) })
    val pageUuid: Lens<Block, String> = Lens(get = { it.pageUuid }, set = { b, v -> b.copy(pageUuid = v) })
    val parentUuid: Lens<Block, String?> = Lens(get = { it.parentUuid }, set = { b, v -> b.copy(parentUuid = v) })
    val leftUuid: Lens<Block, String?> = Lens(get = { it.leftUuid }, set = { b, v -> b.copy(leftUuid = v) })
    val content: Lens<Block, String> = Lens(get = { it.content }, set = { b, v -> b.copy(content = v) })
    val level: Lens<Block, Int> = Lens(get = { it.level }, set = { b, v -> b.copy(level = v) })
    val position: Lens<Block, Int> = Lens(get = { it.position }, set = { b, v -> b.copy(position = v) })
    val createdAt: Lens<Block, Instant> = Lens(get = { it.createdAt }, set = { b, v -> b.copy(createdAt = v) })
    val updatedAt: Lens<Block, Instant> = Lens(get = { it.updatedAt }, set = { b, v -> b.copy(updatedAt = v) })
    val version: Lens<Block, Long> = Lens(get = { it.version }, set = { b, v -> b.copy(version = v) })
    val properties: Lens<Block, Map<String, String>> = Lens(get = { it.properties }, set = { b, v -> b.copy(properties = v) })
    val isLoaded: Lens<Block, Boolean> = Lens(get = { it.isLoaded }, set = { b, v -> b.copy(isLoaded = v) })
    val contentHash: Lens<Block, String?> = Lens(get = { it.contentHash }, set = { b, v -> b.copy(contentHash = v) })
    val blockType: Lens<Block, String> = Lens(get = { it.blockType }, set = { b, v -> b.copy(blockType = v) })
}

object PropertyOptics {
    val uuid: Lens<Property, String> = Lens(get = { it.uuid }, set = { p, v -> p.copy(uuid = v) })
    val blockUuid: Lens<Property, String> = Lens(get = { it.blockUuid }, set = { p, v -> p.copy(blockUuid = v) })
    val key: Lens<Property, String> = Lens(get = { it.key }, set = { p, v -> p.copy(key = v) })
    val value: Lens<Property, String> = Lens(get = { it.value }, set = { p, v -> p.copy(value = v) })
    val createdAt: Lens<Property, Instant> = Lens(get = { it.createdAt }, set = { p, v -> p.copy(createdAt = v) })
}
