package com.localai.server

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings via SharedPreferences.
 * All settings survive app restart and device reboot.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("local_ai_settings", Context.MODE_PRIVATE)

    // ── Auto-start ──

    /** Flag: user wants server running. Used for auto-recovery after process kill. */
    var serverShouldBeRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVER_SHOULD_RUN, false)
        set(v) = prefs.edit().putBoolean(KEY_SERVER_SHOULD_RUN, v).apply()

    /** Auto-load last model + start server when app opens */
    var autoStartOnOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START_ON_OPEN, v).apply()

    /** Auto-start server when device boots */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, v).apply()

    // ── Keep alive ──

    /** Acquire WakeLock to prevent CPU sleep while server runs */
    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, v).apply()

    // ── Last model ──

    /** Path of the last loaded model (for auto-load) */
    var lastModelPath: String
        get() = prefs.getString(KEY_LAST_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_MODEL_PATH, v).apply()

    // ── Inference settings ──

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(v) = prefs.edit().putInt(KEY_MAX_TOKENS, v).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(v) = prefs.edit().putFloat(KEY_TEMPERATURE, v).apply()

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, 64)
        set(v) = prefs.edit().putInt(KEY_TOP_K, v).apply()

    var topP: Float
        get() = prefs.getFloat(KEY_TOP_P, 0.95f)
        set(v) = prefs.edit().putFloat(KEY_TOP_P, v).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SYSTEM_PROMPT, v).apply()

    // ── Server ──

    var preferredPort: Int
        get() = prefs.getInt(KEY_PREFERRED_PORT, 8765)
        set(v) = prefs.edit().putInt(KEY_PREFERRED_PORT, v).apply()

    companion object {
        private const val KEY_SERVER_SHOULD_RUN = "server_should_run"
        private const val KEY_AUTO_START_ON_OPEN = "auto_start_on_open"
        private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_LAST_MODEL_PATH = "last_model_path"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_PREFERRED_PORT = "preferred_port"
    }
}
