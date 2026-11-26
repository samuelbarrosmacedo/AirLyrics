package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.providers.LocalPlainLyricsProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TopNotchTrackLinksTest : LyricsStorageTestBase() {
    private val id = "0123456789abcdefghijkl"
    private val song = SongIdentity("Test recording", "Artist A, Artist B", "Album", 120_000)
    private val lrc = "[00:01.00]First line\n[00:03.00]Second line"

    private fun request(trackId: String? = id, duration: Long = 120_000) =
        PlainLyricsSearchRequest(context, song.title, "Artist A & Artist B", durationMs = duration, spotifyTrackId = trackId)

    @Test fun importedIdResolvesDifferentArtistSpellingAndRejectsWrongIdOrDuration() {
        assertTrue(LyricsStorage.savePlainLyrics(context, song.title, song.artist, song.durationMs,
            lrc, plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT, plainProvider = "topnotch"))
        assertTrue(TopNotchTrackLinks.canRecover(context, id, song))
        assertTrue(TopNotchTrackLinks.link(context, id, song))
        assertFalse(TopNotchTrackLinks.canRecover(context, id, song))
        assertEquals(lrc, LocalPlainLyricsProvider.fetch(request()).getOrThrow()?.plainLrc)
        assertNull(TopNotchTrackLinks.read(request(null)))
        assertNull(TopNotchTrackLinks.read(request("1123456789abcdefghijkl")))
        assertNull(TopNotchTrackLinks.read(request(duration = 140_000)))
        assertTrue(LyricsStorage.deleteLocalLyrics(context, song.title, song.artist, song.durationMs))
        assertNull(TopNotchTrackLinks.read(request()))
    }

    @Test fun unrelatedLocalLyricsCannotBeLinked() {
        assertTrue(LyricsStorage.savePlainLyrics(context, song.title, song.artist, song.durationMs, lrc))
        assertFalse(TopNotchTrackLinks.link(context, id, song))
        assertNull(TopNotchTrackLinks.read(request()))
    }
}
