package com.iptv.heroprotv.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iptv.heroprotv.model.*
import com.iptv.heroprotv.parser.PlaylistParser
import com.iptv.heroprotv.util.HeroGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class IptvRepository(private val storage: StorageManager) {

    companion object {
        const val HERO_LIST_URL = "https://raw.githubusercontent.com/NOSLIUDE/Satsil/refs/heads/main/all_in_one.txt"
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchRawText(url: String, useProxy: Boolean = true): String = withContext(Dispatchers.IO) {
        val finalUrl = if (useProxy) storage.applyProxy(url) else url
        val request = Request.Builder()
            .url(finalUrl)
            .header("User-Agent", "IPTVHeroProTV/1.0 (Android)")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: throw Exception("Resposta vazia")
        }
    }

    fun heroUrlWithBust(): String {
        val bust = System.currentTimeMillis()
        val sep = if (HERO_LIST_URL.contains("?")) "&" else "?"
        return "$HERO_LIST_URL${sep}t=$bust"
    }

    suspend fun testProxy(rawProxyUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = storage.normalizeProxyBase(rawProxyUrl)
        if (normalized.isEmpty()) throw Exception("URL inválida. Use http(s)://.../")
        val target = storage.applyProxy("https://httpbin.org/get", normalized)
        val req = Request.Builder().url(target).build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: "{}"
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("origin")?.asString ?: "?"
        }
    }

    data class XtreamLoadResult(
        val playlist: PlaylistData,
        val validity: String?,
        val maxConnections: Int?,
        val activeConnections: Int
    )

    suspend fun loadXtreamFull(server: String, user: String, pass: String): XtreamLoadResult = withContext(Dispatchers.IO) {
        val cleanServer = server.trim().removeSuffix("/")
        val encUser = URLEncoder.encode(user, "UTF-8")
        val encPass = URLEncoder.encode(pass, "UTF-8")
        val base = "$cleanServer/player_api.php?username=$encUser&password=$encPass"

        val authJson = JsonParser.parseString(fetchRawText(base)).asJsonObject
        val userInfo = authJson.getAsJsonObject("user_info")
            ?: throw Exception("Credenciais inválidas")
        val authFlag = userInfo.get("auth")?.asInt ?: 0
        if (authFlag == 0) throw Exception("Credenciais inválidas")

        var validity: String? = null
        val expSec = userInfo.get("exp_date")?.takeIf { !it.isJsonNull }?.asString?.toLongOrNull() ?: 0L
        if (expSec > 0L) {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            validity = sdf.format(Date(expSec * 1000L))
        }

        val maxConnections = userInfo.get("max_connections")?.takeIf { !it.isJsonNull }?.asString?.toIntOrNull()
        val activeConnections = userInfo.get("active_cons")?.takeIf { !it.isJsonNull }?.asString?.toIntOrNull() ?: 0

        val liveDeferred = async {
            runCatching {
                val catsRaw = fetchRawText("$base&action=get_live_categories")
                val streamsRaw = fetchRawText("$base&action=get_live_streams")
                val catMap = parseCategoryMap(catsRaw)
                val arr = JsonParser.parseString(streamsRaw).asJsonArray
                arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val streamId = obj.getStringOrNull("stream_id") ?: return@mapNotNull null
                    val name = obj.getStringOrNull("name") ?: "Canal"
                    val catId = obj.getStringOrNull("category_id") ?: ""
                    ChannelItem(
                        name = name,
                        group = catMap[catId] ?: "Geral",
                        url = "$cleanServer/live/$encUser/$encPass/$streamId.m3u8",
                        type = ContentType.LIVE,
                        id = streamId
                    )
                }
            }.getOrDefault(emptyList())
        }

        val vodDeferred = async {
            runCatching {
                val catsRaw = fetchRawText("$base&action=get_vod_categories")
                val streamsRaw = fetchRawText("$base&action=get_vod_streams")
                val catMap = parseCategoryMap(catsRaw)
                val arr = JsonParser.parseString(streamsRaw).asJsonArray
                arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val streamId = obj.getStringOrNull("stream_id") ?: return@mapNotNull null
                    val name = obj.getStringOrNull("name") ?: "Filme"
                    val catId = obj.getStringOrNull("category_id") ?: ""
                    val ext = obj.getStringOrNull("container_extension") ?: "mp4"
                    ChannelItem(
                        name = name,
                        group = catMap[catId] ?: "Geral",
                        url = "$cleanServer/movie/$encUser/$encPass/$streamId.$ext",
                        type = ContentType.VOD,
                        id = streamId
                    )
                }
            }.getOrDefault(emptyList())
        }

        val seriesDeferred = async {
            runCatching {
                val catsRaw = fetchRawText("$base&action=get_series_categories")
                val streamsRaw = fetchRawText("$base&action=get_series")
                val catMap = parseCategoryMap(catsRaw)
                val arr = JsonParser.parseString(streamsRaw).asJsonArray
                arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val seriesId = obj.getStringOrNull("series_id") ?: return@mapNotNull null
                    val name = obj.getStringOrNull("name") ?: "Série"
                    val catId = obj.getStringOrNull("category_id") ?: ""
                    ChannelItem(
                        name = name,
                        group = catMap[catId] ?: "Geral",
                        url = null,
                        type = ContentType.SERIES,
                        id = seriesId
                    )
                }
            }.getOrDefault(emptyList())
        }

        val liveList = liveDeferred.await()
        val vodList = vodDeferred.await()
        val seriesList = seriesDeferred.await()

        XtreamLoadResult(
            playlist = PlaylistData(
                live = liveList,
                vod = vodList,
                series = seriesList,
                sourceType = "xtream",
                server = cleanServer,
                user = user,
                pass = pass
            ),
            validity = validity,
            maxConnections = maxConnections,
            activeConnections = activeConnections
        )
    }

    suspend fun loadM3uOrTxtPlaylist(url: String): PlaylistData = withContext(Dispatchers.IO) {
        val text = fetchRawText(url)
        val channels = if (text.contains("#EXTM3U") || text.contains("#EXTINF")) {
            PlaylistParser.parseM3U(text)
        } else {
            PlaylistParser.parseTXT(text)
        }
        val live = channels.filter { it.type == ContentType.LIVE }
        val vod = channels.filter { it.type == ContentType.VOD }
        PlaylistData(
            live = live,
            vod = vod,
            series = emptyList(),
            sourceType = "m3u",
            server = url
        )
    }

        suspend fun processOneBulkLine(
        line: String,
        onProfileCreated: (UserProfile) -> Unit
    ): BulkItemResult = withContext(Dispatchers.IO) {
        val creds = PlaylistParser.detectXtreamPure(line)
        if (creds != null) {
            return@withContext try {
                val cleanServer = creds.server.removeSuffix("/")
                val encUser = URLEncoder.encode(creds.username, "UTF-8")
                val encPass = URLEncoder.encode(creds.password, "UTF-8")
                val base = "$cleanServer/player_api.php?username=$encUser&password=$encPass"

                val authJson = JsonParser.parseString(fetchRawText(base)).asJsonObject
                val userInfo = authJson.getAsJsonObject("user_info")
                if (userInfo == null || (userInfo.get("auth")?.asInt ?: 0) == 0) {
                    throw Exception("Credenciais inválidas")
                }

                var validity: String? = null
                val expSec = userInfo.get("exp_date")?.takeIf { !it.isJsonNull }?.asString?.toLongOrNull() ?: 0L
                if (expSec > 0L) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    validity = sdf.format(Date(expSec * 1000L))
                }
                val maxC = userInfo.get("max_connections")?.takeIf { !it.isJsonNull }?.asString?.toIntOrNull()

                // Otimização: Não baixamos os arrays gigantes de streams no lote. 
                // O conteúdo será carregado sob demanda quando o usuário ativar o perfil.
                val profile = buildNewProfile(
                    sourceType = "xtream",
                    sourceLabel = cleanServer,
                    server = cleanServer,
                    user = creds.username,
                    pass = creds.password,
                    validity = validity,
                    connections = maxC,
                    contentCounts = ContentCounts(0, 0, 0),
                    bulkOrigin = true
                )
                onProfileCreated(profile)
                BulkItemResult(ok = true, hero = profile.hero, type = "XTREAM", count = 0)
            } catch (e: Exception) {
                BulkItemResult(ok = false, error = e.message ?: "Erro")
            }
        }

        if (line.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
            return@withContext try {
                val text = fetchRawText(line)
                if (text.contains("#EXTM3U") || text.contains("#EXTINF")) {
                    // Contagem leve sem instanciar milhares de objetos na memória
                    val liveCount = text.lines().count { it.startsWith("#EXTINF") }
                    val profile = buildNewProfile(
                        sourceType = "m3u",
                        sourceLabel = line,
                        server = line,
                        contentCounts = ContentCounts(liveCount, 0, 0),
                        bulkOrigin = true
                    )
                    onProfileCreated(profile)
                    return@withContext BulkItemResult(ok = true, hero = profile.hero, type = "M3U", count = liveCount)
                }
                val ch = PlaylistParser.parseTXT(text)
                if (ch.isNotEmpty()) {
                    val profile = buildNewProfile(
                        sourceType = "txt",
                        sourceLabel = line,
                        server = line,
                        contentCounts = ContentCounts(ch.size, 0, 0),
                        bulkOrigin = true
                    )
                    onProfileCreated(profile)
                    return@withContext BulkItemResult(ok = true, hero = profile.hero, type = "TXT", count = ch.size)
                }
                throw Exception("Formato desconhecido")
            } catch (e: Exception) {
                BulkItemResult(ok = false, error = e.message ?: "Erro")
            }
        }

        val inline = PlaylistParser.parseInlineChannel(line)
        if (inline != null) {
            val profile = buildNewProfile(
                sourceType = "txt",
                sourceLabel = inline.first,
                server = inline.second,
                contentCounts = ContentCounts(1, 0, 0),
                bulkOrigin = true
            )
            onProfileCreated(profile)
            return@withContext BulkItemResult(ok = true, hero = profile.hero, type = "inline", count = 1)
        }

        return@withContext BulkItemResult(ok = false, error = "Não reconhecido")
    }


    suspend fun loadSeriesInfo(
        server: String,
        user: String,
        pass: String,
        seriesId: String
    ): Map<String, List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val encUser = URLEncoder.encode(user, "UTF-8")
        val encPass = URLEncoder.encode(pass, "UTF-8")
        val url = "$server/player_api.php?username=$encUser&password=$encPass&action=get_series_info&series_id=$seriesId"
        val raw = fetchRawText(url)
        val root = JsonParser.parseString(raw).asJsonObject
        val episodesObj = root.getAsJsonObject("episodes") ?: return@withContext emptyMap()

        val result = linkedMapOf<String, List<EpisodeItem>>()
        val sortedSeasonKeys = episodesObj.keySet().sortedBy { it.toFloatOrNull() ?: 0f }

        for (seasonKey in sortedSeasonKeys) {
            val epEl: JsonElement = episodesObj.get(seasonKey)
            if (!epEl.isJsonArray) continue
            val parsedEps = epEl.asJsonArray.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val ep = item.asJsonObject
                val id = ep.getStringOrNull("id")
                    ?: ep.getStringOrNull("episode_id")
                    ?: ep.getStringOrNull("stream_id")
                    ?: ep.getStringOrNull("vod_id")
                    ?: return@mapNotNull null
                val epNum = ep.getStringOrNull("episode_num")
                    ?: ep.getStringOrNull("episode")
                    ?: ep.getStringOrNull("num")
                    ?: "0"
                val title = ep.getStringOrNull("title")
                    ?: ep.getStringOrNull("name")
                    ?: ep.getStringOrNull("episode_title")
                    ?: "Episódio $epNum"
                val ext = ep.getStringOrNull("container_extension")
                    ?: ep.getStringOrNull("ext")
                    ?: ep.getStringOrNull("extension")
                    ?: "mp4"
                val infoObj = ep.getAsJsonObject("info")
                val duration = ep.getStringOrNull("duration")
                    ?: infoObj?.getStringOrNull("duration")

                EpisodeItem(
                    id = id,
                    episodeNum = epNum,
                    title = title,
                    containerExtension = ext,
                    duration = duration
                )
            }.sortedBy { it.episodeNum.toFloatOrNull() ?: 0f }

            result[seasonKey] = parsedEps
        }
        result
    }

    fun buildNewProfile(
        sourceType: String,
        sourceLabel: String,
        server: String? = null,
        user: String? = null,
        pass: String? = null,
        validity: String? = null,
        connections: Int? = null,
        contentCounts: ContentCounts = ContentCounts(),
        bulkOrigin: Boolean = false
    ): UserProfile {
        val hero = HeroGenerator.generate()
        return UserProfile(
            id = "p_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
            hero = hero.name,
            universe = hero.universe,
            sourceType = sourceType,
            sourceLabel = sourceLabel,
            server = server,
            user = user,
            pass = pass,
            validity = validity,
            maxConnections = connections,
            activeConnections = 0,
            contentCounts = contentCounts,
            bulkOrigin = bulkOrigin,
            createdAt = System.currentTimeMillis(),
            lastUsed = System.currentTimeMillis()
        )
    }

    private fun parseCategoryMap(jsonRaw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val el = JsonParser.parseString(jsonRaw)
        if (el.isJsonArray) {
            for (item in el.asJsonArray) {
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    val id = obj.getStringOrNull("category_id")
                    val name = obj.getStringOrNull("category_name")
                    if (id != null && name != null) {
                        map[id] = name
                    }
                }
            }
        }
        return map
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val el = this.get(key) ?: return null
        if (el.isJsonNull) return null
        return try {
            el.asString
        } catch (e: Exception) {
            null
        }
    }
}
