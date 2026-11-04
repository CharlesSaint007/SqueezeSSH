package me.nick.squeezessh

import android.app.Application
import me.nick.squeezessh.session.SessionManager
import me.nick.squeezessh.settings.SettingsRepository

/** Holds the app-wide singletons. Replaced by Hilt once there are enough of them to warrant it. */
class SqueezeApp : Application() {
    val sessionManager = SessionManager()
    val settingsRepository by lazy { SettingsRepository(this) }
}
