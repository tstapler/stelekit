// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

/**
 * Persistent 32-way hash trie keyed by string (ADR-002 "Spike 0.1.6 decision": about 1 us and 0.6 KB retained per
 * snapshot at 5,000 elements, against 180 us and 190 KB for a `HashMap` copy). Every update path-copies at most
 * [LEVELS] nodes and shares the rest, so an undo stack of scene snapshots stays small. No dependency (ADR-007).
 *
 * Keys that agree on all [LEVELS] hash chunks chain in the leaf. String hash codes are specified, so layout and
 * iteration order are the same on every target (iteration order is NOT insertion order; callers keep a z-order list).
 */
class PersistentHashTrie<V> private constructor(private val root: Array<Any?>, val size: Int) {
    private class Leaf<V>(val key: String, val value: V, val next: Leaf<V>?)

    fun get(key: String): V? {
        var node = root
        var shift = 0
        while (true) {
            when (val slot = node[chunk(key.hashCode(), shift)]) {
                null -> return null
                is Leaf<*> -> return findInChain(slot, key)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    node = slot as Array<Any?>
                    shift += BITS
                }
            }
        }
    }

    fun containsKey(key: String): Boolean = get(key) != null

    fun put(key: String, value: V): PersistentHashTrie<V> {
        val added = BooleanArray(1)
        val newRoot = putInto(root, key, value, key.hashCode(), 0, added)
        return PersistentHashTrie(newRoot, if (added[0]) size + 1 else size)
    }

    fun remove(key: String): PersistentHashTrie<V> {
        if (get(key) == null) return this
        val newRoot = removeFrom(root, key, key.hashCode(), 0) ?: arrayOfNulls(WIDTH)
        return PersistentHashTrie(newRoot, size - 1)
    }

    /** Every entry, in trie order. */
    fun entries(): Sequence<Pair<String, V>> = sequence { yieldAll(walk(root)) }

    private fun walk(node: Array<Any?>): Sequence<Pair<String, V>> = sequence {
        for (slot in node) {
            when (slot) {
                null -> Unit
                is Leaf<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    var leaf: Leaf<V>? = slot as Leaf<V>
                    while (leaf != null) {
                        yield(leaf.key to leaf.value)
                        leaf = leaf.next
                    }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    yieldAll(walk(slot as Array<Any?>))
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun putInto(node: Array<Any?>, key: String, value: V, hash: Int, shift: Int, added: BooleanArray): Array<Any?> {
        val copy = node.copyOf()
        val idx = chunk(hash, shift)
        copy[idx] = when (val slot = node[idx]) {
            null -> {
                added[0] = true
                Leaf(key, value, null)
            }
            is Leaf<*> -> putIntoLeaf(slot as Leaf<V>, key, value, hash, shift, added)
            else -> putInto(slot as Array<Any?>, key, value, hash, shift + BITS, added)
        }
        return copy
    }

    private fun putIntoLeaf(leaf: Leaf<V>, key: String, value: V, hash: Int, shift: Int, added: BooleanArray): Any {
        if (leaf.key == key) return Leaf(key, value, leaf.next)
        if (shift + BITS >= HASH_BITS) return chainPut(leaf, key, value, added)
        val child = arrayOfNulls<Any?>(WIDTH)
        child[chunk(leaf.key.hashCode(), shift + BITS)] = Leaf(leaf.key, leaf.value, null)
        return putInto(child, key, value, hash, shift + BITS, added)
    }

    private fun chainPut(head: Leaf<V>, key: String, value: V, added: BooleanArray): Leaf<V> {
        var cur: Leaf<V>? = head
        while (cur != null) {
            if (cur.key == key) return replaceInChain(head, key, value)
            cur = cur.next
        }
        added[0] = true
        return Leaf(key, value, head)
    }

    private fun replaceInChain(head: Leaf<V>, key: String, value: V): Leaf<V> =
        if (head.key == key) Leaf(key, value, head.next) else Leaf(head.key, head.value, replaceInChain(head.next!!, key, value))

    @Suppress("UNCHECKED_CAST")
    private fun removeFrom(node: Array<Any?>, key: String, hash: Int, shift: Int): Array<Any?>? {
        val idx = chunk(hash, shift)
        val copy = node.copyOf()
        when (val slot = node[idx]) {
            null -> return node
            is Leaf<*> -> copy[idx] = removeFromChain(slot as Leaf<V>, key)
            else -> copy[idx] = removeFrom(slot as Array<Any?>, key, hash, shift + BITS)
        }
        return if (copy.all { it == null }) null else copy
    }

    private fun removeFromChain(head: Leaf<V>, key: String): Leaf<V>? = when {
        head.key == key -> head.next
        head.next == null -> head
        else -> Leaf(head.key, head.value, removeFromChain(head.next, key))
    }

    private fun findInChain(leaf: Any, key: String): V? {
        @Suppress("UNCHECKED_CAST")
        var cur: Leaf<V>? = leaf as Leaf<V>
        while (cur != null) {
            if (cur.key == key) return cur.value
            cur = cur.next
        }
        return null
    }

    companion object {
        private const val BITS = 5
        private const val WIDTH = 1 shl BITS
        private const val HASH_BITS = 32

        private fun chunk(hash: Int, shift: Int): Int = (hash ushr shift) and (WIDTH - 1)

        fun <V> empty(): PersistentHashTrie<V> = PersistentHashTrie(arrayOfNulls(WIDTH), 0)
    }
}
