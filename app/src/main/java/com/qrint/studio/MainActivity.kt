package com.qrint.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.qrint.studio.ui.QrintApp
import com.qrint.studio.ui.theme.QrintTheme
import kotlinx.coroutines.flow.MutableStateFlow

data class IncomingShare(
    val token: Long,
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
)

class MainActivity : ComponentActivity() {
    private val incomingShare = MutableStateFlow<IncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingShare.value = intent.toIncomingShare()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val preferences by (application as QrintApplication).localStore.appPreferences.collectAsState()
            val shared by incomingShare.collectAsState()
            QrintTheme(preferences.theme) {
                QrintApp(
                    incomingShare = shared,
                    onIncomingShareConsumed = { incomingShare.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShare.value = intent.toIncomingShare()
    }

    @Suppress("DEPRECATION")
    private fun Intent.toIncomingShare(): IncomingShare? {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null
        val sharedUri = data ?: getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        val name = runCatching {
            contentResolver.query(
                sharedUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
            ?: sharedUri.lastPathSegment?.substringAfterLast('/')
            ?: "分享文件"
        return IncomingShare(
            token = System.nanoTime(),
            uri = sharedUri,
            mimeType = type.orEmpty().ifBlank { contentResolver.getType(sharedUri).orEmpty() },
            displayName = name,
        )
    }
}
