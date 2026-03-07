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
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageAccessMonitor
import com.ankit.destination.ui.components.showShortToast

class UsageAccessGuideActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        render()
    }

    override fun onResume() {
        super.onResume()
        UsageAccessMonitor.refreshNow(
            context = applicationContext,
            reason = "usage_access_guide_resume",
            requestPolicyRefreshIfChanged = true
        )
        render()
    }

    private fun render() {
        val granted = UsageAccess.hasUsageAccess(this)
        statusText.text = buildString {
            append("Usage access granted: $granted\n\n")
            append("Grant path:\n")
            append("Settings -> Special app access -> Usage access -> Destination -> Allow")
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
            text = "Open Usage Access Settings"
            setOnClickListener {
                val usageAccessIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                when {
                    usageAccessIntent.resolveActivity(packageManager) != null -> {
                        startActivity(usageAccessIntent)
                    }
                    fallbackIntent.resolveActivity(packageManager) != null -> {
                        startActivity(fallbackIntent)
                    }
                    else -> showShortToast("No Settings activity found for Usage Access recovery.")
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
