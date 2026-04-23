package com.revvivii.mydentapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.revvivii.mydentapp.ui.theme.MyDentAppTheme
import com.revvivii.mydentapp.ui.CameraScreen

class MainActivity : ComponentActivity() {

    // Lanzador del diálogo de permisos de cámara
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Actualiza el estado cuando el usuario responde al diálogo
        hasCameraPermission = granted
    }

    private var hasCameraPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Verifica si ya tiene permiso
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // Si no tiene permiso, lo pide
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MyDentAppTheme {
                // Pasa el estado del permiso a la UI
                CameraScreen(hasCameraPermission = hasCameraPermission)
            }
        }
    }
}
