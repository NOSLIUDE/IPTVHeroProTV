package com.iptv.heroprotv.parser

import com.iptv.heroprotv.model.ChannelItem
import com.iptv.heroprotv.model.ContentType
import com.iptv.heroprotv.model.XtreamCredentials
import java.security.MessageDigest

object PlaylistParser {

    private val extinfNameRegex = Regex(",(.*)$")
    private val extinfGroupRegex = Regex("group-title="([^"]*)"")
    private val xtreamPatterns = listOf(
        Regex("https?://[^\s]+/get\.php\?[^\s]*username=([^&\s]+)[^\s]*password=([^&\s]+)", RegexOption.IGNORE_CASE),
        Regex("https?://[^\s]+/player_api\.php\?[^\s]*username=([^&\s]+)[^\s]*password=([^&\s]+)", RegexOption.IGNORE_CASE)
    )
    private val hostRegex = Regex("(https?://[^/\s]+)", RegexOption.IGNORE_CASE)

    fun parseM3U(text: String): List<ChannelItem> {
        val lines = text.split(Regex("\r?\n"))
        val channels = mutableListOf<ChannelItem>()
        var currentName: String? = null
        var currentGroup: String = "Geral"

        for (rawLine in lines) {
            val l = rawLine.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF:")) {
                val nMatch = extinfNameRegex.find(l)
                val gMatch = extinfGroupRegex.find(l)
                currentName = nMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Canal"
                currentGroup = gMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "Geral"
            } else if (!l.startsWith("#") && currentName != null) {
                val type = detectContentTypeFromUrl(l, currentGroup)
                channels.add(
                    ChannelItem(
                        name = currentName,
                        group = currentGroup,
                        url = l,
                        type = type
                    )
                )
                currentName = null
                currentGroup = "Geral"
            }
        }
        return channels
    }

    private fun detectContentTypeFromUrl(url: String, group: String): ContentType {
        val lowerUrl = url.lowercase()
        val lowerGroup = group.lowercase()
        return when {
            lowerUrl.contains("/series/") || lowerGroup.contains("série") || lowerGroup.contains("series") -> ContentType.VOD
            lowerUrl.contains("/movie/") || lowerGroup.contains("filme") || lowerGroup.contains("vod") -> ContentType.VOD
            else -> ContentType.LIVE
        }
    }

    fun parseTXT(text: String): List<ChannelItem> {
        val lines = text.split(Regex("\r?\n"))
        val channels = mutableListOf<ChannelItem>()
        for (rawLine in lines) {
            val l = rawLine.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val inline = parseInlineChannel(l)
            if (inline != null) {
                channels.add(
                    ChannelItem(
                        name = inline.first,
                        group = "TXT",
                        url = inline.second,
                        type = ContentType.LIVE
                    )
                )
            }
        }
        return channels
    }

    fun parseInlineChannel(line: String): Pair<String, String>? {
        if (line.contains(",")) {
            val parts = line.split(",", limit = 2)
            val p0 = parts[0].trim()
            val p1 = parts.getOrNull(1)?.trim() ?: ""
            if (p0.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
                return Pair(p1.ifEmpty { "Canal" }, p0)
            }
            if (p1.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
                return Pair(p0.ifEmpty { "Canal" }, p1)
            }
        } else if (line.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
            return Pair("Canal", line)
        }
        return null
    }

    fun detectXtreamPure(text: String): XtreamCredentials? {
        for (pattern in xtreamPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val hostMatch = hostRegex.find(text)
                if (hostMatch != null) {
                    return XtreamCredentials(
                        server = hostMatch.groupValues[1],
                        username = match.groupValues[1],
                        password = match.groupValues[2]
                    )
                }
            }
        }
        return null
    }

    fun parseHeroUrls(text: String): List<String> {
        return text.split(Regex("\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val idx = line.indexOf(" | ")
                if (idx > -1) line.substring(0, idx).trim() else line
            }
            .filter { it.startsWith("http", ignoreCase = true) }
    }

    fun sha256Hex(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            var h = 5381
            for (ch in text) {
                h = ((h shl 5) + h + ch.code)
            }
            "djb2_${Integer.toHexString(h)}"
        }
    }
}
