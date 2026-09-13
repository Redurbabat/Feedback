package com.redurbabat.feedback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.redurbabat.feedback.pairing.PairingInvitationFactory
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.ui.FeedbackApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val identityStore = DeviceIdentityStore()
        val identityResult = runCatching {
            identityStore.loadOrCreate()
        }
        val pairingFactory = PairingInvitationFactory(identityStore)

        setContent {
            FeedbackApp(
                identity = identityResult.getOrNull(),
                identityAvailable = identityResult.isSuccess,
                onCreatePairingInvitation = {
                    val identity = identityResult.getOrNull()
                        ?: error("Device identity unavailable")
                    pairingFactory.create(identity)
                },
            )
        }
    }
}
