package com.moon.storyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moon.storyboard.StoryboardApp
import com.moon.storyboard.ui.login.LoginScreen

@Composable
fun AppRoot(app: StoryboardApp) {
    val loggedIn by app.session.loggedIn.collectAsState()

    if (!loggedIn) {
        LoginScreen(api = app.api, session = app.session)
        return
    }

    val vm: MainViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MainViewModel(app.api, app.session) }
        },
    )
    MainScaffold(vm)
}
