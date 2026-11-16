package com.iptv.heroprotv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iptv.heroprotv.model.UserProfile
import java.io.File
import java.net.URLEncoder

data class HeroListMeta(
    val hash: String,
    val checkedAt: Long,
    val urlCount: Int
)

class StorageManager(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_hero_prefs", Context.MODE_PRIVATE)
    private val profilesFile = File(context.filesDir, "iptv_hero_profiles.json")
    private val gson = Gson()

    fun getProxyUrl(): String {
        return normalizeProxyBase(prefs.getString("iptv_proxy", "") ?: "")
    }

    fun setProxyUrl(raw: String): String {
        val normalized = normalizeProxyBase(raw)
        if (normalized.isNotEmpty()) {
            prefs.edit().putString("iptv_proxy", normalized).apply()
        } else {
            prefs.edit().remove("iptv_proxy").apply()
        }
        return normalized
    }

    fun clearProxyUrl() {
        prefs.edit().remove("iptv_proxy").apply()
    }

    fun normalizeProxyBase(raw: String): String {
        if (raw.isBlank()) return ""
        var base = raw.trim()
        base = base.replace(Regex("[?&]url=.*$", RegexOption.IGNORE_CASE), "")
        base = base.replace(Regex("[?&]+$"), "")
        if (!base.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) return ""
        if (!base.endsWith("/") && !base.contains("?")) base += "/"
        return base
    }

    fun applyProxy(url: String, proxyBase: String = getProxyUrl()): String {
        if (url.isBlank() || proxyBase.isBlank()) return url
        if (url.startsWith(proxyBase)) return url
        if (!url.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) return url
        val sep = if (proxyBase.contains("?")) "&" else "?"
        return proxyBase + sep + "url=" + URLEncoder.encode(url, "UTF-8")
    }

    fun getActiveProfileId(): String? {
        return prefs.getString("activeProfileId", null)
    }

    fun setActiveProfileId(id: String?) {
        if (id != null) {
            prefs.edit().putString("activeProfileId", id).apply()
        } else {
            prefs.edit().remove("activeProfileId").apply()
        }
    }

    fun loadProfiles(): MutableList<UserProfile> {
        return try {
            if (!profilesFile.exists()) return mutableListOf()
            val json = profilesFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<MutableList<UserProfile>>() {}.type
            gson.fromJson<MutableList<UserProfile>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveProfiles(profiles: List<UserProfile>, activeProfileId: String?) {
        try {
            val json = gson.toJson(profiles)
            profilesFile.writeText(json, Charsets.UTF_8)
            setActiveProfileId(activeProfileId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getHeroListMeta(): HeroListMeta? {
        val json = prefs.getString("hero_list_meta_v2", null) ?: return null
        return try {
            gson.fromJson(json, HeroListMeta::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setHeroListMeta(meta: HeroListMeta) {
        prefs.edit().putString("hero_list_meta_v2", gson.toJson(meta)).apply()
    }
}
