package com.ankit.destination.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.R
import com.ankit.destination.policy.FocusConfig
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import com.ankit.destination.vpn.FocusVpnService
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var policyEngine: PolicyEngine
    private lateinit var adminGate: AdminGate

    private lateinit var doStatusText: TextView
    private lateinit var modeStatusText: TextView
    private lateinit var provisioningHelpText: TextView
    private lateinit var nuclearSwitch: SwitchMaterial
    private lateinit var applyNowButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var schedulesButton: Button
    private lateinit var budgetsButton: Button
    private lateinit var vpnDnsButton: Button
    private lateinit var adminControlsButton: Button
    private lateinit var enrollmentGuideButton: Button
    private lateinit var manageEmergencyAppsButton: Button
    private lateinit var startVpnButton: Button
    private lateinit var stopVpnButton: Button
    private lateinit var recoveryExitButton: Button

    private var ignoreSwitchCallback = false
    private var resumeEnforceJob: Job? = null
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (FocusVpnService.isPrepared(this)) {
            FocusVpnService.start(this)
            Toast.makeText(this, "VPN stub started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyEngine = PolicyEngine(this)
        adminGate = AdminGate(this)
        setContentView(buildContentView())
        wireListeners()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        resumeEnforceJob?.cancel()
        resumeEnforceJob = lifecycleScope.launch {
            val includeBudgets = policyEngine.shouldRunBudgetEvaluation()
            ScheduleEnforcer(this@MainActivity).enforceNowAsync(
                trigger = "MainActivityResume",
                hostActivity = this@MainActivity,
                includeBudgets = includeBudgets
            )
            if (policyEngine.isDeviceOwner() && policyEngine.getDesiredMode() == ModeState.NUCLEAR) {
                val verify = policyEngine.verifyDesiredMode(this@MainActivity)
                if (!verify.passed) {
                    policyEngine.reapplyDesiredMode(this@MainActivity, reason = "watchdog_on_resume")
                }
            }
            refreshUi()
        }
    }

    override fun onPause() {
        super.onPause()
        resumeEnforceJob?.cancel()
        resumeEnforceJob = null
    }

    private fun wireListeners() {
        nuclearSwitch.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitchCallback) return@setOnCheckedChangeListener
            if (policyEngine.isScheduleLockActive() && !checked) {
                Toast.makeText(this, "This lock is scheduled and cannot be canceled.", Toast.LENGTH_SHORT).show()
                refreshUi()
                return@setOnCheckedChangeListener
            }

            adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                val result = if (checked) {
                    policyEngine.setMode(ModeState.NUCLEAR, hostActivity = this, reason = "ui_toggle")
                } else {
                    policyEngine.setMode(ModeState.NORMAL, hostActivity = this, reason = "ui_toggle")
                }
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            refreshUi()
        }

        applyNowButton.setOnClickListener {
            adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                val result = policyEngine.reapplyDesiredMode(this, reason = "ui_reapply")
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

        diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        schedulesButton.setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }

        budgetsButton.setOnClickListener {
            startActivity(Intent(this, BudgetsActivity::class.java))
        }

        vpnDnsButton.setOnClickListener {
            startActivity(Intent(this, VpnDnsSettingsActivity::class.java))
        }

        adminControlsButton.setOnClickListener {
            startActivity(Intent(this, AdminControlsActivity::class.java))
        }

        enrollmentGuideButton.setOnClickListener {
            startActivity(Intent(this, EnrollmentGuideActivity::class.java))
        }

        manageEmergencyAppsButton.setOnClickListener {
            startActivity(Intent(this, EmergencyAppsActivity::class.java))
        }

        startVpnButton.setOnClickListener {
            adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnPrepareLauncher.launch(prepareIntent)
                } else {
                    FocusVpnService.start(this)
                    Toast.makeText(this, "VPN stub started", Toast.LENGTH_SHORT).show()
                }
                refreshUi()
            }
        }

        stopVpnButton.setOnClickListener {
            adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                FocusVpnService.stop(this)
                Toast.makeText(this, "VPN stub stopped", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

        recoveryExitButton.setOnClickListener {
            showDebugRecoveryDialog()
        }
    }

    private fun showDebugRecoveryDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.recovery_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_pin_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (input.text.toString().trim() == FocusConfig.debugRecoveryPin) {
                    val result = policyEngine.setMode(
                        ModeState.NORMAL,
                        hostActivity = this,
                        reason = "debug_recovery_pin"
                    )
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    refreshUi()
                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun refreshUi() {
        val isDo = policyEngine.isDeviceOwner()
        val snapshot = policyEngine.diagnosticsSnapshot()
        val scheduleUntil = snapshot.scheduleNextTransitionAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "unknown"

        doStatusText.text = if (isDo) {
            getString(R.string.do_ready)
        } else {
            getString(R.string.not_do_blocked)
        }

        val lastApplied = if (snapshot.lastAppliedAtMs > 0L) {
            DateFormat.getDateTimeInstance().format(Date(snapshot.lastAppliedAtMs))
        } else {
            "never"
        }
        modeStatusText.text = buildString {
            append("Desired mode: ${snapshot.desiredMode}\n")
            append("Last verification: ${snapshot.lastVerificationPassed}\n")
            append("Last applied: $lastApplied\n")
            append("Allowlist size: ${snapshot.lockTaskPackages.size}\n")
            append("Suspended count: ${snapshot.lastSuspendedPackages.size}\n")
            append("VPN active: ${snapshot.vpnActive}\n")
            append("VPN required for Nuclear: ${snapshot.vpnRequiredForNuclear}\n")
            append("Schedule computed: ${snapshot.scheduleLockComputed}\n")
            append("Schedule enforced: ${snapshot.scheduleLockActive}\n")
            append("Schedule strict computed: ${snapshot.scheduleStrictComputed}\n")
            append("Schedule strict enforced: ${snapshot.scheduleStrictActive}\n")
            append("Schedule blocked groups: ${snapshot.scheduleBlockedGroups.size}\n")
            append("Budget blocked: ${snapshot.budgetBlockedPackages.size}\n")
            append("Budget blocked groups: ${snapshot.budgetBlockedGroupIds.size}\n")
            append("Touch Grass break active: ${snapshot.touchGrassBreakActive}\n")
            if (snapshot.scheduleLockActive) {
                append("Locked by schedule until $scheduleUntil\n")
            } else if (snapshot.scheduleLockComputed) {
                append("Schedule wants lock, but enforcement is not active.\n")
            }
            if (snapshot.touchGrassBreakActive) {
                val until = snapshot.touchGrassBreakUntilMs?.let {
                    DateFormat.getDateTimeInstance().format(Date(it))
                } ?: "unknown"
                append("Touch Grass break until $until\n")
            }
            if (!snapshot.lastError.isNullOrBlank()) {
                append("Last error: ${snapshot.lastError}")
            }
            if (!snapshot.vpnLastError.isNullOrBlank()) {
                append("\nVPN error: ${snapshot.vpnLastError}")
            }
        }

        provisioningHelpText.text = if (isDo) {
            "Provisioned as Device Owner."
        } else {
            "Provisioning required.\n\n" +
                "Dev test command (fresh/factory-reset device):\n" +
                "adb shell dpm set-device-owner com.ankit.destination/.admin.FocusDeviceAdminReceiver\n" +
                "Verify:\n" +
                "adb shell dpm get-device-owner"
        }

        ignoreSwitchCallback = true
        nuclearSwitch.isChecked = isDo && snapshot.desiredMode == ModeState.NUCLEAR
        ignoreSwitchCallback = false

        nuclearSwitch.isEnabled = isDo && !snapshot.scheduleLockActive && !snapshot.touchGrassBreakActive
        applyNowButton.isEnabled = isDo
        diagnosticsButton.isEnabled = true
        schedulesButton.isEnabled = true
        budgetsButton.isEnabled = true
        vpnDnsButton.isEnabled = true
        adminControlsButton.isEnabled = true
        enrollmentGuideButton.isEnabled = true
        manageEmergencyAppsButton.isEnabled = true
        startVpnButton.isEnabled = true
        stopVpnButton.isEnabled = FocusVpnService.isRunning(this)
        recoveryExitButton.visibility = if (isDebugBuild()) View.VISIBLE else View.GONE
        recoveryExitButton.isEnabled = isDo
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        doStatusText = TextView(this).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        modeStatusText = TextView(this)
        provisioningHelpText = TextView(this).apply {
            setTextIsSelectable(true)
        }
        nuclearSwitch = SwitchMaterial(this).apply {
            text = getString(R.string.nuclear_mode_title)
        }
        applyNowButton = Button(this).apply {
            text = getString(R.string.apply_now)
        }
        diagnosticsButton = Button(this).apply {
            text = getString(R.string.open_diagnostics)
        }
        schedulesButton = Button(this).apply {
            text = getString(R.string.manage_schedules)
        }
        budgetsButton = Button(this).apply {
            text = "Manage Budgets"
        }
        vpnDnsButton = Button(this).apply {
            text = "VPN + DNS Settings"
        }
        adminControlsButton = Button(this).apply {
            text = "Admin Controls"
        }
        enrollmentGuideButton = Button(this).apply {
            text = getString(R.string.provisioning_guide)
        }
        manageEmergencyAppsButton = Button(this).apply {
            text = getString(R.string.manage_emergency_apps)
        }
        startVpnButton = Button(this).apply {
            text = getString(R.string.start_vpn_stub)
        }
        stopVpnButton = Button(this).apply {
            text = getString(R.string.stop_vpn_stub)
        }
        recoveryExitButton = Button(this).apply {
            text = getString(R.string.debug_recovery_exit)
        }

        container.addView(doStatusText)
        container.addView(modeStatusText, lp(top = spacing))
        container.addView(provisioningHelpText, lp(top = dp(12)))
        container.addView(nuclearSwitch, lp(top = dp(20)))
        container.addView(applyNowButton, lp(top = dp(12)))
        container.addView(startVpnButton, lp(top = spacing))
        container.addView(stopVpnButton, lp(top = spacing))
        container.addView(schedulesButton, lp(top = spacing))
        container.addView(budgetsButton, lp(top = spacing))
        container.addView(vpnDnsButton, lp(top = spacing))
        container.addView(adminControlsButton, lp(top = spacing))
        container.addView(manageEmergencyAppsButton, lp(top = spacing))
        container.addView(enrollmentGuideButton, lp(top = spacing))
        container.addView(diagnosticsButton, lp(top = spacing))
        container.addView(recoveryExitButton, lp(top = spacing))

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

