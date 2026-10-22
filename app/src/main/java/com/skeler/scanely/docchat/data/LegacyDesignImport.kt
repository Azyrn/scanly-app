package com.skeler.scanely.docchat.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.DesignStore
import com.skeler.scanely.ui.viewmodel.DesignWorkspace
import com.skeler.scanely.ui.viewmodel.DesignWorkspaceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LegacyDesignImport"

/**
 * The old designer kept one workspace: the last request and up to six versions of its PDF. The
 * first time the chat runs, that workspace becomes a chat — the request as the user's message,
 * each version as a file — so a document made before 3.6 still opens from history. Then it is
 * cleared, so this happens once.
 */
@Singleton
class LegacyDesignImport @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaces: DesignWorkspaceStore,
    private val designStore: DesignStore,
    private val repository: ChatRepository,
    private val files: ChatFiles
) {
    suspend fun run() {
        val workspace = try {
            workspaces.read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        try {
            importAsChat(workspace)
            workspaces.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Import failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun importAsChat(workspace: DesignWorkspace) {
        val versions = workspace.versions.mapNotNull { version ->
            designStore.fileFor(version.uri.toUri())?.let { version to it }
        }
        if (versions.isEmpty()) return
        val text = workspace.prompt
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS)
            ?: context.getString(R.string.chat_untitled)
        val chatId = UUID.randomUUID().toString()
        repository.createChat(chatId, title)
        repository.append(chatId, Role.USER, UserBody(text = text))
        val name = chatFileName(title, context.getString(R.string.chat_file_default_name))
        versions.forEach { (version, file) ->
            val copy = files.adopt(file, version.id, DocFormat.PDF)
            files.makeThumbnail(version.id, copy)
            val body = FileBody(version.id, DocFormat.PDF, name, version.pages, version.billed)
            repository.append(chatId, Role.AI, body)
        }
    }

    private companion object {
        const val TITLE_CHARS = 48
    }
}
