package net.ssync.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ServerVault {
    suspend fun load(): PairedServerRecord?
    suspend fun save(record: PairedServerRecord)
    suspend fun clear()
}

/**
 * Android Keystore (`AES-256-GCM`) encrypted file vault with atomic temp-file rename.
 *
 * Stores all sensitive pairing material (`lookup_id`, `server_pubkey`, `cert_fingerprint_sha256`,
 * `candidate_addresses`, `last_working_base_url`, `device_token`) encrypted at rest;
 * never writes plaintext SharedPreferences.
 */
class KeystoreServerVault(
    private val vaultFile: File,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ServerVault {

    constructor(context: Context) : this(
        vaultFile = File(context.filesDir, DEFAULT_FILE_NAME),
    )

    override suspend fun load(): PairedServerRecord? = withContext(Dispatchers.IO) {
        if (!vaultFile.exists()) return@withContext null
        val blob = vaultFile.readBytes()
        if (blob.size <= 4 + GCM_IV_BYTES) return@withContext null

        val buffer = ByteBuffer.wrap(blob)
        val version = buffer.int
        if (version != FORMAT_VERSION) return@withContext null

        val iv = ByteArray(GCM_IV_BYTES)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        json.decodeFromString<PairedServerRecord>(plaintext.toString(Charsets.UTF_8))
    }

    override suspend fun save(record: PairedServerRecord) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(PairedServerRecord.serializer(), record)
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)

        val out = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(FORMAT_VERSION)
            .put(iv)
            .put(ciphertext)
            .array()

        vaultFile.parentFile?.mkdirs()
        val tmp = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(vaultFile)) {
            vaultFile.writeBytes(out)
            tmp.delete()
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        if (vaultFile.exists()) {
            vaultFile.delete()
        }
    }

    override fun toString(): String = "KeystoreServerVault(file=${vaultFile.name}, keyAlias=$keyAlias)"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "soulsync_server_vault_aes256_gcm_v1"
        private const val DEFAULT_FILE_NAME = "soulsync_vault.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1
    }
}
