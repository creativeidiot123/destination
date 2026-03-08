package com.ankit.destination.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.R
import com.ankit.destination.policy.ProvisioningCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class ProvisioningComplianceActivity : AppCompatActivity() {
    private lateinit var content: TextView
    private lateinit var primaryButton: Button
    private lateinit var refreshButton: Button
    private lateinit var usageAccessButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var coordinator: ProvisioningCoordinator
    private var latestResult: ProvisioningCoordinator.FinalizationResult? = null
    private var finalizationInFlight: Boolean = false
    private var finalizationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ProvisioningCoordinator(this)
        setTitle(R.string.provisioning_compliance_title)
        setContentView(buildContentView())
        primaryButton.setOnClickListener { handlePrimaryAction() }
        refreshButton.setOnClickListener { finalizeProvisioning() }
        usageAccessButton.setOnClickListener {
            startActivity(Intent(this, UsageAccessGuideActivity::class.java))
        }
        accessibilityButton.setOnClickListener {
            startActivity(Intent(this, AccessibilityGuideActivity::class.java))
        }
        finalizeProvisioning()
    }

    private fun finalizeProvisioning() {
        if (finalizationJob?.isActive == true) return

        finalizationInFlight = true
        render()

        val trigger = intent.getStringExtra(ProvisioningCoordinator.extraSourceAction) ?: intent.action ?: "manual"
        val adminExtras = ProvisioningCoordinator.extractAdminExtras(intent)
        finalizationJob = lifecycleScope.launch {
            try {
                latestResult = withContext(Dispatchers.IO) {
                    coordinator.finalizeProvisioning(trigger, adminExtras)
                }
            } finally {
                finalizationInFlight = false
                render()
            }
        }
    }

    private fun render() {
        val snapshot = coordinator.snapshot()
        val action = intent.action ?: ProvisioningCoordinator.actionShowProvisioningStatus
        val finalization = latestResult
        val finalizationState = finalization?.state ?: snapshot.lastFinalizationState
        val finalizationMessage =
            finalization?.message ?: snapshot.lastFinalizationMessage ?: "Waiting to run finalization."
        val sourceAction = intent.getStringExtra(ProvisioningCoordinator.extraSourceAction)
        val finalizationAt = snapshot.lastFinalizationAtMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
        val signalAt = snapshot.lastSignalAtMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) }

        content.text = buildString {
            appendLine("Entry action: $action")
            if (!sourceAction.isNullOrBlank()) {
                appendLine("Provisioning source action: $sourceAction")
            }
            appendLine("Admin active: ${snapshot.adminActive}")
            appendLine("Device Owner active: ${snapshot.deviceOwnerActive}")
            appendLine("Usage Access granted: ${snapshot.usageAccessGranted}")
            appendLine("Accessibility enabled: ${snapshot.accessibilityServiceEnabled}")
            appendLine("Accessibility running: ${snapshot.accessibilityServiceRunning}")
            appendLine("Admin component: ${snapshot.adminComponent}")
            appendLine()
            appendLine("QR config ready: ${snapshot.qrValidation.isReady}")
            if (snapshot.qrValidation.errors.isNotEmpty()) {
                appendLine("QR validation errors:")
                snapshot.qrValidation.errors.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Last provisioning signal: ${snapshot.lastSignalAction ?: "none"}")
            appendLine("Last signal time: ${signalAt ?: "never"}")
            appendLine("Provisioning source: ${snapshot.lastSource ?: "unknown"}")
            appendLine("Enrollment ID: ${snapshot.lastEnrollmentId ?: "unknown"}")
            appendLine("Schema version: ${snapshot.lastSchemaVersion?.toString() ?: "unknown"}")
            appendLine()
            appendLine("Finalization running: $finalizationInFlight")
            appendLine("Finalization state: ${finalizationState ?: "pending"}")
            appendLine("Finalization message: $finalizationMessage")
            appendLine("Finalization time: ${finalizationAt ?: "never"}")
            val issues = finalization?.verificationIssues.orEmpty()
            if (issues.isNotEmpty()) {
                appendLine("Verification issues:")
                issues.forEach { appendLine("- $it") }
            }
        }

        primaryButton.text = when (action) {
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> getString(R.string.complete_enrollment)
            else -> getString(R.string.open_destination)
        }
        primaryButton.isEnabled = when (action) {
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                !finalizationInFlight && finalizationState == ProvisioningCoordinator.FinalizationState.SUCCESS
            }
            else -> !finalizationInFlight
        }
        refreshButton.isEnabled = !finalizationInFlight
        usageAccessButton.isEnabled = !finalizationInFlight
        accessibilityButton.isEnabled = !finalizationInFlight
    }

    private fun handlePrimaryAction() {
        if (finalizationInFlight) return

        when (intent.action) {
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                val finalizationState = latestResult?.state ?: coordinator.snapshot().lastFinalizationState
                val resultCode = if (finalizationState == ProvisioningCoordinator.FinalizationState.SUCCESS) {
                    RESULT_OK
                } else {
                    RESULT_CANCELED
                }
                setResult(resultCode)
                finish()
            }
            else -> {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                finish()
            }
        }
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        primaryButton = Button(this)
        refreshButton = Button(this).apply {
            text = getString(R.string.refresh)
        }
        usageAccessButton = Button(this).apply {
            text = "Open Usage Access"
        }
        accessibilityButton = Button(this).apply {
            text = "Open Accessibility"
        }
        content = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                primaryButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                refreshButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = spacing }
            )
            addView(
                usageAccessButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = spacing }
            )
            addView(
                accessibilityButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = spacing }
            )
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
        root.addView(actions)
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
