package dev.stapler.stelekit.repository

/**
 * Composite repository interface for all block operations.
 * Implementations satisfy all four role interfaces automatically.
 *
 * Prefer the narrower role interfaces for consumer dependencies:
 *   [BlockReadRepository]      — reads and cache invalidation
 *   [BlockWriteRepository]     — content writes and deletes
 *   [BlockStructureRepository] — indent / outdent / move
 *   [BlockSearchRepository]    — search and backlink queries
 */
interface BlockRepository : BlockReadRepository, BlockWriteRepository, BlockStructureRepository, BlockSearchRepository
