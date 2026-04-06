package com.localai.server

import android.app.Application
import com.localai.server.engine.LlmEngine

class LocalAIApp : Application() {
    val engine by lazy { LlmEngine(this) }
    val settings by lazy { SettingsManager(this) }
}
