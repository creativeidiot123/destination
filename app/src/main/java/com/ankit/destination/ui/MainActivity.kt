package com.ankit.destination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.theme.DestinationTheme

class MainActivity : ComponentActivity() {
    private val policyEngine by lazy(LazyThreadSafetyMode.NONE) {
        PolicyEngine(applicationContext)
    }
    private val appLockManager by lazy(LazyThreadSafetyMode.NONE) {
        AppLockManager(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DestinationTheme {
                DestinationApp(
                    policyEngine = policyEngine,
                    appLockManager = appLockManager
                )
            }
        }
    }
}
