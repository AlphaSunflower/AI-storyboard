package com.moon.storyboard

import android.app.Application
import com.moon.storyboard.data.ApiClient
import com.moon.storyboard.data.SessionStore

class StoryboardApp : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var api: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        api = ApiClient(session)
    }
}
