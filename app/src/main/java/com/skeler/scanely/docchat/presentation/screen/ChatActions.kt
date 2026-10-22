package com.skeler.scanely.docchat.presentation.screen

import com.skeler.scanely.docchat.data.AskAnswer
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.docchat.data.RowOutput

/** Everything a reply can ask for. One instance per screen, so rows never capture fresh lambdas. */
internal interface ChatActions {
    fun retry(replyId: String)
    fun answer(replyId: String, answer: AskAnswer)
    fun submitFields(replyId: String, values: Map<String, String>)
    fun open(messageId: String, file: FileBody)
    fun share(messageId: String, file: FileBody)
    fun download(messageId: String, file: FileBody)
    fun saveImages(messageId: String, file: FileBody)

    /** Fetch a file this phone does not have (its thumbnail was tapped). */
    fun restore(messageId: String, file: FileBody)
    fun reviseFrom(designId: String)

    /** An expired file: its request back in the composer as a new document. */
    fun rebuild(fileMessageId: String)
    fun upgrade(reason: String, code: String?)

    /** One of the agent's suggested answers, sent as the user's next message. */
    fun sendChoice(text: String)

    /** Opens the missing-details sheet for a fields reply. */
    fun openFields(replyId: String)

    /** A row of the agent's log in full, from the server while it keeps the turn. */
    suspend fun output(turnId: String, row: String): RowOutput
}

/** Where a reply sits: whether it is the newest thing in the chat, and whether it is being revised. */
internal data class ReplyContext(
    val isLatest: Boolean,
    val isRevisionTarget: Boolean,
    /** The designer answers, so a file can be picked as the one to change; the agent needs no pick. */
    val canRevise: Boolean,
    val chatIsWorking: Boolean,
    val hasThumbnail: Boolean = false
)
