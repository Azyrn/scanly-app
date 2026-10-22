package com.skeler.scanely.docchat.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A web turn's open question to the person, in the chat it belongs to. */
data class PendingApproval(val chatId: String, val turnId: String, val approval: AgentApprovalDto)

/**
 * The question a web turn is waiting on (runner/agent/webgate.py), from the turn's stream ([AgentTurns])
 * to the chat screen's sheet, and the person's answer back. The server decides: a question it has
 * closed (answered, timed out, the turn ended) refuses a late answer, and one nobody answers is denied.
 */
@Singleton
class AgentApprovals @Inject internal constructor(private val agent: AgentClient) {
    private val open = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = open.asStateFlow()

    fun ask(chatId: String, turnId: String, approval: AgentApprovalDto) {
        open.value = PendingApproval(chatId, turnId, approval)
    }

    /** The server closed [approvalId], or its time ran out here: the sheet goes. */
    fun closed(approvalId: String) {
        open.update { it?.takeUnless { pending -> pending.approval.id == approvalId } }
    }

    fun turnEnded(turnId: String) {
        open.update { it?.takeUnless { pending -> pending.turnId == turnId } }
    }

    /** Sends the answer; the sheet goes at once, whatever the server says (a closed question is a no). */
    suspend fun answer(approvalId: String, allow: Boolean) {
        val asked = open.value?.takeIf { it.approval.id == approvalId } ?: return
        closed(approvalId)
        agent.answerApproval(asked.turnId, approvalId, allow)
    }
}
