package com.ankit.destination.ui

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
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import java.text.DateFormat
import java.util.Date

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var policyEngine: PolicyEngine
    private lateinit var adminGate: AdminGate
    private lateinit var diagnosticsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyEngine = PolicyEngine(this)
        adminGate = AdminGate(this)
        setContentView(buildContentView())
        render()
    }

    private fun render() {
        val snapshot = policyEngine.diagnosticsSnapshot()
        val lastApplied = if (snapshot.lastAppliedAtMs > 0L) {
            DateFormat.getDateTimeInstance().format(Date(snapshot.lastAppliedAtMs))
        } else {
            "never"
        }
        val nextTransition = snapshot.scheduleNextTransitionAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "none"
        val budgetNextCheck = snapshot.budgetNextCheckAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "none"
        val touchGrassUntil = snapshot.touchGrassBreakUntilMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "none"

        diagnosticsText.text = buildString {
            appendLine("Device Owner: ${snapshot.deviceOwner}")
            appendLine("Desired mode: ${snapshot.desiredMode}")
            appendLine("Manual mode: ${snapshot.manualMode}")
            appendLine("Schedule computed active: ${snapshot.scheduleLockComputed}")
            appendLine("Schedule lock active: ${snapshot.scheduleLockActive}")
            appendLine("Schedule strict computed: ${snapshot.scheduleStrictComputed}")
            appendLine("Schedule strict active: ${snapshot.scheduleStrictActive}")
            appendLine("Schedule blocked groups: ${snapshot.scheduleBlockedGroups.joinToString().ifBlank { "none" }}")
            appendLine("Schedule lock reason: ${snapshot.scheduleLockReason ?: "none"}")
            appendLine("Schedule next transition: $nextTransition")
            appendLine("Budget blocked packages: ${snapshot.budgetBlockedPackages.size}")
            appendLine("Budget blocked groups: ${snapshot.budgetBlockedGroupIds.joinToString().ifBlank { "none" }}")
            appendLine("Budget reason: ${snapshot.budgetReason ?: "none"}")
            appendLine("Budget usage access granted: ${snapshot.budgetUsageAccessGranted}")
            appendLine("Budget next check: $budgetNextCheck")
            appendLine("Touch Grass break active: ${snapshot.touchGrassBreakActive}")
            appendLine("Touch Grass break until: $touchGrassUntil")
            appendLine("Touch Grass threshold: ${snapshot.touchGrassThreshold}")
            appendLine("Touch Grass break minutes: ${snapshot.touchGrassBreakMinutes}")
            appendLine("Unlock day: ${snapshot.unlockCountDay ?: "none"}")
            appendLine("Unlock count today: ${snapshot.unlockCountToday}")
            appendLine("Current lock reason: ${snapshot.currentLockReason ?: "none"}")
            appendLine("Last verify passed: ${snapshot.lastVerificationPassed}")
            appendLine("Last applied: $lastApplied")
            appendLine("Last error: ${snapshot.lastError ?: "none"}")
            appendLine("Lock task features: ${snapshot.lockTaskFeatures ?: "n/a"}")
            appendLine("Status bar disabled: ${snapshot.statusBarDisabled ?: "n/a"}")
            appendLine("Lock task packages (${snapshot.lockTaskPackages.size}):")
            snapshot.lockTaskPackages.sorted().forEach { appendLine(" - $it") }
            appendLine("Suspended packages count: ${snapshot.lastSuspendedPackages.size}")
            val sample = snapshot.lastSuspendedPackages.take(25)
            if (sample.isNotEmpty()) {
                appendLine("Suspended sample:")
                sample.forEach { appendLine(" - $it") }
            }
            appendLine("Restrictions:")
            snapshot.restrictions.toSortedMap().forEach { (restriction, enabled) ->
                appendLine(" - $restriction = $enabled")
            }
            appendLine("Emergency apps:")
            snapshot.emergencyApps.sorted().forEach { appendLine(" - $it") }
            appendLine("Always allowed apps:")
            snapshot.alwaysAllowedApps.sorted().forEach { appendLine(" - $it") }
            appendLine("Always blocked apps:")
            snapshot.alwaysBlockedApps.sorted().forEach { appendLine(" - $it") }
            appendLine("Uninstall protected apps:")
            snapshot.uninstallProtectedApps.sorted().forEach { appendLine(" - $it") }
            appendLine("Global controls:")
            appendLine(" - lockTime=${snapshot.globalControls.lockTime}")
            appendLine(" - lockVpnDns=${snapshot.globalControls.lockVpnDns}")
            appendLine(" - lockDevOptions=${snapshot.globalControls.lockDevOptions}")
            appendLine(" - lockUserCreation=${snapshot.globalControls.lockUserCreation}")
            appendLine(" - lockWorkProfile=${snapshot.globalControls.lockWorkProfile}")
            appendLine(" - lockCloningBestEffort=${snapshot.globalControls.lockCloningBestEffort}")
            appendLine(" - dangerUnenrollEnabled=${snapshot.globalControls.dangerUnenrollEnabled}")
            appendLine("Allowlist reasons:")
            snapshot.allowlistReasons.toSortedMap().forEach { (pkg, reason) ->
                appendLine(" - $pkg => $reason")
            }
            appendLine("Primary reason by package:")
            snapshot.primaryReasonByPackage.toSortedMap().forEach { (pkg, reason) ->
                appendLine(" - $pkg => $reason")
            }
            appendLine("VPN active: ${snapshot.vpnActive}")
            appendLine("VPN required for Nuclear: ${snapshot.vpnRequiredForNuclear}")
            appendLine("VPN lockdown required: ${snapshot.vpnLockdownRequired}")
            appendLine("VPN last error: ${snapshot.vpnLastError ?: "none"}")
            appendLine("Always-on VPN package: ${snapshot.alwaysOnVpnPackage ?: "none"}")
            appendLine("Always-on VPN lockdown: ${snapshot.alwaysOnVpnLockdown ?: "n/a"}")
            appendLine("Domain rules loaded: ${snapshot.domainRuleCount}")
        }
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val refreshButton = Button(this).apply {
            text = getString(R.string.refresh)
            setOnClickListener { render() }
        }

        val reapplyButton = Button(this).apply {
            text = getString(R.string.reapply)
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                    val result = policyEngine.reapplyDesiredMode(
                        this@DiagnosticsActivity,
                        reason = "diagnostics_reapply"
                    )
                    Toast.makeText(this@DiagnosticsActivity, result.message, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }

        diagnosticsText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(
                diagnosticsText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(refreshButton)
        root.addView(
            reapplyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = spacing }
        )
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
