package com.sambhav.lyricconstellation

import android.content.Context
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class Word(val text: String, val startMs: Long, val endMs: Long)

data class Line(val startMs: Long, val endMs: Long, val words: List<Word>)

data class Track(val title: String, val artist: String, val durationMs: Long, val pkg: String) {
    val key: String get() = "$artist|$title|${durationMs / 1000}"
}

/** Needed only so Android lets us read the active media sessions (Spotify, YT Music, ...). */
class MediaListenerService : NotificationListenerService()

object Lrc {
    private val tag = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")

    fun parse(lrc: String): List<Line> {
        val raw = ArrayList<Pair<Long, String>>()
        for (ln in lrc.lines()) {
            val ms = tag.findAll(ln).toList()
            if (ms.isEmpty()) continue
            val text = ln.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val t = ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toDouble()) * 1000).toLong()
                raw.add(t to text)
            }
        }
        raw.sortBy { it.first }
        val out = ArrayList<Line>()
        for (i in raw.indices) {
            val s = raw[i].first
            val text = raw[i].second
            if (text.isBlank()) continue
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            val nextStart = if (i + 1 < raw.size) raw[i + 1].first else s + 6000L
            val est = 1200L + text.length * 120L
            val end = maxOf(s + 800L, minOf(nextStart, s + est))
            val usable = ((end - s) * 0.9).toLong()
            val totalW = parts.sumOf { it.length + 1 }
            var acc = 0
            val words = ArrayList<Word>()
            for (p in parts) {
                val ws = s + usable * acc / totalW
                acc += p.length + 1
                val we = s + usable * acc / totalW
                words.add(Word(p, ws, we))
            }
            out.add(Line(s, end, words))
        }
        return out
    }

    fun fetch(ctx: Context, t: Track): List<Line>? {
        val f = File(ctx.cacheDir, "lrc_" + t.key.hashCode() + ".txt")
        if (f.exists()) {
            val cached = parse(f.readText())
            if (cached.isNotEmpty()) return cached
        }
        val synced = query(t, true) ?: query(t, false) ?: return null
        val lines = parse(synced)
        if (lines.isEmpty()) return null
        try { f.writeText(synced) } catch (_: Exception) {}
        return lines
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(s: String) =
        s.replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex(" - .*$"), "").trim()

    private fun query(t: Track, exact: Boolean): String? {
        return try {
            val base = "https://lrclib.net/api/"
            if (exact) {
                val url = base + "get?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}&duration=${t.durationMs / 1000}"
                val body = http(url) ?: return null
                val j = JSONObject(body)
                if (j.isNull("syncedLyrics")) null else j.getString("syncedLyrics").ifBlank { null }
            } else {
                val title = clean(t.title)
                val artist = t.artist.split(",", "&").first().trim()
                val body = http(base + "search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
                val arr = JSONArray(body)
                var best: String? = null
                var bestDiff = Long.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.isNull("syncedLyrics")) continue
                    val s = o.getString("syncedLyrics")
                    if (s.isBlank()) continue
                    val d = if (t.durationMs > 0) abs(o.optDouble("duration", 0.0) * 1000 - t.durationMs).toLong() else 0L
                    if (d < bestDiff) { bestDiff = d; best = s }
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "LyricConstellation/1.0")
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }
}

/** Reads the currently playing song and its position from any media app. */
class NowPlaying(private val ctx: Context) {
    var track: Track? = null
        private set
    var playing = false
        private set
    private var basePos = 0L
    private var baseTime = 0L
    private var speed = 1f

    fun refresh() {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java))
        } catch (e: SecurityException) {
            track = null; playing = false
            return
        }
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
        val md = c?.metadata
        if (c == null || md == null) { track = null; playing = false; return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        track = if (title.isBlank()) null else Track(title, artist, dur, c.packageName)
        val st = c.playbackState
        if (st != null) {
            basePos = if (st.position < 0) 0L else st.position
            baseTime = if (st.lastPositionUpdateTime > 0) st.lastPositionUpdateTime else SystemClock.elapsedRealtime()
            speed = if (st.playbackSpeed == 0f) 1f else st.playbackSpeed
            playing = st.state == PlaybackState.STATE_PLAYING
        }
    }

    /** Current song position in ms, extrapolated between polls. Sync offset is added by the view. */
    fun positionMs(): Long {
        if (!playing) return basePos
        return basePos + ((SystemClock.elapsedRealtime() - baseTime) * speed).toLong() 
    }
}
