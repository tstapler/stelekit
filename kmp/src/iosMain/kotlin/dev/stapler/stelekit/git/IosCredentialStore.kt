// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
actual class CredentialStore actual constructor() {

    private val service = "dev.stapler.stelekit.credentials"

    actual fun store(key: String, value: String) {
        val valueBytes = value.encodeToByteArray()
        val valueData = valueBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), valueBytes.size.toULong())
        }

        val deleteQuery = baseQuery(key)
        @Suppress("UNCHECKED_CAST")
        SecItemDelete(deleteQuery as platform.CoreFoundation.CFDictionaryRef)

        val addQuery = NSMutableDictionary(dictionary = deleteQuery)
        @Suppress("UNCHECKED_CAST")
        addQuery[kSecValueData as Any] = valueData
        @Suppress("UNCHECKED_CAST")
        SecItemAdd(addQuery as platform.CoreFoundation.CFDictionaryRef, null)
    }

    actual fun retrieve(key: String): String? = memScoped {
        val query = NSMutableDictionary(dictionary = baseQuery(key))
        @Suppress("UNCHECKED_CAST")
        query[kSecReturnData as Any] = true
        @Suppress("UNCHECKED_CAST")
        query[kSecMatchLimit as Any] = kSecMatchLimitOne

        val result = alloc<CFTypeRefVar>()
        @Suppress("UNCHECKED_CAST")
        val status = SecItemCopyMatching(query as platform.CoreFoundation.CFDictionaryRef, result.ptr)
        if (status != errSecSuccess) return@memScoped null

        val data = result.value as? NSData ?: return@memScoped null
        NSString.create(data, NSUTF8StringEncoding) as? String
    }

    actual fun delete(key: String) {
        @Suppress("UNCHECKED_CAST")
        SecItemDelete(baseQuery(key) as platform.CoreFoundation.CFDictionaryRef)
    }

    private fun baseQuery(key: String): NSMutableDictionary = NSMutableDictionary().apply {
        @Suppress("UNCHECKED_CAST")
        this[kSecClass as Any] = kSecClassGenericPassword
        @Suppress("UNCHECKED_CAST")
        this[kSecAttrService as Any] = service
        @Suppress("UNCHECKED_CAST")
        this[kSecAttrAccount as Any] = key
    }
}
