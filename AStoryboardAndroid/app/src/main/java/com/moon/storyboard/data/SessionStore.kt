package com.moon.storyboard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录态与 JWT 持久化。
 *
 * 用 SharedPreferences 而不是 DataStore：OkHttp 拦截器需要在同步线程里读 token，
 * 内存 + SP 的读取成本足够低，也避免拦截器里 runBlocking。
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("moon_session", Context.MODE_PRIVATE)

    private val _loggedIn = MutableStateFlow(!prefs.getString(KEY_ACCESS, null).isNullOrBlank())
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _displayName = MutableStateFlow(prefs.getString(KEY_NAME, null).orEmpty())
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    val accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)

    /** 上次打开的会话：App 重启后回到同一对话，而不是列表里最新的空会话。 */
    var lastConversationId: String?
        get() = prefs.getString(KEY_CONVERSATION, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_CONVERSATION) else putString(KEY_CONVERSATION, value)
            }.apply()
        }

    fun save(data: LoginData) {
        prefs.edit()
            .putString(KEY_ACCESS, data.accessToken)
            .putString(KEY_REFRESH, data.refreshToken)
            .putString(KEY_USER_ID, data.userId)
            .putString(KEY_NAME, data.displayName)
            .apply()
        _displayName.value = data.displayName
        _loggedIn.value = true
    }

    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _displayName.value = ""
        _loggedIn.value = false
    }

    private companion object {
        const val KEY_ACCESS = "accessToken"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_USER_ID = "userId"
        const val KEY_NAME = "displayName"
        const val KEY_CONVERSATION = "lastConversationId"
    }
}
