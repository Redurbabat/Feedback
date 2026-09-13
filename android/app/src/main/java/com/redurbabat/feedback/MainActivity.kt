package com.redurbabat.feedback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.ui.FeedbackApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val identityResult = runCatching {
            DeviceIdentityStore().loadOrCreate()
        }

        setContent {
            FeedbackApp(
                identity = identityResult.getOrNull(),
                identityAvailable = identityResult.isSuccess,
            )
        }
    }
}
