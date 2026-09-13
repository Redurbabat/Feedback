package com.redurbabat.feedback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redurbabat.feedback.ui.FeedbackApp
import com.redurbabat.feedback.ui.FeedbackController

class MainActivity : ComponentActivity() {
    private lateinit var controller: FeedbackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = FeedbackController(applicationContext)
        setContent {
            FeedbackApp(controller)
        }
    }

    /**
     * Auto-lock is driven from the activity lifecycle rather than from a timer, so the idle window
     * starts exactly when the management UI stops being visible. `onStop` also covers the recents
     * overview and the screen turning off.
     */
    override fun onStop() {
        if (::controller.isInitialized) {
            controller.onEnterBackground()
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) {
            controller.onEnterForeground()
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.close()
        }
        super.onDestroy()
    }
}
