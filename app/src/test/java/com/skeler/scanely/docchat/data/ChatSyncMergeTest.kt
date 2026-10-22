package com.skeler.scanely.docchat.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSyncMergeTest {
    private fun chat(updatedAt: Long, synced: Boolean) =
        ChatEntity(id = "c", title = "t", createdAt = 1, updatedAt = updatedAt, synced = synced)

    private fun message(updatedAt: Long, synced: Boolean, chatId: String = "c") = MessageEntity(
        id = "m", chatId = chatId, seq = 1, role = "ai", kind = "file", body = "{}", searchText = "",
        createdAt = 1, updatedAt = updatedAt, synced = synced
    )

    private fun remoteChat(updatedAt: Long) = RemoteChat(id = "c", title = "server", updatedAt = updatedAt)
    private fun remoteMessage(updatedAt: Long) = RemoteMessage("m", 1, "ai", "file", "{}", 1, updatedAt)

    @Test
    fun `the server's copy replaces anything this phone already sent`() {
        assertTrue(takesRemoteChat(null, remoteChat(5)))
        assertTrue(takesRemoteChat(chat(updatedAt = 9, synced = true), remoteChat(5)))
        assertTrue(takesRemoteMessage(message(updatedAt = 9, synced = true), remoteMessage(5), "c"))
    }

    @Test
    fun `an unsent local edit survives an older server copy and loses to a newer one`() {
        assertFalse(takesRemoteChat(chat(updatedAt = 9, synced = false), remoteChat(5)))
        assertTrue(takesRemoteChat(chat(updatedAt = 5, synced = false), remoteChat(9)))
        assertFalse(takesRemoteMessage(message(updatedAt = 9, synced = false), remoteMessage(5), "c"))
        assertTrue(takesRemoteMessage(message(updatedAt = 5, synced = false), remoteMessage(9), "c"))
    }

    @Test
    fun `a message id never moves to another chat`() {
        assertFalse(takesRemoteMessage(message(updatedAt = 1, synced = true, chatId = "other"), remoteMessage(9), "c"))
    }
}
