package net.ssync.player

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * numbers as the server can send them: an int, a float (sqlite keeps what a
 * scanner wrote, 215000.5 in an INTEGER column), or a string ("320kbps").
 * one odd row used to fail a whole list (the screen showed an error).
 * anything that isn't a number reads as null.
 */
private fun lenientNumber(decoder: Decoder): Double? {
    val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
    if (element is JsonNull || element !is JsonPrimitive) return null
    val text = element.content.trim()
    text.toDoubleOrNull()?.let { return it }
    // "320kbps", "1,411 kbps": the leading number
    val digits = Regex("""^-?\d[\d,]*(\.\d+)?""").find(text)?.value?.replace(",", "")
    return digits?.toDoubleOrNull()
}

object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? = lenientNumber(decoder)?.toInt()
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

object LenientLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long? = lenientNumber(decoder)?.toLong()
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}
