package com.ankit.destination.ui

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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.UninstallProtectedApp
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminControlsActivity : AppCompatActivity() {
    private lateinit var policyEngine: PolicyEngine
    private lateinit var db: FocusDatabase
    private lateinit var adminGate: AdminGate

    private lateinit var statusText: TextView
    private lateinit var lockTimeCheck: CheckBox
    private lateinit var lockVpnDnsCheck: CheckBox
    private lateinit var lockDevCheck: CheckBox
    private lateinit var lockUserCheck: CheckBox
    private lateinit var lockWorkProfileCheck: CheckBox
    private lateinit var lockCloningCheck: CheckBox
    private lateinit var dangerUnenrollCheck: CheckBox

    private lateinit var alwaysAllowedInput: EditText
    private lateinit var alwaysAllowedContainer: LinearLayout
    private lateinit var alwaysBlockedInput: EditText
    private lateinit var alwaysBlockedContainer: LinearLayout
    private lateinit var uninstallProtectedInput: EditText
    private lateinit var uninstallProtectedContainer: LinearLayout
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyEngine = PolicyEngine(this)
        db = FocusDatabase.get(this)
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
            val controls = withContext(Dispatchers.IO) {
                db.budgetDao().getGlobalControls() ?: GlobalControls()
            }
            val alwaysAllowed = withContext(Dispatchers.IO) { db.budgetDao().getAlwaysAllowedPackages() }.toSet()
            val alwaysBlocked = withContext(Dispatchers.IO) { db.budgetDao().getAlwaysBlockedPackages() }.toSet()
            val uninstallProtected = withContext(Dispatchers.IO) {
                db.budgetDao().getUninstallProtectedPackages()
            }.toMutableSet().apply {
                add(packageName)
            }.toSet()

            statusText.text = buildString {
                append("Device Owner: ${policyEngine.isDeviceOwner()}\n")
                append("Always allowed: ${alwaysAllowed.size}\n")
                append("Always blocked: ${alwaysBlocked.size}\n")
                append("Uninstall protected: ${uninstallProtected.size}")
            }

            lockTimeCheck.isChecked = controls.lockTime
            lockVpnDnsCheck.isChecked = controls.lockVpnDns
            lockDevCheck.isChecked = controls.lockDevOptions
            lockUserCheck.isChecked = controls.lockUserCreation
            lockWorkProfileCheck.isChecked = controls.lockWorkProfile
            lockCloningCheck.isChecked = controls.lockCloningBestEffort
            dangerUnenrollCheck.isChecked = controls.dangerUnenrollEnabled

            renderList(
                container = alwaysAllowedContainer,
                values = alwaysAllowed,
                removeCapability = AdminCapability.REMOVE_ALLOWLIST_APP
            ) { pkg ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.budgetDao().deleteAlwaysAllowed(pkg) }
                    applyNow("AlwaysAllowedRemoved")
                }
            }
            renderList(
                container = alwaysBlockedContainer,
                values = alwaysBlocked,
                removeCapability = AdminCapability.EDIT_INDIVIDUAL_APP_SETTINGS
            ) { pkg ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.budgetDao().deleteAlwaysBlocked(pkg) }
                    applyNow("AlwaysBlockedRemoved")
                }
            }
            renderList(
                container = uninstallProtectedContainer,
                values = uninstallProtected,
                removeCapability = AdminCapability.EDIT_UNINSTALL_PROTECTED_LIST
            ) { pkg ->
                if (pkg == packageName) {
                    Toast.makeText(
                        this@AdminControlsActivity,
                        "Controller app is always uninstall-protected.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@renderList
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.budgetDao().deleteUninstallProtected(pkg) }
                    applyNow("UninstallProtectedRemoved")
                }
            }
        }
    }

    private fun saveControls() {
        adminGate.runWithCapability(AdminCapability.EDIT_DEVICE_TOGGLES) {
            lifecycleScope.launch {
                val controls = GlobalControls(
                    id = 1,
                    lockTime = lockTimeCheck.isChecked,
                    lockVpnDns = lockVpnDnsCheck.isChecked,
                    lockDevOptions = lockDevCheck.isChecked,
                    lockUserCreation = lockUserCheck.isChecked,
                    lockWorkProfile = lockWorkProfileCheck.isChecked,
                    lockCloningBestEffort = lockCloningCheck.isChecked,
                    dangerUnenrollEnabled = dangerUnenrollCheck.isChecked
                )
                withContext(Dispatchers.IO) {
                    db.budgetDao().upsertGlobalControls(controls)
                }
                applyNow("GlobalControlsSaved")
            }
        }
    }

    private fun addAlwaysAllowed() {
        val pkg = alwaysAllowedInput.text.toString().trim()
        if (pkg.isBlank()) return
        adminGate.runWithCapability(AdminCapability.ADD_ALLOWLIST_APP) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.budgetDao().addAlwaysAllowedExclusive(pkg) }
                alwaysAllowedInput.setText("")
                applyNow("AlwaysAllowedAdded")
            }
        }
    }

    private fun addAlwaysBlocked() {
        val pkg = alwaysBlockedInput.text.toString().trim()
        if (pkg.isBlank()) return
        adminGate.runWithCapability(AdminCapability.ADD_BLOCKLIST_APP) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.budgetDao().addAlwaysBlockedExclusive(pkg) }
                alwaysBlockedInput.setText("")
                applyNow("AlwaysBlockedAdded")
            }
        }
    }

    private fun addUninstallProtected() {
        val pkg = uninstallProtectedInput.text.toString().trim()
        if (pkg.isBlank()) return
        adminGate.runWithCapability(AdminCapability.EDIT_UNINSTALL_PROTECTED_LIST) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.budgetDao().upsertUninstallProtected(UninstallProtectedApp(pkg))
                }
                uninstallProtectedInput.setText("")
                applyNow("UninstallProtectedAdded")
            }
        }
    }

    private suspend fun applyNow(trigger: String) {
        ScheduleEnforcer(this).enforceNowAsync(trigger = trigger, hostActivity = this, includeBudgets = false)
        loadAndRender()
    }

    private fun renderList(
        container: LinearLayout,
        values: Set<String>,
        removeCapability: AdminCapability,
        onRemove: (String) -> Unit
    ) {
        container.removeAllViews()
        if (values.isEmpty()) {
            container.addView(TextView(this).apply { text = "None" })
            return
        }
        values.sorted().forEach { pkg ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply { text = pkg }
            val remove = Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    adminGate.runWithCapability(removeCapability) {
                        onRemove(pkg)
                    }
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove)
            container.addView(row)
        }
    }

    private fun buildContentView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        statusText = TextView(this)
        lockTimeCheck = CheckBox(this).apply { text = "Lock date/time changes" }
        lockVpnDnsCheck = CheckBox(this).apply { text = "Enforce always-on VPN + lockdown" }
        lockDevCheck = CheckBox(this).apply { text = "Lock developer/debugging options" }
        lockUserCheck = CheckBox(this).apply { text = "Lock add-user/guest" }
        lockWorkProfileCheck = CheckBox(this).apply { text = "Lock add work profile" }
        lockCloningCheck = CheckBox(this).apply { text = "Best-effort lock clone/private profiles" }
        dangerUnenrollCheck = CheckBox(this).apply { text = "Enable danger unenroll UI" }
        val saveControls = Button(this).apply {
            text = "Save Global Controls"
            setOnClickListener { saveControls() }
        }
        val lockSession = Button(this).apply {
            text = "Lock Admin Session Now"
            setOnClickListener {
                adminGate.lockNow()
                Toast.makeText(this@AdminControlsActivity, "Admin session locked", Toast.LENGTH_SHORT).show()
            }
        }

        alwaysAllowedInput = EditText(this).apply {
            hint = "Always allowed package"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val addAlwaysAllowed = Button(this).apply {
            text = "Add Always Allowed"
            setOnClickListener { addAlwaysAllowed() }
        }
        alwaysAllowedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        alwaysBlockedInput = EditText(this).apply {
            hint = "Always blocked package"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val addAlwaysBlocked = Button(this).apply {
            text = "Add Always Blocked"
            setOnClickListener { addAlwaysBlocked() }
        }
        alwaysBlockedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        uninstallProtectedInput = EditText(this).apply {
            hint = "Uninstall protected package"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val addUninstallProtected = Button(this).apply {
            text = "Add Uninstall Protected"
            setOnClickListener { addUninstallProtected() }
        }
        uninstallProtectedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        container.addView(statusText)
        container.addView(lockTimeCheck, lp(top = dp(8)))
        container.addView(lockVpnDnsCheck, lp(top = dp(4)))
        container.addView(lockDevCheck, lp(top = dp(4)))
        container.addView(lockUserCheck, lp(top = dp(4)))
        container.addView(lockWorkProfileCheck, lp(top = dp(4)))
        container.addView(lockCloningCheck, lp(top = dp(4)))
        container.addView(dangerUnenrollCheck, lp(top = dp(4)))
        container.addView(saveControls, lp(top = dp(8)))
        container.addView(lockSession, lp(top = dp(8)))

        container.addView(section("Always Allowed"), lp(top = dp(16)))
        container.addView(alwaysAllowedInput, lp(top = dp(8)))
        container.addView(addAlwaysAllowed, lp(top = dp(8)))
        container.addView(alwaysAllowedContainer, lp(top = dp(8)))

        container.addView(section("Always Blocked"), lp(top = dp(16)))
        container.addView(alwaysBlockedInput, lp(top = dp(8)))
        container.addView(addAlwaysBlocked, lp(top = dp(8)))
        container.addView(alwaysBlockedContainer, lp(top = dp(8)))

        container.addView(section("Uninstall Protected"), lp(top = dp(16)))
        container.addView(uninstallProtectedInput, lp(top = dp(8)))
        container.addView(addUninstallProtected, lp(top = dp(8)))
        container.addView(uninstallProtectedContainer, lp(top = dp(8)))

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
