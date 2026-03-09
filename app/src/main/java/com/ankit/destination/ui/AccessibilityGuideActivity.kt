package com.ankit.destination.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.ui.components.showShortToast
import java.text.DateFormat
import java.util.Date

class AccessibilityGuideActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Accessibility Setup")
        setContentView(buildContentView())
        render()
    }

    override fun onResume() {
        super.onResume()
        AccessibilityStatusMonitor.refreshNow(
            context = applicationContext,
            reason = "accessibility_guide_resume",
            requestPolicyRefreshIfChanged = true
        )
        render()
    }

    private fun render() {
        val state = AccessibilityStatusMonitor.refreshNow(this, reason = "accessibility_guide_render")
        val compliance = PolicyEngine(applicationContext).currentAccessibilityComplianceState(state)
        val lastHeartbeat = state.lastHeartbeatAtMs.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "never"
        statusText.text = buildString {
            appendLine("Accessibility enabled: ${state.enabled}")
            appendLine("Service running: ${compliance.accessibilityServiceRunning}")
            appendLine("Last heartbeat: $lastHeartbeat")
            compliance.reason?.let {
                appendLine()
                appendLine("Status:")
                appendLine(it)
            }
            appendLine()
            appendLine("Why this is required:")
            appendLine("- Destination only checks the foreground app package name.")
            appendLine("- It does not inspect screen text or window contents.")
            appendLine("- This keeps blocking immediate even when the app is not open.")
            appendLine("- If Accessibility is unavailable, Destination switches to recovery lockdown until it comes back.")
            appendLine()
            appendLine("Grant path:")
            appendLine("Settings -> Accessibility -> Installed apps -> Destination -> On")
        }
    }

    private fun buildContentView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusText = TextView(this).apply { setTextIsSelectable(true) }
        val openSettings = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                when {
                    settingsIntent.resolveActivity(packageManager) != null -> startActivity(settingsIntent)
                    fallbackIntent.resolveActivity(packageManager) != null -> startActivity(fallbackIntent)
                    else -> showShortToast("No Settings activity found for Accessibility recovery.")
                }
            }
        }

        container.addView(statusText)
        container.addView(openSettings, lp(top = dp(12)))
        root.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = top
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
