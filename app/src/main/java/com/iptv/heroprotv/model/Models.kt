package com.iptv.heroprotv.model

enum class ContentType {
    LIVE, VOD, SERIES, SERIES_EPISODE
}

data class ChannelItem(
    val name: String,
    val group: String,
    val url: String?,
    val type: ContentType,
    val id: String? = null
)

data class PlaylistData(
    val live: List<ChannelItem> = emptyList(),
    val vod: List<ChannelItem> = emptyList(),
    val series: List<ChannelItem> = emptyList(),
    val sourceType: String = "xtream",
    val server: String? = null,
    val user: String? = null,
    val pass: String? = null
)

data class ContentCounts(
    val live: Int = 0,
    val vod: Int = 0,
    val series: Int = 0
)

data class UserProfile(
    val id: String,
    val hero: String,
    val universe: String, // "marvel" or "dc"
    val sourceType: String, // "xtream", "m3u", "txt"
    val sourceLabel: String,
    val server: String? = null,
    val user: String? = null,
    val pass: String? = null,
    val validity: String? = null,
    val maxConnections: Int? = null,
    var activeConnections: Int = 0,
    var contentCounts: ContentCounts = ContentCounts(),
    val bulkOrigin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var lastUsed: Long = System.currentTimeMillis()
)

data class EpisodeItem(
    val id: String,
    val episodeNum: String,
    val title: String,
    val containerExtension: String,
    val duration: String?
)

data class XtreamCredentials(
    val server: String,
    val username: String,
    val password: String
)

data class BulkItemResult(
    val ok: Boolean,
    val hero: String = "",
    val type: String = "",
    val count: Int = 0,
    val error: String = ""
)
