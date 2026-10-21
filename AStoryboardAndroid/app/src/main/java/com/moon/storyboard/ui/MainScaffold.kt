package com.moon.storyboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moon.storyboard.ui.chat.ChatScreen
import com.moon.storyboard.ui.generate.GenerationCenterScreen
import com.moon.storyboard.ui.generate.GenerationRecordsScreen
import com.moon.storyboard.ui.library.LibraryScreen
import com.moon.storyboard.ui.settings.SettingsScreen
import com.moon.storyboard.ui.theme.MoonPalette

private enum class Screen { Chat, Library, Generate, Records, Settings }

/**
 * 手机端只有一个主界面：对话。
 * 项目库、生成中心、生成记录、设置都从顶栏右上角的 ⋮ 菜单进入，不再占用底部导航。
 */
@Composable
fun MainScaffold(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableIntStateOf(Screen.Chat.ordinal) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    BackHandler(enabled = screen != Screen.Chat.ordinal) {
        screen = Screen.Chat.ordinal
    }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.dismissError()
    }

    Scaffold(
        containerColor = MoonPalette.Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Library.ordinal -> LibraryScreen(
                    state = state,
                    onBack = { screen = Screen.Chat.ordinal },
                    onRefresh = { vm.loadLibrary() },
                )

                Screen.Generate.ordinal -> GenerationCenterScreen(
                    state = state,
                    onBack = { screen = Screen.Chat.ordinal },
                    onOpenRecords = { screen = Screen.Records.ordinal },
                    onAddReference = { uri -> vm.addReference(context, uri) },
                    onRemoveReference = vm::removeReference,
                    onGenerate = { prompt, video, ratio, count ->
                        vm.generateNow(prompt, video, ratio, count)
                        screen = Screen.Chat.ordinal
                    },
                )

                Screen.Records.ordinal -> GenerationRecordsScreen(
                    state = state,
                    onBack = { screen = Screen.Chat.ordinal },
                    onRefresh = vm::loadRecords,
                )

                Screen.Settings.ordinal -> SettingsScreen(
                    state = state,
                    onBack = { screen = Screen.Chat.ordinal },
                    onSelectProject = vm::selectProject,
                    onLogout = vm::logout,
                )

                else -> ChatScreen(
                    state = state,
                    onSend = vm::send,
                    onNewConversation = vm::startNewConversation,
                    onSelectConversation = vm::selectConversation,
                    onReload = vm::reload,
                    onOpenLibrary = { screen = Screen.Library.ordinal },
                    onOpenGenerate = { screen = Screen.Generate.ordinal },
                    onOpenRecords = {
                        vm.loadRecords()
                        screen = Screen.Records.ordinal
                    },
                    onOpenSettings = { screen = Screen.Settings.ordinal },
                )
            }
        }
    }
}
