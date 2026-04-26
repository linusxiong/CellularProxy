package com.cellularproxy.app.config

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AndroidKeystoreSensitiveValueCipherTest {
    @Test
    fun `encrypt returns versioned ciphertext without plaintext and decrypt restores the value`() {
        val cipher = AndroidKeystoreSensitiveValueCipher.forTesting(FixedSecretKeyProvider())

        val encrypted = cipher.encrypt("management-token")

        assertFalse("management-token" in encrypted)
        assertFalse(encrypted.contains("\n"))
        assertEquals("management-token", cipher.decrypt(encrypted))
    }

    @Test
    fun `encrypt uses a fresh iv for each value`() {
        val cipher = AndroidKeystoreSensitiveValueCipher.forTesting(FixedSecretKeyProvider())

        val first = cipher.encrypt("management-token")
        val second = cipher.encrypt("management-token")

        assertFalse(first == second)
        assertEquals("management-token", cipher.decrypt(first))
        assertEquals("management-token", cipher.decrypt(second))
    }

    @Test
    fun `decrypt rejects malformed or tampered values`() {
        val cipher = AndroidKeystoreSensitiveValueCipher.forTesting(FixedSecretKeyProvider())
        val encrypted = cipher.encrypt("management-token")
        val tampered = encrypted.replaceAfterLast(":", "AAAA")

        listOf(
            "",
            "not-versioned",
            "v2:abc:def",
            "v1:not-base64:def",
            "v1:abc:not-base64",
            tampered,
        ).forEach { value ->
            assertNull(cipher.decrypt(value), "Expected malformed value to be rejected: $value")
        }
    }

    @Test
    fun `create rejects blank key aliases before first use`() {
        assertFailsWith<IllegalArgumentException> {
            AndroidKeystoreSensitiveValueCipher.create(keyAlias = " ")
        }
    }
}

private class FixedSecretKeyProvider : AndroidKeystoreSensitiveValueCipher.SecretKeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    override fun getOrCreateKey(): SecretKey = key
}
