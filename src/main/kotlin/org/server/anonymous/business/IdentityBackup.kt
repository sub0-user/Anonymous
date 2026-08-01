package org.server.anonymous.business

import java.io.ByteArrayInputStream
import java.security.spec.KeySpec
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-protected identity backup: the Ed25519 seed is the only secret that matters
 * (keys and the onion address are derived from it), so backup = encrypted seed. Wrong
 * passphrase or tampered data fails the AES-GCM tag and is rejected.
 */
object IdentityBackup {
    private const val HEADER = "anonymous-identity-v1"
    private const val ITERATIONS = 200_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val VERSION = "1"

    /** Returns the backup file contents (UTF-8 properties). */
    fun export(
        seed: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val salt = SessionCrypto.randomBytes(SALT_LENGTH)
        val iv = SessionCrypto.randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val plaintext = "$HEADER\n".toByteArray(Charsets.UTF_8) + seed
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return buildProperties(salt, iv, cipher.doFinal(plaintext)).toByteArray(Charsets.UTF_8)
    }

    /** Returns the seed, or throws when the passphrase is wrong or the data is tampered. */
    fun import(
        data: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val props =
            Properties().apply {
                ByteArrayInputStream(data).use { load(it) }
            }
        val salt = decode(props.getProperty("salt") ?: invalid())
        val iv = decode(props.getProperty("iv") ?: invalid())
        val ciphertext = decode(props.getProperty("ciphertext") ?: invalid())
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext) // AEADBadTagException on wrong passphrase/tamper
        check(plaintext.size > HEADER.length + 1) { "backup is not a valid Anonymous identity" }
        val header = plaintext.copyOfRange(0, HEADER.length).toString(Charsets.UTF_8)
        check(header == HEADER) { "backup is not a valid Anonymous identity" }
        return plaintext.copyOfRange(HEADER.length + 1, plaintext.size)
    }

    private fun invalid(): Nothing = error("backup is not a valid Anonymous identity")

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec: KeySpec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun buildProperties(
        salt: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): String {
        val encoder = Base64.getEncoder()
        return buildString {
            appendLine("anonymous.backup.version=$VERSION")
            appendLine("kdf=pbkdf2-hmac-sha256")
            appendLine("kdf.iterations=$ITERATIONS")
            appendLine("salt=${encoder.encodeToString(salt)}")
            appendLine("iv=${encoder.encodeToString(iv)}")
            append("ciphertext=${encoder.encodeToString(ciphertext)}")
        }
    }
}
