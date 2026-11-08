package com.example.chirp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.chirp.sonar.ui.SonarScreen
import com.example.chirp.sonar.ui.SonarViewModel
import com.example.chirp.ui.theme.ChirpTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SonarViewModel by viewModels()

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.reportError("Microphone permission denied. Allow it in app settings to use the sonar.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChirpTheme { SonarScreen(viewModel, onStart = ::startWithPermission) }
        }
    }

    private fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Never chirp or record in the background: battery, privacy, and nobody is watching the output. */
    override fun onStop() {
        if (!isChangingConfigurations) viewModel.stop()
        super.onStop()
    }
}
