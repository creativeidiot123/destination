package com.ankit.destination.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ankit.destination.R
import com.ankit.destination.policy.ProvisioningCoordinator
import java.text.DateFormat
import java.util.Date

class EnrollmentGuideActivity : AppCompatActivity() {
    private lateinit var content: TextView
    private lateinit var copyButton: Button
    private lateinit var coordinator: ProvisioningCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ProvisioningCoordinator(this)
        setContentView(buildContentView())
        render()
    }

    private fun render() {
        val snapshot = coordinator.snapshot()
        val qrPayload = if (snapshot.qrValidation.isReady) coordinator.qrPayload() else null
        val lastSignal = snapshot.lastSignalAtMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "never"
        val lastFinalization = snapshot.lastFinalizationAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "never"

        copyButton.isEnabled = snapshot.qrValidation.isReady
        content.text = buildString {
            appendLine("Device Owner active: ${snapshot.deviceOwnerActive}")
            appendLine("Admin active: ${snapshot.adminActive}")
            appendLine("Admin component: ${snapshot.adminComponent}")
            appendLine("QR provisioning ready: ${snapshot.qrValidation.isReady}")
            if (snapshot.qrValidation.errors.isNotEmpty()) {
                appendLine("Validation errors:")
                snapshot.qrValidation.errors.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Provisioning status:")
            appendLine("- Last signal: ${snapshot.lastSignalAction ?: "none"} at $lastSignal")
            appendLine("- Last source: ${snapshot.lastSource ?: "unknown"}")
            appendLine("- Last finalization: ${snapshot.lastFinalizationState ?: "none"} at $lastFinalization")
            appendLine("- Message: ${snapshot.lastFinalizationMessage ?: "No provisioning finalization has run yet."}")
            appendLine()
            appendLine("Production provisioning (Android Enterprise managed device / QR):")
            appendLine("1. Factory reset the test device.")
            appendLine("2. In Setup Wizard, tap the first screen 6 times to open QR provisioning.")
            appendLine("3. Scan the generated QR payload.")
            appendLine("4. Complete policy compliance and open Destination.")
            appendLine()
            appendLine("QR payload:")
            appendLine(qrPayload ?: "<blocked until ProvisioningConfig is valid>")
            appendLine()
            appendLine("Dev-only fallback (fresh device):")
            appendLine("adb shell dpm set-device-owner ${snapshot.adminComponent}")
            appendLine("adb shell dpm get-device-owner")
            appendLine()
            appendLine("Notes:")
            appendLine("- QR copy is disabled until the download URL and checksum are valid.")
            appendLine("- Android 12+ enrollment uses the in-app compliance activity during provisioning.")
            appendLine("- If Device Owner is not active, all enforcement controls stay blocked.")
        }
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        copyButton = Button(this).apply {
            text = getString(R.string.copy_qr_json)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Provisioning QR", coordinator.qrPayload()))
                Toast.makeText(this@EnrollmentGuideActivity, "QR JSON copied", Toast.LENGTH_SHORT).show()
            }
        }
        content = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(copyButton)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                topMargin = spacing
                weight = 1f
            }
        )
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
