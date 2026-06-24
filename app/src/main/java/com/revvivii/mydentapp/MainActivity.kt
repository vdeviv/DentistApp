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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    private var hasCameraPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MyDentAppTheme {
                CameraScreen(hasCameraPermission = hasCameraPermission)
            }
        }
    }
}