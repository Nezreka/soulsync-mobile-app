package net.ssync.player

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// the offline downloads contract (server: /api/player/offline/*)

/** an id the server may send as a number or a string; kept as a string */
object FlexIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexId", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            val el = decoder.decodeJsonElement()
            return (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
        }
        return decoder.decodeString()
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** a size or count that may come as an int, a float or a string */
object FlexLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return 0L
            if (p is JsonNull) return 0L
            return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: 0L
        }
        return decoder.decodeLong()
    }
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

object FlexDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double {
        if (decoder is JsonDecoder) {
            val p = decoder.decodeJsonElement() as? JsonPrimitive ?: return 0.0
            if (p is JsonNull) return 0.0
            return p.content.toDoubleOrNull() ?: 0.0
        }
        return decoder.decodeDouble()
    }
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

@Serializable
data class OfflineSource(
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @Serializable(with = LenientIntSerializer::class) val height: Int? = null,
    val hdr: Boolean = false,
    @SerialName("audio_codec") val audioCodec: String? = null,
    @SerialName("bitrate_kbps") @Serializable(with = LenientIntSerializer::class) val bitrateKbps: Int? = null,
)

@Serializable
data class OfflinePlanItem(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") @Serializable(with = FlexIdSerializer::class) val itemId: String,
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    @SerialName("duration_s") @Serializable(with = FlexDoubleSerializer::class) val durationS: Double = 0.0,
    @SerialName("size_bytes") @Serializable(with = FlexLongSerializer::class) val sizeBytes: Long = 0L,
    val source: OfflineSource? = null,
    @SerialName("on_device") val onDevice: Boolean = false,
    /** the asked-about device's state for it (queued, downloading...), null when it has none */
    @SerialName("device_state") val deviceState: String? = null,
    val watched: Boolean = false,
    @SerialName("position_s") @Serializable(with = FlexDoubleSerializer::class) val positionS: Double = 0.0,
) {
    val key: String get() = offlineKey(itemType, itemId)
}

@Serializable
data class OfflineQuality(
    val id: String,
    val label: String = "",
    val sub: String = "",
    @SerialName("total_bytes") @Serializable(with = FlexLongSerializer::class) val totalBytes: Long = 0L,
    @SerialName("per_item_bytes") val perItemBytes: Map<String, @Serializable(with = FlexLongSerializer::class) Long> = emptyMap(),
)

@Serializable
data class OfflinePlan(
    val kind: String = "video",
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    val items: List<OfflinePlanItem> = emptyList(),
    val qualities: List<OfflineQuality> = emptyList(),
    /** true when this profile only gets originals (converted copies are off for members) */
    @SerialName("conversion_off") val conversionOff: Boolean = false,
    /** the device's current smart rule for a show / playlist / podcast */
    val rule: OfflineRule? = null,
    @SerialName("free_bytes") @Serializable(with = FlexLongSerializer::class) val freeBytes: Long = 0L,
    @SerialName("limit_bytes") @Serializable(with = FlexLongSerializer::class) val limitBytes: Long = 0L,
) {
    val isVideo: Boolean get() = kind == "video"
}

@Serializable
data class OfflinePrepare(
    @Serializable(with = FlexIdSerializer::class) val id: String = "",
    val status: String = "failed",
    @Serializable(with = FlexDoubleSerializer::class) val progress: Double = 0.0,
    val url: String? = null,
    @SerialName("size_bytes") @Serializable(with = FlexLongSerializer::class) val sizeBytes: Long = 0L,
    val error: String? = null,
    val message: String? = null,
    val filename: String? = null,
) {
    /** what to show when it failed: the server's words over its code */
    val problem: String? get() = message?.takeIf { it.isNotBlank() } ?: error?.takeIf { it.isNotBlank() }
}

@Serializable
data class OfflineWantedAdd(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") @Serializable(with = FlexIdSerializer::class) val itemId: String,
    val quality: String = "original",
    val reason: String = "wish",
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    val group: JsonElement? = null,
) {
    val key: String get() = offlineKey(itemType, itemId)
    fun groupRef(): OfflineGroupRef? = parseGroupRef(group)
}

@Serializable
data class OfflineWantedRemove(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") @Serializable(with = FlexIdSerializer::class) val itemId: String,
    val reason: String = "wish",
) {
    val key: String get() = offlineKey(itemType, itemId)
}

@Serializable
data class OfflineWanted(
    val add: List<OfflineWantedAdd> = emptyList(),
    val remove: List<OfflineWantedRemove> = emptyList(),
)

@Serializable
data class OfflineReportItem(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: String,
    val quality: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val state: String,
    val progress: Double,
)

@Serializable
data class OfflineDeviceReport(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android",
    @SerialName("free_bytes") val freeBytes: Long,
    @SerialName("limit_bytes") val limitBytes: Long,
    @SerialName("used_bytes") val usedBytes: Long,
    @SerialName("wifi_only") val wifiOnly: Boolean,
    val items: List<OfflineReportItem>,
)

/** a smart rule. in POST bodies `id` is the target (show/playlist/podcast); the rule's own id is `rule_id` */
@Serializable
data class OfflineRule(
    @SerialName("rule_id") @Serializable(with = FlexIdSerializer::class) val ruleId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val type: String = "",
    @Serializable(with = FlexIdSerializer::class) val id: String = "",
    val quality: String = "original",
    @SerialName("keep_next") @Serializable(with = LenientIntSerializer::class) val keepNext: Int? = null,
    @SerialName("delete_watched") val deleteWatched: Boolean = false,
    val follow: Boolean = false,
    val title: String? = null,
    val artwork: String? = null,
)

@Serializable
data class OfflineDeviceItem(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") @Serializable(with = FlexIdSerializer::class) val itemId: String,
    val quality: String = "original",
    @SerialName("size_bytes") @Serializable(with = FlexLongSerializer::class) val sizeBytes: Long = 0L,
    val state: String = "downloaded",
    @Serializable(with = FlexDoubleSerializer::class) val progress: Double = 0.0,
    val title: String? = null,
    val subtitle: String? = null,
    val artwork: String? = null,
    /** show/season/album/playlist the item belongs to: a string or an object */
    val group: JsonElement? = null,
    val watched: Boolean = false,
    @SerialName("position_s") @Serializable(with = FlexDoubleSerializer::class) val positionS: Double = 0.0,
    @SerialName("duration_s") @Serializable(with = FlexDoubleSerializer::class) val durationS: Double = 0.0,
    val kind: String? = null,
) {
    val key: String get() = offlineKey(itemType, itemId)

    /** the group as (type, id, title), whatever shape the server sent */
    fun groupRef(): OfflineGroupRef? = parseGroupRef(group)
}

data class OfflineGroupRef(
    val type: String,
    val id: String,
    val title: String,
    val artwork: String? = null,
    val subtitle: String? = null,
) {
    /**
     * how the phone files it: a season's episodes sit with their show
     * (the server says season, id "<show_id>:<season>", title the show)
     */
    fun normalized(): OfflineGroupRef =
        if (type == "season") copy(type = "show", id = id.substringBefore(':')) else this

    /** the season number of a season group, else null */
    val seasonNumber: Int? get() = if (type == "season") id.substringAfter(':', "").toIntOrNull() else null
}

fun parseGroupRef(g: JsonElement?): OfflineGroupRef? {
    if (g == null || g is JsonNull) return null
    if (g is JsonPrimitive) {
        val s = g.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return OfflineGroupRef(type = "", id = s, title = s)
    }
    val o = g as? JsonObject ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    val id = str("id") ?: return null
    return OfflineGroupRef(
        type = str("type").orEmpty(),
        id = id,
        title = str("title") ?: str("name").orEmpty(),
        artwork = str("artwork"),
        subtitle = str("subtitle"),
    )
}

@Serializable
data class OfflineDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "",
    val platform: String = "android",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("free_bytes") @Serializable(with = FlexLongSerializer::class) val freeBytes: Long = 0L,
    @SerialName("limit_bytes") @Serializable(with = FlexLongSerializer::class) val limitBytes: Long = 0L,
    @SerialName("used_bytes") @Serializable(with = FlexLongSerializer::class) val usedBytes: Long = 0L,
    @SerialName("wifi_only") val wifiOnly: Boolean = true,
    val items: List<OfflineDeviceItem> = emptyList(),
    val rules: List<OfflineRule> = emptyList(),
    /** adds and removes sent from the web or a tv that the device hasn't done yet */
    val wishes: List<OfflineWish> = emptyList(),
)

@Serializable
data class OfflineWish(
    @SerialName("wish_id") @Serializable(with = FlexIdSerializer::class) val wishId: String = "",
    val action: String = "add",
    val type: String = "",
    @Serializable(with = FlexIdSerializer::class) val id: String = "",
    val quality: String = "original",
    val title: String? = null,
    val subtitle: String? = null,
    val artwork: String? = null,
)

@Serializable
data class OfflineDevices(val devices: List<OfflineDevice> = emptyList())

/** the admin's download settings (GET/PUT /api/player/offline/settings) */
@Serializable
data class OfflineAdminSettings(
    @SerialName("allow_conversion_for_members") val allowConversionForMembers: Boolean = true,
    @SerialName("max_conversions") @Serializable(with = LenientIntSerializer::class) val maxConversions: Int? = 1,
    @SerialName("cache_gb") @Serializable(with = LenientIntSerializer::class) val cacheGb: Int? = 50,
)

@Serializable
data class OfflineSettingsInfo(
    val settings: OfflineAdminSettings = OfflineAdminSettings(),
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("allow_downloads") val allowDownloads: Boolean = true,
    @SerialName("can_convert") val canConvert: Boolean = true,
    @SerialName("cache_used_bytes") @Serializable(with = FlexLongSerializer::class) val cacheUsedBytes: Long = 0L,
)

/** a play, position or watched mark made offline (route 9) */
@Serializable
data class OfflineSyncEvent(
    val kind: String, // progress | watched | play
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("position_s") val positionS: Double = 0.0,
    @SerialName("duration_s") val durationS: Double = 0.0,
    val at: String,
)

/** a wish or rule change made offline, sent when back */
@Serializable
data class OfflinePendingOp(
    val op: String, // wish_remove | rule_delete
    val type: String = "",
    val id: String = "",
    val quality: String = "original",
)

fun offlineKey(itemType: String, itemId: String): String = "${itemType.lowercase()}:$itemId"

val OFFLINE_VIDEO_KINDS: Set<String> = setOf("movie", "episode", "youtube", "home")
val OFFLINE_AUDIO_KINDS: Set<String> = setOf("track", "audiobook", "podcast_episode")
