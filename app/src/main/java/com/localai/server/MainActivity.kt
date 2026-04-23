package com.localai.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import com.localai.server.ui.ServerScreen

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, server works either way */ }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity", "存储权限结果: granted=$granted")
    }

    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 用户从设置页面返回 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request storage permissions
        requestStoragePermissions()

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                Surface {
                    ServerScreen()
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        when {
            // Android 11+ (API 30+): 使用 MANAGE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    android.util.Log.w("MainActivity", "Android 11+: 未授予 MANAGE_EXTERNAL_STORAGE 权限")
                    requestManageExternalStoragePermission()
                } else {
                    android.util.Log.i("MainActivity", "Android 11+: 已授予 MANAGE_EXTERNAL_STORAGE 权限")
                }
            }
            // Android 6-10: 使用 READ_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.w("MainActivity", "请求 READ_EXTERNAL_STORAGE 权限")
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    android.util.Log.i("MainActivity", "已授予 READ_EXTERNAL_STORAGE 权限")
                }
            }
            // Android 5 及以下: 权限在安装时授予
            else -> {
                android.util.Log.i("MainActivity", "Android 5-: 不需要运行时权限")
            }
        }
    }

    private fun requestManageExternalStoragePermission() {
        android.util.Log.d("MainActivity", "引导用户到设置页面授予权限")
        
        // 创建 Intent 打开应用的详细信息页面
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        
        // 如果无法打开所有文件访问设置，尝试打开应用设置页面
        if (intent.resolveActivity(packageManager) == null) {
            android.util.Log.w("MainActivity", "无法打开所有文件访问设置，打开应用设置页面")
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            manageStoragePermissionLauncher.launch(fallbackIntent)
        } else {
            manageStoragePermissionLauncher.launch(intent)
        }
    }

    // 公开方法供其他组件检查权限状态
    fun hasStoragePermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Environment.isExternalStorageManager()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }

    // 公开方法供其他组件打开权限设置
    fun openStoragePermissionSettings() {
        requestManageExternalStoragePermission()
    }
}
