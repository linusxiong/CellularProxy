package com.cellularproxy.app.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSensitiveValueCipher private constructor(
    private val keyProvider: SecretKeyProvider,
    private val cipherFactory: AesGcmCipherFactory,
) : SensitiveValueCipher {
    override fun encrypt(plainText: String): String {
        val cipher = cipherFactory.encryptCipher(keyProvider.getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(
            FORMAT_VERSION,
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(cipherText),
        ).joinToString(separator = ":")
    }

    override fun decrypt(encryptedValue: String): String? {
        val parts = encryptedValue.split(":")
        if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
            return null
        }

        return try {
            val iv = Base64.getDecoder().decode(parts[1])
            val cipherText = Base64.getDecoder().decode(parts[2])
            val cipher =
                cipherFactory.decryptCipher(
                    key = keyProvider.getOrCreateKey(),
                    iv = iv,
                )
            cipher.doFinal(cipherText).decodeStrictUtf8()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    interface SecretKeyProvider {
        fun getOrCreateKey(): SecretKey
    }

    internal interface AesGcmCipherFactory {
        fun encryptCipher(key: SecretKey): Cipher

        fun decryptCipher(
            key: SecretKey,
            iv: ByteArray,
        ): Cipher
    }

    companion object {
        private const val FORMAT_VERSION = "v1"
        private const val DEFAULT_KEY_ALIAS = "cellularproxy_sensitive_config"

        fun create(keyAlias: String = DEFAULT_KEY_ALIAS): AndroidKeystoreSensitiveValueCipher {
            require(keyAlias.isNotBlank()) { "Android Keystore key alias must not be blank" }
            return AndroidKeystoreSensitiveValueCipher(
                keyProvider = AndroidKeystoreSecretKeyProvider(keyAlias),
                cipherFactory = JcaAesGcmCipherFactory,
            )
        }

        internal fun forTesting(
            keyProvider: SecretKeyProvider,
            cipherFactory: AesGcmCipherFactory = JcaAesGcmCipherFactory,
        ): AndroidKeystoreSensitiveValueCipher =
            AndroidKeystoreSensitiveValueCipher(
                keyProvider = keyProvider,
                cipherFactory = cipherFactory,
            )
    }
}

private object JcaAesGcmCipherFactory : AndroidKeystoreSensitiveValueCipher.AesGcmCipherFactory {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    override fun encryptCipher(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }

    override fun decryptCipher(
        key: SecretKey,
        iv: ByteArray,
    ): Cipher {
        if (iv.isEmpty()) {
            throw IllegalArgumentException("Cipher IV must not be empty")
        }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }
}

private class AndroidKeystoreSecretKeyProvider(
    private val keyAlias: String,
) : AndroidKeystoreSensitiveValueCipher.SecretKeyProvider {
    override fun getOrCreateKey(): SecretKey {
        require(keyAlias.isNotBlank()) { "Android Keystore key alias must not be blank" }

        val keyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
                load(null)
            }
        val existingKey = keyStore.getKey(keyAlias, null)
        if (existingKey is SecretKey) {
            return existingKey
        }

        return KeyGenerator
            .getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER,
            ).apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

private fun ByteArray.decodeStrictUtf8(): String {
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(this)).toString()
}
