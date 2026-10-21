package com.moon.storyboard.data

import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LoginData(
    val accessToken: String,
    val refreshToken: String = "",
    val userId: String,
    val displayName: String,
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val status: String = "",
    val aspectRatio: String = "",
    val updatedAt: String = "",
)

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val status: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String = "",
    val createdAt: String = "",
)

@Serializable
data class LibraryAsset(
    val id: String,
    val name: String = "",
    val type: String = "",
    val description: String = "",
)

@Serializable
data class CreateConversationRequest(
    val projectId: String,
    val title: String = "新对话",
)

@Serializable
data class GenerationRecord(
    val id: String,
    val type: String = "",
    val url: String = "",
    val status: String = "",
    val prompt: String = "",
    val model: String = "",
    val genType: String = "",
    val error: String = "",
    val createdAt: String = "",
)

@Serializable
data class GenerationPage(
    val records: List<GenerationRecord> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

@Serializable
data class SendTurnRequest(
    val content: String,
    val clientRequestId: String,
    val picUrls: List<String> = emptyList(),
)

@Serializable
data class UploadResult(
    val assetId: String = "",
    val url: String = "",
    val name: String = "",
)
