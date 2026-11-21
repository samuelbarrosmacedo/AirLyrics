package com.andsi.airlyrics.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocument
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocumentReader
import com.andsi.airlyrics.lyrics.importer.LyricsDocumentFormat
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top Notch: import multiple *new* local lyrics entries from a vetted ZIP.
 * Existing manual, provider, and karaoke lyrics are never overwritten.
 * This Activity intentionally adds no network/Spotify permissions.
 *
 * Package: manifest.tsv + lyrics/<Spotify ID>.lrc|ttml
 * manifest.tsv columns: spotify_id, title, artists, album, duration_ms, file
 */
internal class TopNotchBatchImportActivity : AppCompatActivity() {
    private data class Track(
        val spotifyId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val path: String,
        val document: BatchLyricsDocument
    )
    private data class Plan(
        val candidates: List<Track>,
        val rejected: List<String>,
        val withoutFiles: Int,
        val alreadyPresent: Int
    )
    private var pending: Plan? = null
    private lateinit var chooseButton: Button
    private lateinit var importButton: Button
    private lateinit var info: TextView
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) preview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Top Notch · Importação em lote"
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        column.addView(TextView(this).apply {
            text = "Top Notch Lyrics"
            textSize = 23f
            setPadding(0, 0, 0, dp(12))
        })
        column.addView(TextView(this).apply {
            text = "Importa letras de várias músicas, mesmo que nunca tenham sido reproduzidas no AirLyrics. Letras existentes e karaokês salvos são preservados. Analise o ZIP antes de confirmar."
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })
        chooseButton = Button(this).apply {
            text = "Selecionar pacote ZIP"
            setOnClickListener {
                if (!busy) picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }
        column.addView(chooseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        importButton = Button(this).apply {
            text = "Importar novas letras (não substituir)"
            isEnabled = false
            setOnClickListener { executeImport() }
        }
        column.addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        info = TextView(this).apply {
            text = "Nenhum ZIP selecionado."
            textSize = 14f
            setPadding(0, dp(14), 0, 0)
            gravity = Gravity.START
            setTextIsSelectable(true)
        }
        column.addView(info)
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun setBusy(value: Boolean) {
        busy = value
        chooseButton.isEnabled = !value
        importButton.isEnabled = !value && pending?.candidates?.isNotEmpty() == true
    }

    private fun preview(uri: Uri) {
        setBusy(true)
        pending = null
        info.text = "Lendo ZIP e validando cada arquivo..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { inspect(uri) } }
            result.onSuccess { plan ->
                pending = plan
                info.text = "Prévia segura\n\n" +
                    "Novas letras: ${plan.candidates.size}\n" +
                    "Já existentes, preservadas: ${plan.alreadyPresent}\n" +
                    "Sem arquivo no pacote: ${plan.withoutFiles}\n" +
                    "Inválidas ou ambíguas: ${plan.rejected.size}\n\n" +
                    plan.rejected.take(12).joinToString("\n") +
                    "\n\nNão altera nenhuma música antes da confirmação."
            }.onFailure { err ->
                info.text = "Não foi possível ler o pacote: ${err.message ?: "erro desconhecido"}"
            }
            setBusy(false)
        }
    }

    private fun executeImport() {
        val plan = pending ?: return
        if (plan.candidates.isEmpty()) return
        setBusy(true)
        info.text = "Importando sem sobrescrever letras existentes..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var savedPlain = 0
                var savedWord = 0
                var skipped = 0
                val errors = mutableListOf<String>()
                for (t in plan.candidates) {
                    try {
                        // Recheck at commit time: a track may have been saved since preview.
                        if (hasExisting(t)) { skipped++; continue }
                        if (t.document.words.isNotEmpty()) {
                            val wordSaved = LyricsStorage.saveWordByWordLyrics(
                                applicationContext, t.title, t.artist, t.durationMs,
                                t.document.words, album = t.album,
                                wordByWordSource = LyricsStorage.SOURCE_MANUAL_IMPORT,
                                wordByWordProvider = "topnotch", overwrite = false,
                                metadataLines = t.document.metadata
                            )
                            if (!wordSaved) {
                                errors += "${t.spotifyId}: não foi possível gravar karaokê"
                                continue
                            }
                            // Keep a line-level fallback for players not showing word timing.
                            LyricsStorage.savePlainLyrics(
                                applicationContext, t.title, t.artist, t.durationMs,
                                t.document.plainLrc, album = t.album,
                                plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
                                plainProvider = "topnotch", overwrite = false
                            )
                            savedWord++
                        } else {
                            if (LyricsStorage.savePlainLyrics(
                                    applicationContext, t.title, t.artist, t.durationMs,
                                    t.document.plainLrc, album = t.album,
                                    plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT,
                                    plainProvider = "topnotch", overwrite = false
                                )) savedPlain++ else errors += "${t.spotifyId}: falha ao salvar"
                        }
                    } catch (e: Exception) {
                        errors += "${t.spotifyId}: ${e.javaClass.simpleName}"
                    }
                }
                Triple(savedPlain, savedWord, Pair(skipped, errors))
            }
            info.text = "Importação concluída\n\n" +
                "LRC por linha: ${result.first}\n" +
                "Karaokê por palavra: ${result.second}\n" +
                "Já presentes, ignoradas: ${plan.alreadyPresent + result.third.first}\n" +
                "Inválidas: ${plan.rejected.size}\n" +
                "Falhas de gravação: ${result.third.second.size}\n\n" +
                result.third.second.take(15).joinToString("\n") +
                "\n\nSe alguma faixa não aparecer ao tocar, verifique se o nome do artista exibido pelo Spotify corresponde ao do manifesto."
            pending = null
            setBusy(false)
        }
    }

    private fun hasExisting(t: Track): Boolean =
        LyricsStorage.hasWordByWordLyrics(applicationContext, t.title, t.artist, t.durationMs) ||
            LyricsStorage.hasPlainLyrics(applicationContext, t.title, t.artist, t.durationMs)

    private fun inspect(uri: Uri): Plan {
        val entries = readZip(uri)
        val manifest = entries["manifest.tsv"] ?: throw IOException("manifest.tsv ausente")
        val lines = String(manifest, StandardCharsets.UTF_8).removePrefix("\uFEFF").lineSequence()
            .filter { it.isNotBlank() }.toList()
        require(lines.size in 2..501) { "O manifesto deve conter entre 1 e 500 faixas" }
        require(lines.first() == "spotify_id\ttitle\tartists\talbum\tduration_ms\tfile") { "Cabeçalho inesperado no manifesto" }
        val accepted = mutableListOf<Track>()
        val errors = mutableListOf<String>()
        var without = 0
        var existing = 0
        val seenIds = mutableSetOf<String>()
        val seenIdentities = mutableSetOf<String>()
        for ((index, line) in lines.drop(1).withIndex()) {
            val fields = line.split('\t')
            if (fields.size != 6) { errors += "Linha ${index + 2}: colunas inválidas"; continue }
            val id = fields[0]
            val title = fields[1]
            val originalArtists = fields[2]
            val album = fields[3]
            val durationText = fields[4]
            val file = fields[5]
            val duration = durationText.toLongOrNull()
            if (!id.matches(Regex("[A-Za-z0-9]{22}")) || title.isBlank() || originalArtists.isBlank() ||
                duration == null || duration !in 1000L..1_800_000L || !seenIds.add(id)) {
                errors += "Linha ${index + 2}: identidade inválida/duplicada"; continue
            }
            val artist = originalArtists.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            val identity = "${title.lowercase()}|${artist.lowercase()}|${duration / 1000}"
            if (!seenIdentities.add(identity)) { errors += "$id: música ambígua/duplicada"; continue }
            if (file.isBlank()) { without++; continue }
            if (!isSafePath(file) || !file.startsWith("lyrics/") || !file.substringAfterLast('.').lowercase().let { it == "lrc" || it == "ttml" }) {
                errors += "$id: caminho inválido"; continue
            }
            val bytes = entries[file]
            if (bytes == null) { errors += "$id: arquivo ausente"; continue }
            val format = if (file.endsWith(".ttml", true)) LyricsDocumentFormat.TTML else LyricsDocumentFormat.LRC
            val decoded = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
            if (decoded == null) { errors += "$id: texto inválido"; continue }
            val parsed = BatchLyricsDocumentReader.parse(file, decoded, format).document
            if (parsed == null || parsed.plainLrc.isBlank() || parsed.isUntimed) {
                errors += "$id: formato inválido"; continue
            }
            val track = Track(id, title, artist, album, duration, file, parsed)
            if (hasExisting(track)) { existing++; continue }
            accepted += track
        }
        return Plan(accepted, errors, without, existing)
    }

    private fun isSafePath(path: String): Boolean =
        !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
            !path.any { it == '\u0000' || it == '\r' || it == '\n' }

    private fun readZip(uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Arquivo inacessível")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var total = 0L
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= 700) { "ZIP contém arquivos demais" }
                    if (entry.isDirectory) continue
                    require(isSafePath(entry.name)) { "Caminho não permitido no ZIP" }
                    require(entry.name == "manifest.tsv" || entry.name.startsWith("lyrics/")) { "Arquivo inesperado no ZIP" }
                    require(!result.containsKey(entry.name)) { "Nome duplicado no ZIP" }
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    var size = 0
                    while (true) {
                        val got = zip.read(buffer)
                        if (got < 0) break
                        size += got
                        total += got
                        require(size <= 350_000 && total <= 35_000_000L) { "ZIP ultrapassou os limites de segurança" }
                        output.write(buffer, 0, got)
                    }
                    result[entry.name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        }
        return result
    }
}
