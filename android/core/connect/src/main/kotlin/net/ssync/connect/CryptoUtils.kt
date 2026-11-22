package net.ssync.connect

import java.security.MessageDigest
import java.util.Base64

object CryptoUtils {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun sha256Hex(input: ByteArray): String = toLowerHex(sha256(input))

    fun sha256Hex(utf8: String): String = sha256Hex(utf8.toByteArray(Charsets.UTF_8))

    fun toLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex char: '$c'")
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun base64UrlEncodeNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecodeNoPadding(encoded: String): ByteArray =
        Base64.getUrlDecoder().decode(encoded)
}
