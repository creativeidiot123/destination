package com.ankit.destination.ui

import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.data.DomainRule
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import com.ankit.destination.vpn.FocusVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnDnsSettingsActivity : AppCompatActivity() {
    private lateinit var db: FocusDatabase
    private lateinit var policyEngine: PolicyEngine
    private lateinit var adminGate: AdminGate

    private lateinit var statusText: TextView
    private lateinit var rulesContainer: LinearLayout
    private lateinit var domainInput: EditText
    private lateinit var scopeTypeInput: EditText
    private lateinit var scopeIdInput: EditText
    private lateinit var blockedCheck: CheckBox
    private var loadJob: Job? = null

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (FocusVpnService.isPrepared(this)) {
            FocusVpnService.start(this)
            Toast.makeText(this, "VPN started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        loadAndRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FocusDatabase.get(this)
        policyEngine = PolicyEngine(this)
        adminGate = AdminGate(this)
        setContentView(buildContentView())
        loadAndRender()
    }

    override fun onResume() {
        super.onResume()
        loadAndRender()
    }

    private fun loadAndRender() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { db.budgetDao().getAllDomainRules() }
            renderStatus()
            renderRules(rules)
        }
    }

    private fun renderStatus() {
        val snapshot = policyEngine.diagnosticsSnapshot()
        statusText.text = buildString {
            append("VPN active: ${snapshot.vpnActive}\n")
            append("Always-on package: ${snapshot.alwaysOnVpnPackage ?: "none"}\n")
            append("Lockdown enabled: ${snapshot.alwaysOnVpnLockdown ?: "n/a"}\n")
            append("Domain rules loaded: ${snapshot.domainRuleCount}\n")
            append("VPN error: ${snapshot.vpnLastError ?: "none"}")
        }
    }

    private fun renderRules(rules: List<DomainRule>) {
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply { text = "No domain rules configured." })
            return
        }
        rules.forEach { rule ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${rule.domain} | ${rule.scopeType}:${rule.scopeId.ifBlank { "-" }} | ${if (rule.blocked) "BLOCK" else "ALLOW"}"
            }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                        deleteRule(rule)
                    }
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(delete)
            rulesContainer.addView(row)
        }
    }

    private fun saveRule() {
        val domain = domainInput.text.toString().trim().lowercase().trim('.')
        val scopeType = scopeTypeInput.text.toString().trim().uppercase().ifEmpty { "GLOBAL" }
        val scopeId = scopeIdInput.text.toString().trim()
        if (domain.isBlank()) {
            Toast.makeText(this, "Enter a domain", Toast.LENGTH_SHORT).show()
            return
        }
        if (scopeType !in setOf("GLOBAL", "GROUP")) {
            Toast.makeText(this, "scopeType must be GLOBAL or GROUP", Toast.LENGTH_SHORT).show()
            return
        }
        if (scopeType == "GROUP" && scopeId.isBlank()) {
            Toast.makeText(this, "Group scope needs groupId", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedScopeId = if (scopeType == "GLOBAL") "" else scopeId
        val rule = DomainRule(
            domain = domain,
            scopeType = scopeType,
            scopeId = normalizedScopeId,
            blocked = blockedCheck.isChecked
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.budgetDao().upsertDomainRule(rule) }
            domainInput.setText("")
            if (scopeType == "GLOBAL") scopeIdInput.setText("")
            loadAndRender()
        }
    }

    private fun deleteRule(rule: DomainRule) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.budgetDao().deleteDomainRule(
                    domain = rule.domain,
                    scopeType = rule.scopeType,
                    scopeId = rule.scopeId
                )
            }
            loadAndRender()
        }
    }

    private fun allowCaptivePortalDefaults() {
        val defaults = listOf(
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "captive.apple.com",
            "msftconnecttest.com",
            "detectportal.firefox.com"
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                defaults.forEach { domain ->
                    db.budgetDao().upsertDomainRule(
                        DomainRule(
                            domain = domain,
                            scopeType = "GLOBAL",
                            scopeId = "",
                            blocked = false
                        )
                    )
                }
            }
            Toast.makeText(
                this@VpnDnsSettingsActivity,
                "Captive portal allowlist added",
                Toast.LENGTH_SHORT
            ).show()
            loadAndRender()
        }
    }

    private fun startVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            FocusVpnService.start(this)
            loadAndRender()
        }
    }

    private fun buildContentView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusText = TextView(this)
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadAndRender() }
        }
        val startVpnButton = Button(this).apply {
            text = "Start VPN"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                    startVpn()
                }
            }
        }
        val stopVpnButton = Button(this).apply {
            text = "Stop VPN"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                    FocusVpnService.stop(this@VpnDnsSettingsActivity)
                    loadAndRender()
                }
            }
        }
        val captivePortalButton = Button(this).apply {
            text = "Allow Captive Portal Domains"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                    allowCaptivePortalDefaults()
                }
            }
        }

        domainInput = EditText(this).apply { hint = "Domain (example.com)" }
        scopeTypeInput = EditText(this).apply { hint = "Scope type: GLOBAL or GROUP"; setText("GLOBAL") }
        scopeIdInput = EditText(this).apply { hint = "Scope id (group id for GROUP scope)" }
        blockedCheck = CheckBox(this).apply { text = "Blocked rule (off = allow rule)"; isChecked = true }
        val saveButton = Button(this).apply {
            text = "Save Rule"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
                    saveRule()
                }
            }
        }

        rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        container.addView(statusText)
        container.addView(refreshButton, lp(top = dp(8)))
        container.addView(startVpnButton, lp(top = dp(8)))
        container.addView(stopVpnButton, lp(top = dp(8)))
        container.addView(captivePortalButton, lp(top = dp(8)))
        container.addView(section("Domain Rules"), lp(top = dp(16)))
        container.addView(domainInput, lp(top = dp(8)))
        container.addView(scopeTypeInput, lp(top = dp(8)))
        container.addView(scopeIdInput, lp(top = dp(8)))
        container.addView(blockedCheck, lp(top = dp(8)))
        container.addView(saveButton, lp(top = dp(8)))
        container.addView(rulesContainer, lp(top = dp(8)))

        root.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        super.onDestroy()
    }
}
