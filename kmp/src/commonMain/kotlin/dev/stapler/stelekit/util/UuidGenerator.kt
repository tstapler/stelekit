package dev.stapler.stelekit.util

import kotlin.time.Clock
import kotlin.random.Random

object UuidGenerator {
    
    /**
     * Generates a UUID v7 (Time-ordered).
     * Format: 8-4-4-4-12 hex string (36 chars including hyphens).
     * 
     * Layout (approximate for KMP implementation without native lib):
     * - 48 bits: Timestamp (milliseconds)
     * - 12 bits: Version (7) + Random sequence
     * - 62 bits: Random variant + Random bits
     */
    fun generateV7(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        
        // 48 bits for timestamp
        val timeHigh = (timestamp ushr 16).toInt()
        val timeLow = (timestamp and 0xFFFF).toInt()
        
        // 16 bits: 4 bits version (7) + 12 bits random
        val versionAndRandom = (7 shl 12) or (Random.nextInt(0xFFF + 1))
        
        // 16 bits: 2 bits variant (2) + 14 bits random
        // Variant 10xx (RFC 4122) -> 0x8000 to 0xBFFF
        // We use 0x8000 (1000...) base + random 14 bits
        val variantAndRandom = 0x8000 or (Random.nextInt(0x3FFF + 1))
        
        // Remaining 48 bits random
        // We can construct this from two random integers (32 bits), masking accordingly
        val nodeHigh = Random.nextInt() and 0xFFFF
        val nodeLow = Random.nextInt()
        
        // Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        // TimeHigh (8 chars)
        // TimeLow (4 chars)
        // Version (4 chars)
        // Variant (4 chars)
        // Node (12 chars)
        
        return buildString {
            append(timeHigh.toHexString(8))
            append('-')
            append(timeLow.toHexString(4))
            append('-')
            append(versionAndRandom.toHexString(4))
            append('-')
            append(variantAndRandom.toHexString(4))
            append('-')
            append(nodeHigh.toHexString(4))
            append(nodeLow.toHexString(8))
        }
    }
    
    /**
     * Generates a deterministic UUID based on inputs.
     * Useful for recovering block IDs from file path + content/position when not persisted.
     * 
     * Uses a simple hash-based approach since we don't have SHA-1/MD5 in stdlib KMP easily 
     * without adding heavy crypto deps. 
     * For stability across sessions, a good hash of the stable content is "good enough" 
     * to prevent ID churn on reload, provided collisions are low.
     * 
     * Format: "00000004-" + hash segments (mimicking Logseq's built-in block UUID style)
     */
    fun generateDeterministic(seed: String): String {
        val hash = seed.hashCode().toLong() // Int hash code
        // We need more bits for a good UUID.
        // Let's use a custom 64-bit hash or just mix the string better.
        // Actually, FNV-1a is easy to implement.
        
        val hash64 = fnv1a64(seed)
        val hash64b = fnv1a64(seed.reversed()) // Quick way to get more bits
        
        // Use Logseq's "builtin" prefix style for determinism?
        // Clojure implementation uses "00000004" prefix for builtin blocks.
        // We can use a specific prefix to indicate "Generated from content".
        // Let's use "00000000" or similar to distinguish.
        
        val part1 = (hash64 ushr 32).toInt().toHexString(8)
        val part2 = (hash64 and 0xFFFFFFFF).toInt().toHexString(8)
        val part3 = (hash64b ushr 32).toInt().toHexString(8)
        val part4 = (hash64b and 0xFFFFFFFF).toInt().toHexString(8)
        
        // Format: 8-4-4-4-12
        // We have 32 chars of hex.
        // part1 (8)
        // part2.subs(0,4)
        // part2.subs(4,8)
        // part3.subs(0,4)
        // part3.subs(4,8) + part4 (8) = 12 chars
        
        val fullHex = part1 + part2 + part3 + part4
        // Ensure 32 chars
        val padded = fullHex.padEnd(32, '0')
        
        return buildString {
            append(padded.substring(0, 8))
            append('-')
            append(padded.substring(8, 12))
            append('-')
            append(padded.substring(12, 16))
            append('-')
            append(padded.substring(16, 20))
            append('-')
            append(padded.substring(20, 32))
        }
    }
    
    private fun fnv1a64(data: String): Long {
        var hash: Long = -3750763034362895579L // FNV offset basis
        for (char in data) {
            hash = hash xor char.code.toLong()
            hash = hash * 1099511628211L // FNV prime
        }
        return hash
    }
    
    private fun Int.toHexString(length: Int): String {
        // Kotlin 1.9+ has toHexString but we might be on older?
        // Manual implementation for safety in KMP
        val hex = this.toUInt().toString(16)
        return if (hex.length < length) {
            "0".repeat(length - hex.length) + hex
        } else {
            hex.substring(hex.length - length)
        }
    }
    
    private fun Long.toHexString(length: Int): String {
        val hex = this.toULong().toString(16)
        return if (hex.length < length) {
            "0".repeat(length - hex.length) + hex
        } else {
            hex.substring(hex.length - length)
        }
    }
}
