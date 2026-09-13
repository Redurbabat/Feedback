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

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.close()
        }
        super.onDestroy()
    }
}
