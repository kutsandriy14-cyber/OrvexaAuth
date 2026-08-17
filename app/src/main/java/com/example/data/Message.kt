package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Message exchanged through the public OrvexaAuth API. */
data class Message(
    val id: Int = 0,
    val senderEmail: String,
    val receiverEmail: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Compatibility adapter for the existing chat UI.
 * Messages are fetched from and sent to the Cloudflare Worker; no Firebase or
 * local message database is used in OrvexaAuth Beta.
 */
class MessageDao(context: Context) {
    private val clientManager = NetAuthClientManager(context)

    private suspend fun fetchMessages(user1: String, user2: String): List<Message> = runCatching {
        clientManager.getService().getMessages(user1, user2).map {
            Message(
                id = it.id,
                senderEmail = it.senderEmail,
                receiverEmail = it.receiverEmail,
                text = it.text,
                timestamp = it.timestamp
            )
        }.sortedBy { it.timestamp }
    }.getOrDefault(emptyList())

    fun getChatMessages(user1: String, user2: String): Flow<List<Message>> = flow {
        emit(fetchMessages(user1, user2))
    }

    suspend fun getChatMessagesList(user1: String, user2: String): List<Message> =
        fetchMessages(user1, user2)

    /** The beta API does not expose a global message index, so partners are unknown until opened. */
    fun getChatPartners(userEmail: String): Flow<List<String>> = flow {
        emit(emptyList())
    }

    suspend fun insertMessage(message: Message) {
        runCatching {
            clientManager.getService().sendMessage(
                SendMessageRequest(
                    senderEmail = message.senderEmail,
                    receiverEmail = message.receiverEmail,
                    text = message.text
                )
            )
        }
    }

    /** Message deletion is intentionally unavailable in the public beta contract. */
    suspend fun deleteChat(user1: String, user2: String) {
        // No local fallback and no destructive public endpoint.
    }

    suspend fun deleteMessage(messageId: Int) {
        // No local fallback and no destructive public endpoint.
    }
}
