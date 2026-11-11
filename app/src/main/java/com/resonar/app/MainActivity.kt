package com.resonar.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.resonar.app.ui.HomeScreen
import com.resonar.app.ui.ResonarViewModel
import com.resonar.app.ui.theme.ResonarTheme
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Entrada de la app (Etapa 1).
 * Permisos:
 *  - POST_NOTIFICATIONS (Android 13+): para la notificación multimedia.
 *  - READ_MEDIA_AUDIO (Android 13+) / READ_EXTERNAL_STORAGE (≤12): para leer la música.
 * El botón "Dar permiso de música" del ViewModel re-dispara el pedido si el usuario lo denegó.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ResonarViewModel by viewModels()

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Sin notificación el audio sigue; no insistimos.
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.onMusicFolderPicked(uri)
        }

    /** Borrar canciones del teléfono (Android 11+): la confirmación la hace el sistema. */
    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onSystemDeleteResult(result.resultCode == android.app.Activity.RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        askNotificationPermissionIfNeeded()

        // Biblioteca: si ya hay permiso, escaneamos al abrir; si no, lo pedimos.
        if (hasAudioPermission()) {
            viewModel.onPermissionResult(true)
        } else {
            askMediaPermission()
        }

        // El botón de la pantalla de permisos pide de nuevo (StateFlow = contador).
        // drop(1): el primer valor (0) ya lo cubrimos con el pedido automático de arriba.
        lifecycleScope.launch {
            viewModel.permissionRequests.drop(1).collect { askMediaPermission() }
        }
        lifecycleScope.launch {
            viewModel.folderPickRequests.drop(1).collect { openMusicFolderPicker() }
        }
        lifecycleScope.launch {
            viewModel.systemDeleteRequest.drop(1).filterNotNull().collect { req -> deleteRequestLauncher.launch(req) }
        }

        setContent {
            // El tema sale del estado persistido: "dark" por defecto (una app de música
            // se ve mejor oscura), con opción Claro o Sistema desde Ajustes.
            val playback by viewModel.playbackState.collectAsState()
            val darkTheme = when (playback.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ResonarTheme(darkTheme = darkTheme) {
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
                HomeScreen(viewModel)
            }
        }
    }

    /** Permiso de audio según versión de Android. */
    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun askMediaPermission() {
        if (!hasAudioPermission()) mediaPermissionLauncher.launch(audioPermission())
    }

    /** El selector abre en Music. El usuario puede ir a otra carpeta. */
    private fun openMusicFolderPicker() {
        val initial = runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${com.resonar.app.data.MusicPlaces.APP_FOLDER}",
            )
        }.getOrNull()
        runCatching { folderLauncher.launch(initial) }
            .onFailure { folderLauncher.launch(null) }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
