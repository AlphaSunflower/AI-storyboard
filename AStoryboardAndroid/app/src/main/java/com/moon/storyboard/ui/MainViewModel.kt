package com.moon.storyboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.moon.storyboard.data.ApiClient
import com.moon.storyboard.data.ApiException
import com.moon.storyboard.data.ChatMessage
import com.moon.storyboard.data.Conversation
import com.moon.storyboard.data.CreateConversationRequest
import com.moon.storyboard.data.GenerationPage
import com.moon.storyboard.data.GenerationRecord
import com.moon.storyboard.data.LibraryAsset
import com.moon.storyboard.data.Project
import com.moon.storyboard.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID

data class MainUiState(
    val bootstrapped: Boolean = false,
    val loading: Boolean = true,
    val displayName: String = "",
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val sending: Boolean = false,
    val creatingConversation: Boolean = false,
    val error: String? = null,
    val records: List<GenerationRecord> = emptyList(),
    val recordsLoading: Boolean = false,
    val library: List<LibraryAsset> = emptyList(),
    val libraryLoading: Boolean = false,
    val referenceUrls: List<String> = emptyList(),
    val uploadingReference: Boolean = false,
)

class MainViewModel(
    private val api: ApiClient,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState(displayName = session.displayName.value))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    fun bootstrap() {
        if (_state.value.bootstrapped) return
        _state.update { it.copy(bootstrapped = true, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val projects = api.get<List<Project>>("/projects")
                val project = projects.firstOrNull()
                if (project == null) {
                    _state.update {
                        it.copy(projects = emptyList(), project = null, loading = false)
                    }
                    return@launch
                }
                val conversations = api.get<List<Conversation>>(
                    "/agent/conversations?projectId=${project.id}",
                )
                val active = conversations.firstOrNull { it.id == session.lastConversationId }
                    ?: conversations.firstOrNull { it.title != "新对话" }
                    ?: conversations.firstOrNull()
                session.lastConversationId = active?.id
                _state.update {
                    it.copy(
                        projects = projects,
                        project = project,
                        conversations = visibleConversations(conversations, active?.id),
                        activeConversationId = active?.id,
                        loading = false,
                    )
                }
                if (active != null) {
                    loadMessages(active.id)
                }
                loadLibrary(project.id)
                loadRecords()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = readable(e)) }
            }
        }
    }

    fun selectProject(project: Project) {
        if (project.id == _state.value.project?.id) return
        _state.update {
            it.copy(
                project = project,
                conversations = emptyList(),
                activeConversationId = null,
                messages = emptyList(),
                streamingText = "",
                library = emptyList(),
            )
        }
        viewModelScope.launch {
            try {
                val conversations = api.get<List<Conversation>>(
                    "/agent/conversations?projectId=${project.id}",
                )
                val active = conversations.firstOrNull { it.id == session.lastConversationId }
                    ?: conversations.firstOrNull { it.title != "新对话" }
                    ?: conversations.firstOrNull()
                session.lastConversationId = active?.id
                _state.update {
                    it.copy(
                        conversations = visibleConversations(conversations, active?.id),
                        activeConversationId = active?.id,
                    )
                }
                if (active != null) loadMessages(active.id)
                loadLibrary(project.id)
            } catch (e: Exception) {
                _state.update { it.copy(error = readable(e)) }
            }
        }
    }

    fun selectConversation(id: String) {
        if (id == _state.value.activeConversationId) return
        session.lastConversationId = id
        _state.update { it.copy(activeConversationId = id, messages = emptyList(), streamingText = "") }
        loadMessages(id)
    }

    /**
     * 点 ＋ 只清空当前视图，不预建服务端会话；首条消息发送时才真正创建，
     * 避免每次点＋都堆一条空「新对话」。
     */
    fun startNewConversation() {
        session.lastConversationId = null
        _state.update {
            it.copy(
                activeConversationId = null,
                messages = emptyList(),
                streamingText = "",
                error = null,
            )
        }
    }

    /**
     * 会话按需创建：首屏不再预建空会话，避免「新对话」堆一屏。
     * 首条消息发送时才会落一条真实会话。
     */
    private suspend fun ensureConversation(
        title: String = "新对话",
        clearMessages: Boolean = false,
        forceNew: Boolean = false,
    ): String? {
        if (!forceNew) {
            _state.value.activeConversationId?.let { return it }
        }
        val project = _state.value.project ?: return null
        return try {
            val conversation = api.post<Conversation>(
                "/agent/conversations",
                api.json.encodeToString(CreateConversationRequest(project.id, title)),
            )
            _state.update {
                it.copy(
                    conversations = listOf(conversation) + it.conversations,
                    activeConversationId = conversation.id,
                    messages = if (clearMessages) emptyList() else it.messages,
                    error = null,
                )
            }
            session.lastConversationId = conversation.id
            conversation.id
        } catch (e: Exception) {
            _state.update { it.copy(error = readable(e)) }
            null
        }
    }

    fun send(text: String, picUrls: List<String> = emptyList()) {
        val content = text.trim()
        if (content.isEmpty() || _state.value.sending) return

        val localUser = ChatMessage(
            id = "local-user-${UUID.randomUUID()}",
            role = "user",
            content = content,
        )
        _state.update {
            it.copy(
                messages = it.messages + localUser,
                sending = true,
                streamingText = "",
                error = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val conversationId = _state.value.activeConversationId
                ?: ensureConversation("新对话")
                ?: run {
                    _state.update { it.copy(sending = false, error = "无法创建会话，请稍后重试") }
                    return@launch
                }
            api.streamTurn(
                conversationId = conversationId,
                content = content,
                picUrls = picUrls,
                onDelta = { partial ->
                    _state.update { it.copy(streamingText = partial) }
                },
                onComplete = { finalContent ->
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                id = "local-assistant-${UUID.randomUUID()}",
                                role = "assistant",
                                content = finalContent,
                            ),
                            streamingText = "",
                            sending = false,
                        )
                    }
                    loadRecords()
                },
                onError = { message ->
                    _state.update {
                        it.copy(sending = false, streamingText = "", error = message)
                    }
                },
            )
        }
    }

    /** 生成中心：新建一条会话并发起一轮生成，完成后由调用方切到对话。 */
    fun generateNow(prompt: String, video: Boolean, ratio: String, count: Int) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val refs = _state.value.referenceUrls
        _state.update { it.copy(creatingConversation = true, error = null) }
        val instruction = if (video) {
            "生成一段 $ratio 的短视频，要求：$clean"
        } else {
            "生成 $count 张 $ratio 的概念图，要求：$clean"
        }
        viewModelScope.launch {
            val conversationId = try {
                ensureConversation("生成 · ${clean.take(12)}", clearMessages = true, forceNew = true)
            } finally {
                _state.update { it.copy(creatingConversation = false) }
            }
            if (conversationId != null) {
                _state.update { it.copy(referenceUrls = emptyList()) }
                send(instruction, refs)
            }
        }
    }

    /** 从相册选一张参考素材，上传后作为本轮 picUrls 传给后端。 */
    fun addReference(context: Context, uri: Uri) {
        if (_state.value.uploadingReference) return
        if (_state.value.referenceUrls.size >= 5) {
            _state.update { it.copy(error = "参考素材最多 5 张") }
            return
        }
        _state.update { it.copy(uploadingReference = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取这张图片")
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    else -> "jpg"
                }
                val uploaded = api.uploadImage(
                    bytes,
                    "reference-${System.currentTimeMillis()}.$ext",
                    mime,
                )
                _state.update {
                    it.copy(
                        referenceUrls = it.referenceUrls + uploaded.url,
                        uploadingReference = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(uploadingReference = false, error = readable(e)) }
            }
        }
    }

    fun removeReference(url: String) {
        _state.update { it.copy(referenceUrls = it.referenceUrls - url) }
    }

    fun loadRecords() {
        _state.update { it.copy(recordsLoading = true) }
        viewModelScope.launch {
            try {
                val page = api.get<GenerationPage>("/agent/generation-records?page=1&size=50")
                _state.update { it.copy(records = page.records, recordsLoading = false) }
            } catch (e: Exception) {
                android.util.Log.w("Moon", "loadRecords failed", e)
                _state.update { it.copy(recordsLoading = false) }
            }
        }
    }

    fun loadLibrary(projectId: String? = _state.value.project?.id) {
        if (projectId.isNullOrBlank()) return
        _state.update { it.copy(libraryLoading = true) }
        viewModelScope.launch {
            try {
                val assets = api.get<List<LibraryAsset>>("/assets?projectId=$projectId")
                _state.update { it.copy(library = assets, libraryLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(libraryLoading = false) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** 设置里切换项目后、或无项目时手动重新检查。 */
    fun reload() {
        _state.update { it.copy(bootstrapped = false, loading = true, error = null) }
        bootstrap()
    }

    fun logout() {
        session.clear()
    }

    private fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            try {
                val messages = api.get<List<ChatMessage>>(
                    "/agent/conversations/$conversationId/messages",
                )
                if (_state.value.activeConversationId != conversationId) return@launch
                _state.update { it.copy(messages = messages) }
            } catch (e: Exception) {
                _state.update { it.copy(error = readable(e)) }
            }
        }
    }

    private fun visibleConversations(
        list: List<Conversation>,
        activeId: String?,
    ): List<Conversation> = list.filter { it.title != "新对话" || it.id == activeId }

    private fun readable(error: Throwable): String = when (error) {
        is ApiException -> error.message
        else -> error.message ?: "网络连接失败"
    }
}
