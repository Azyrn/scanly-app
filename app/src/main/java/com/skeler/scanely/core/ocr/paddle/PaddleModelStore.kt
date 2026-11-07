package com.skeler.scanely.core.ocr.paddle

import android.content.Context
import android.util.Log
import com.skeler.scanely.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PaddleModelStore"

sealed interface PackState {
    data object Bundled : PackState
    data object Installed : PackState
    data object Missing : PackState
    data class Downloading(val progress: Float) : PackState
    data class Failed(val message: String) : PackState
}

/** Bundled assets + on-demand script packs. Packs are usable offline once installed. */
@Singleton
class PaddleModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {

    // context.filesDir stats/creates the dir on every call; lazy keeps the
    // constructor free of disk I/O (eager Hilt injection lands on main).
    private val packsDir by lazy { File(context.filesDir, "ocr_packs") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloader = HfModelDownloader(httpClient)

    /** 30 MB dewarper, opt-in — downloaded rather than bundled. */
    val uvdoc = OptionalModel(context, scope, downloader, "UVDoc_onnx", "UVDoc") {
        File(context.filesDir, "ocr_extras/uvdoc.onnx")
    }

    /** 8 MB SLANet_plus table structure model, opt-in — downloaded rather than bundled. */
    val table = OptionalModel(context, scope, downloader, "SLANet_plus_onnx", "Table model") {
        File(context.filesDir, "ocr_extras/slanet.onnx")
    }

    private val _states = MutableStateFlow<Map<ScriptPack, PackState>>(
        ScriptPack.entries.associateWith { pack ->
            if (pack.isBundled) PackState.Bundled else PackState.Missing
        }
    )
    val states: StateFlow<Map<ScriptPack, PackState>> = _states.asStateFlow()

    init {
        // Eager Hilt injection constructs this on the main thread; the exists() sweep
        // belongs on IO. Only Missing entries are upgraded, so states set by an
        // in-flight download are never clobbered.
        scope.launch {
            _states.update { map ->
                map.mapValues { (pack, state) ->
                    if (state is PackState.Missing && isInstalled(pack)) PackState.Installed else state
                }
            }
            uvdoc.refresh()
            table.refresh()
        }
    }

    fun asset(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    fun downloadAsync(pack: ScriptPack) {
        scope.launch { download(pack) }
    }

    fun isInstalled(pack: ScriptPack): Boolean =
        packModelFile(packsDir, pack).exists() && packDictFile(packsDir, pack).exists()

    /** Rec model bytes + charset for the pack, falling back to the bundled universal model. */
    fun recModel(pack: ScriptPack): Pair<ByteArray, List<String>> {
        if (pack.isBundled) {
            return asset(pack.bundledModel!!) to charset(asset(pack.bundledDict!!).decodeToString())
        }
        if (!isInstalled(pack)) return recModel(ScriptPack.DEFAULT)
        return packModelFile(packsDir, pack).readBytes() to charset(packDictFile(packsDir, pack).readText())
    }

    suspend fun download(pack: ScriptPack): Result<Unit> = withContext(Dispatchers.IO) {
        val repo = pack.repo ?: return@withContext Result.success(Unit)
        val dir = File(packsDir, pack.id).apply { mkdirs() }
        setState(pack, PackState.Downloading(0f))

        runCatching {
            val yml = downloader.fetch("$HF/$repo/resolve/main/inference.yml").decodeToString()
            val chars = parseYamlDict(yml)
            check(chars.size > 10) { "Empty charset" }
            setState(pack, PackState.Downloading(0.1f))

            downloader.downloadVerified(repo, packModelFile(packsDir, pack)) { p ->
                setState(pack, PackState.Downloading(0.1f + p * 0.9f))
            }
            packDictFile(packsDir, pack).writeText(chars.joinToString("\n"))
            setState(pack, PackState.Installed)
        }.onFailure { e ->
            Log.e(TAG, "Pack download failed: ${pack.id}", e)
            dir.deleteRecursively()
            setState(pack, PackState.Failed(e.message ?: context.getString(R.string.ocr_err_download)))
        }.map { }
    }

    fun delete(pack: ScriptPack) {
        if (pack.isBundled) return
        File(packsDir, pack.id).deleteRecursively()
        setState(pack, PackState.Missing)
    }

    private fun setState(pack: ScriptPack, state: PackState) =
        _states.update { it + (pack to state) }

    data class LfsPointer(val sha256: String, val size: Long)

    companion object {

        /** Git LFS pointer text: "oid sha256:<hex>" + "size <bytes>" lines. Used by HfModelDownloader. */
        fun parseLfsPointer(text: String): LfsPointer? {
            val sha = Regex("oid sha256:([0-9a-f]{64})").find(text)?.groupValues?.get(1)
                ?: return null
            val size = Regex("size (\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            return LfsPointer(sha, size)
        }

        /** CTC classes: blank + dict + space, matching PaddleOCR's CTCLabelDecode. */
        fun charset(dict: String): List<String> =
            buildList {
                add("")
                dict.lineSequence().forEach { if (it.isNotEmpty()) add(it) }
                add(" ")
            }

        /** character_dict entries out of PaddleX inference.yml. */
        fun parseYamlDict(yml: String): List<String> {
            val body = yml.substringAfter("character_dict:", "")
            if (body.isEmpty()) return emptyList()
            val out = ArrayList<String>(8000)
            for (raw in body.lineSequence()) {
                val trimmed = raw.trimStart()
                val entry = trimmed.startsWith("- ")
                // Blank and indented non-entry lines are skipped; an unindented one ends the list.
                if (!raw.isBlank() && !entry && !raw.startsWith(" ")) break
                if (!raw.isBlank() && entry) out.add(yamlScalar(trimmed.removePrefix("- ").trimEnd()).ifEmpty { " " })
            }
            return out
        }

        /** A YAML scalar with its single or double quotes removed. */
        private fun yamlScalar(v: String): String = when {
            v.length >= 2 && v.first() == '\'' && v.last() == '\'' -> v.substring(1, v.length - 1).replace("''", "'")
            v.length >= 2 && v.first() == '"' && v.last() == '"' -> v.substring(1, v.length - 1)
            else -> v
        }
    }
}

private fun packModelFile(packsDir: File, pack: ScriptPack) = File(File(packsDir, pack.id), "rec.onnx")

private fun packDictFile(packsDir: File, pack: ScriptPack) = File(File(packsDir, pack.id), "dict.txt")
