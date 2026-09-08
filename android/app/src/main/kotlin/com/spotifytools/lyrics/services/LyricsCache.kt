package com.spotifytools.lyrics.services

import android.content.Context
import com.spotifytools.core.LrcParser
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.utils.Result
import com.spotifytools.lyrics.utils.runCatchingApp
import com.spotifytools.lyrics.utils.AppError
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 歌词磁盘缓存（LyricFetchSkill 的存储层）
 *
 * 结构：files/lyrics_cache/<sha1(key)>.json
 * 内容：{ trackName, artistName, syncedLrc }（原始 LRC 文本，加载时再繁转简——词表升级后可复用）
 * 规则：缓存失败不阻塞主流程（降级跳过）；UTF-8 编码。
 */
class LyricsCache(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics_cache").apply { mkdirs() }

    /** 缓存键：归一化后的 歌名+歌手（大小写/空白不敏感） */
    fun keyOf(trackName: String, artistName: String): String {
        val norm = "${trackName.lowercase()}|${artistName.lowercase()}".replace(Regex("\\s+"), " ").trim()
        val digest = MessageDigest.getInstance("SHA-1").digest(norm.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(trackName: String, artistName: String): List<LrcParser.Line>? {
        val f = File(cacheDir, keyOf(trackName, artistName) + ".json")
        if (!f.exists()) return null
        return runCatchingApp(AppError.storage("读取缓存失败")) {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val lrc = json.optString("syncedLrc")
            if (lrc.isEmpty()) null else LrcParser.parse(lrc)
        }.getOrNull()
    }

    fun put(trackName: String, artistName: String, syncedLrc: String) {
        runCatchingApp(AppError.storage("写入缓存失败")) {
            val json = JSONObject()
                .put("trackName", trackName)
                .put("artistName", artistName)
                .put("syncedLrc", syncedLrc)
                .put("cachedAt", System.currentTimeMillis())
            File(cacheDir, keyOf(trackName, artistName) + ".json")
                .writeText(json.toString(), Charsets.UTF_8)
        }
    }

    fun size(): Int = cacheDir.listFiles()?.size ?: 0

    /** 逐出单曲缓存（手动刷新：强制走网络重新获取） */
    fun evict(trackName: String, artistName: String) {
        val f = File(cacheDir, keyOf(trackName, artistName) + ".json")
        if (f.exists()) {
            runCatchingApp(AppError.storage("逐出缓存失败")) { f.delete() }
            LogKit.d("已逐出缓存: $trackName")
        }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
        LogKit.i("歌词缓存已清空")
    }
}

private fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Failure -> null
}
