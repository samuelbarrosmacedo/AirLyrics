package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.SpotifyTrackId
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import kotlin.math.abs

/** Links only imported TopNotch recordings; no lyrics are duplicated in preferences. */
internal object TopNotchTrackLinks {
    private fun prefs(context: Context) = context.getSharedPreferences("topnotch_track_links", Context.MODE_PRIVATE)

    private fun importedEntry(context: Context, key: String): LyricsIndexEntry? =
        LyricsIndexStore.read(context).singleOrNull {
            it.key == key && !it.isUntimed && it.plainFile.isNotBlank() &&
                (it.plainProvider == "topnotch" || it.wordByWordProvider == "topnotch")
        }

    fun canRecover(context: Context, id: String, song: SongIdentity): Boolean = LyricsStorage.withStorageLock {
        SpotifyTrackId.parse(id) == id &&
            prefs(context).getString(id, null) == null &&
            importedEntry(context, song.storageKey()) != null
    }

    fun link(context: Context, id: String, song: SongIdentity): Boolean = LyricsStorage.withStorageLock {
        if (SpotifyTrackId.parse(id) != id) return@withStorageLock false
        val key = song.storageKey()
        val previous = prefs(context).getString(id, null)
        if (previous != null && previous != key && importedEntry(context, previous) != null) return@withStorageLock false
        val entry = importedEntry(context, key) ?: return@withStorageLock false
        if (LyricsFileStore.readManagedLyrics(context, entry.plainFile).isNullOrBlank()) return@withStorageLock false
        prefs(context).edit().putString(id, key).commit()
    }

    fun read(request: PlainLyricsSearchRequest): LyricsProviderResult? = LyricsStorage.withStorageLock {
        val id = SpotifyTrackId.parse(request.spotifyTrackId) ?: return@withStorageLock null
        val key = prefs(request.context).getString(id, null) ?: return@withStorageLock null
        val entry = importedEntry(request.context, key) ?: return@withStorageLock null
        if (request.durationMs > 0 && entry.durationMs > 0 &&
            abs(request.durationMs - entry.durationMs) > 5_000L) return@withStorageLock null
        val plain = LyricsFileStore.readManagedLyrics(request.context, entry.plainFile)
            ?.takeIf { it.isNotBlank() } ?: return@withStorageLock null
        val words = entry.wordByWordFile.takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(request.context, it) }
            ?.let { WordByWordLyricsJsonCodec.parseWordByWordLinesJson(it) }.orEmpty()
        LyricsProviderResult(
            plainProviderId = "local",
            plainProviderName = "Local lyrics",
            plainLrc = plain,
            wordByWordLines = words,
            matchedTitle = entry.title,
            matchedArtist = entry.artist,
            matchedAlbum = entry.album,
            matchedDurationMs = entry.durationMs,
            manualLocal = true,
            selectedManually = true
        )
    }
}
