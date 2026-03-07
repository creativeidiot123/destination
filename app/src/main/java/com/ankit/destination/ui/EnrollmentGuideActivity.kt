package com.ankit.destination.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.R
import com.ankit.destination.provisioning.DeviceOwnerSetupController
import com.ankit.destination.provisioning.DeviceOwnerSetupSnapshot
import com.ankit.destination.provisioning.ShellCommandResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date

class EnrollmentGuideActivity : AppCompatActivity() {
    private lateinit var controller: DeviceOwnerSetupController

    private lateinit var statusText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var outputText: TextView
    private lateinit var copyQrButton: Button
    private lateinit var copyAdbButton: Button
    private lateinit var requestShizukuButton: Button
    private lateinit var openUsageAccessButton: Button
    private lateinit var activateViaShizukuButton: Button
    private lateinit var verifyDoButton: Button
    private lateinit var copyOutputButton: Button
    private lateinit var refreshButton: Button

    private var lastResult: ShellCommandResult? = null
    private var shellJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = DeviceOwnerSetupController(this)
        setTitle(R.string.provisioning_guide)
        setContentView(buildContentView())
        wireButtons()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        shellJob?.cancel()
        shellJob = null
        super.onDestroy()
    }

    private fun wireButtons() {
        copyQrButton.setOnClickListener {
            val snapshot = controller.snapshot()
            val qrPayload = snapshot.qrPayload
            if (qrPayload.isNullOrBlank()) {
                Toast.makeText(this, "QR payload is not ready yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("Provisioning QR", qrPayload)
            Toast.makeText(this, "QR JSON copied", Toast.LENGTH_SHORT).show()
        }
        copyAdbButton.setOnClickListener {
            val snapshot = controller.snapshot()
            copyToClipboard("ADB device-owner command", "adb shell ${snapshot.adbSetDeviceOwnerCommand}")
            Toast.makeText(this, "ADB command copied", Toast.LENGTH_SHORT).show()
        }
        requestShizukuButton.setOnClickListener {
            val status = controller.shizukuStatus()
            when {
                !status.serviceAvailable -> {
                    Toast.makeText(
                        this,
                        "Start Shizuku first, then return here and refresh.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                status.permissionGranted -> {
                    Toast.makeText(this, "Shizuku is already authorized", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    Toast.makeText(
                        this,
                        "Approve Destination inside Shizuku, then come back and refresh.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            render()
        }
        openUsageAccessButton.setOnClickListener {
            startActivity(Intent(this, UsageAccessGuideActivity::class.java))
        }
        activateViaShizukuButton.setOnClickListener {
            runShellCommand("activate-device-owner") { controller.runShizukuSetDeviceOwner() }
        }
        verifyDoButton.setOnClickListener {
            runShellCommand("verify-device-owner") { controller.runShizukuVerifyDeviceOwner() }
        }
        copyOutputButton.setOnClickListener {
            val result = lastResult ?: run {
                Toast.makeText(this, "No command output yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard(
                "Device owner command output",
                buildString {
                    appendLine(result.summary)
                    appendLine()
                    appendLine("Command: ${result.command}")
                    appendLine("Exit code: ${result.exitCode}")
                    appendLine("STDOUT:")
                    appendLine(result.stdout.ifBlank { "<empty>" })
                    appendLine()
                    appendLine("STDERR:")
                    appendLine(result.stderr.ifBlank { "<empty>" })
                }
            )
            Toast.makeText(this, "Command output copied", Toast.LENGTH_SHORT).show()
        }
        refreshButton.setOnClickListener { render() }
    }

    private fun runShellCommand(
        operation: String,
        block: suspend () -> ShellCommandResult
    ) {
        if (shellJob?.isActive == true) return
        shellJob = lifecycleScope.launch {
            setBusy(true)
            try {
                lastResult = block()
                Toast.makeText(
                    this@EnrollmentGuideActivity,
                    lastResult?.summary ?: operation,
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setBusy(false)
                render()
            }
        }
    }

    private fun render() {
        val snapshot = controller.snapshot()
        renderStatus(snapshot)
        renderInstructions(snapshot)
        renderOutput(snapshot)
        val shizukuReady = snapshot.shizukuStatus.serviceAvailable && snapshot.shizukuStatus.permissionGranted
        copyQrButton.isEnabled = snapshot.qrReady
        requestShizukuButton.isEnabled = !snapshot.shizukuStatus.permissionGranted
        openUsageAccessButton.isEnabled = shellJob?.isActive != true
        activateViaShizukuButton.isEnabled =
            shizukuReady &&
                snapshot.usageAccessGranted &&
                !snapshot.deviceOwnerActive &&
                shellJob?.isActive != true
        verifyDoButton.isEnabled = shizukuReady && shellJob?.isActive != true
        copyOutputButton.isEnabled = lastResult != null
        refreshButton.isEnabled = shellJob?.isActive != true
    }

    private fun renderStatus(snapshot: DeviceOwnerSetupSnapshot) {
        val shizukuStatus = snapshot.shizukuStatus
        statusText.text = buildString {
            appendLine("Admin active: ${snapshot.adminActive}")
            appendLine("Device Owner active: ${snapshot.deviceOwnerActive}")
            appendLine("Usage Access granted: ${snapshot.usageAccessGranted}")
            appendLine("Admin component: ${snapshot.adminComponent}")
            appendLine("QR provisioning ready: ${snapshot.qrReady}")
            appendLine("Shizuku available: ${shizukuStatus.serviceAvailable}")
            appendLine("Shizuku authorized: ${shizukuStatus.permissionGranted}")
            appendLine("Shizuku backend: ${shizukuStatus.backendLabel}")
            appendLine("Shizuku detail: ${shizukuStatus.detail}")
        }
    }

    private fun renderInstructions(snapshot: DeviceOwnerSetupSnapshot) {
        val now = DateFormat.getDateTimeInstance().format(Date())
        instructionsText.text = buildString {
            appendLine("Checked at: $now")
            appendLine()
            appendLine("Production path (Android Enterprise managed device / QR):")
            appendLine("1. Factory reset the device.")
            appendLine("2. On the first Setup Wizard screen, tap 6 times to open QR provisioning.")
            appendLine("3. Scan the QR payload below or copy it into your enrollment tooling.")
            appendLine("4. Grant Usage Access to Destination during compliance.")
            appendLine("5. Complete policy compliance and open Destination.")
            appendLine()
            appendLine("QR payload:")
            appendLine(snapshot.qrPayload ?: "<blocked until ProvisioningConfig is valid>")
            if (snapshot.qrErrors.isNotEmpty()) {
                appendLine()
                appendLine("QR validation errors:")
                snapshot.qrErrors.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("ADB path (fresh device only):")
            appendLine("adb shell ${snapshot.adbSetDeviceOwnerCommand}")
            appendLine(snapshot.adbVerifyCommand)
            appendLine()
            appendLine("Shizuku path:")
            appendLine("1. Start Shizuku.")
            appendLine("2. Authorize Destination in Shizuku.")
            appendLine("3. Grant Usage Access to Destination.")
            appendLine("4. Run the same on-device shell command:")
            appendLine(snapshot.adbSetDeviceOwnerCommand)
            appendLine("5. Verify with:")
            appendLine("dpm get-device-owner")
            appendLine()
            appendLine("Notes:")
            appendLine("- ADB and Shizuku both use the same shell-level device-policy command.")
            appendLine("- Destination cannot auto-grant Usage Access; Android requires a user-managed special-access toggle.")
            appendLine("- Device owner assignment only works on a fresh or factory-reset device with no accounts.")
            appendLine("- Shizuku supports rooted devices on all Android versions and non-rooted Android 11+ devices.")
            appendLine("- If Device Owner is already active, do not rerun set-device-owner.")
        }
    }

    private fun renderOutput(snapshot: DeviceOwnerSetupSnapshot) {
        val result = lastResult
        outputText.text = buildString {
            appendLine("Last shell result:")
            if (result == null) {
                appendLine("No Shizuku command has been run yet.")
            } else {
                appendLine("Summary: ${result.summary}")
                appendLine("Command: ${result.command}")
                appendLine("Exit code: ${result.exitCode}")
                appendLine("STDOUT:")
                appendLine(result.stdout.ifBlank { "<empty>" })
                appendLine()
                appendLine("STDERR:")
                appendLine(result.stderr.ifBlank { "<empty>" })
            }
            appendLine()
            appendLine("Current Device Owner state: ${snapshot.deviceOwnerActive}")
        }
    }

    private fun setBusy(isBusy: Boolean) {
        refreshButton.isEnabled = !isBusy
        activateViaShizukuButton.isEnabled = !isBusy
        verifyDoButton.isEnabled = !isBusy
        copyAdbButton.isEnabled = !isBusy
        openUsageAccessButton.isEnabled = !isBusy
        requestShizukuButton.isEnabled = !isBusy
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        statusText = monospaceText()
        instructionsText = monospaceText()
        outputText = monospaceText()

        refreshButton = Button(this).apply { text = getString(R.string.refresh) }
        copyQrButton = Button(this).apply { text = getString(R.string.copy_qr_json) }
        copyAdbButton = Button(this).apply { text = getString(R.string.copy_command) }
        requestShizukuButton = Button(this).apply { text = getString(R.string.request_shizuku_permission) }
        openUsageAccessButton = Button(this).apply { text = "Open Usage Access" }
        activateViaShizukuButton = Button(this).apply { text = getString(R.string.run_shizuku_activation) }
        verifyDoButton = Button(this).apply { text = getString(R.string.verify_device_owner) }
        copyOutputButton = Button(this).apply { text = getString(R.string.copy_output) }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(refreshButton)
            addView(copyQrButton, lp(top = spacing))
            addView(copyAdbButton, lp(top = spacing))
            addView(requestShizukuButton, lp(top = spacing))
            addView(openUsageAccessButton, lp(top = spacing))
            addView(activateViaShizukuButton, lp(top = spacing))
            addView(verifyDoButton, lp(top = spacing))
            addView(copyOutputButton, lp(top = spacing))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("Status"))
            addView(statusText, lp(top = spacing))
            addView(section("Actions"), lp(top = dp(16)))
            addView(actions, lp(top = spacing))
            addView(section("Instructions"), lp(top = dp(16)))
            addView(instructionsText, lp(top = spacing))
            addView(section("Last Output"), lp(top = dp(16)))
            addView(outputText, lp(top = spacing))
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
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        )
        return root
    }

    private fun monospaceText(): TextView {
        return TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
