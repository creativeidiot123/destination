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
import com.ankit.destination.R
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PackageResolver
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmergencyAppsActivity : AppCompatActivity() {
    private lateinit var policyEngine: PolicyEngine
    private lateinit var resolver: PackageResolver
    private lateinit var adminGate: AdminGate
    private lateinit var optionsContainer: LinearLayout
    private lateinit var previewText: TextView
    private lateinit var customPackageInput: EditText
    private lateinit var doStatusText: TextView

    private val selection = linkedMapOf<String, Boolean>()
    private val optionViews = linkedMapOf<String, CheckBox>()
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyEngine = PolicyEngine(this)
        resolver = PackageResolver(this)
        adminGate = AdminGate(this)
        setContentView(buildContentView())
        seedInitialOptions()
        renderOptions()
        renderPreview()
    }

    override fun onResume() {
        super.onResume()
        renderDoStatus()
        renderPreview()
    }

    private fun seedInitialOptions() {
        val saved = policyEngine.getEmergencyApps().toMutableSet()
        val known = linkedSetOf(
            "com.whatsapp",
            "com.ubercab",
            "com.google.android.apps.maps",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.google.android.apps.messaging"
        )
        known += saved
        known.forEach { pkg ->
            selection[pkg] = saved.contains(pkg)
        }
    }

    private fun renderDoStatus() {
        doStatusText.text = if (policyEngine.isDeviceOwner()) {
            "Device Owner active"
        } else {
            "Device Owner missing (you can edit list, but enforcement stays blocked)"
        }
    }

    private fun renderOptions() {
        optionsContainer.removeAllViews()
        optionViews.clear()
        selection.toSortedMap().forEach { (pkg, checked) ->
            val installed = resolver.isPackageInstalled(pkg)
            val label = resolver.packageLabelOrPackage(pkg)
            val box = CheckBox(this).apply {
                text = "$label\n$pkg (${if (installed) "installed" else "missing"})"
                isChecked = checked
                setOnCheckedChangeListener { _, isNowChecked ->
                    selection[pkg] = isNowChecked
                    renderPreview()
                }
            }
            optionViews[pkg] = box
            optionsContainer.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun renderPreview() {
        previewJob?.cancel()
        val selected = selectedPackages()
        previewJob = lifecycleScope.launch {
            val alwaysAllowed = withContext(Dispatchers.IO) { policyEngine.getAlwaysAllowedApps() }
            val preview = withContext(Dispatchers.Default) {
                val resolved = resolver.resolveAllowlist(
                    userChosenEmergencyApps = selected,
                    alwaysAllowedApps = alwaysAllowed
                )
                buildString {
                    appendLine("Selected emergency packages (${selected.size}):")
                    selected.sorted().forEach { pkg ->
                        appendLine(" - $pkg (${if (resolver.isPackageInstalled(pkg)) "installed" else "missing"})")
                    }
                    appendLine()
                    appendLine("Always-allowed packages (${alwaysAllowed.size}):")
                    alwaysAllowed.sorted().forEach { pkg ->
                        appendLine(" - $pkg (${if (resolver.isPackageInstalled(pkg)) "installed" else "missing"})")
                    }
                    appendLine()
                    appendLine("Resolved allowlist (${resolved.packages.size}):")
                    resolved.packages.sorted().forEach { pkg ->
                        val reason = resolved.reasons[pkg] ?: "unknown"
                        appendLine(" - $pkg => $reason")
                    }
                }
            }
            previewText.text = preview
        }
    }

    private fun selectedPackages(): Set<String> {
        return selection.filterValues { it }.keys.toSet()
    }

    private fun addCustomPackage() {
        val pkg = customPackageInput.text.toString().trim()
        if (pkg.isBlank()) {
            Toast.makeText(this, "Enter a package name", Toast.LENGTH_SHORT).show()
            return
        }
        adminGate.runWithCapability(AdminCapability.ADD_ALLOWLIST_APP) {
            if (!selection.containsKey(pkg)) {
                selection[pkg] = true
                renderOptions()
            } else {
                selection[pkg] = true
                optionViews[pkg]?.isChecked = true
            }
            customPackageInput.setText("")
            renderPreview()
        }
    }

    private fun saveSelection() {
        val selected = selectedPackages()
        val previous = policyEngine.getEmergencyApps()
        val capability = if ((previous - selected).isNotEmpty()) {
            AdminCapability.REMOVE_ALLOWLIST_APP
        } else {
            AdminCapability.ADD_ALLOWLIST_APP
        }
        adminGate.runWithCapability(capability) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    policyEngine.setEmergencyApps(selected)
                }
                val result = if (policyEngine.getDesiredMode() == ModeState.NUCLEAR && policyEngine.isDeviceOwner()) {
                    withContext(Dispatchers.Default) {
                        policyEngine.reapplyDesiredMode(reason = "emergency_apps_changed")
                    }
                } else {
                    null
                }
                val msg = result?.message ?: "Emergency apps saved"
                Toast.makeText(this@EmergencyAppsActivity, msg, Toast.LENGTH_SHORT).show()
                renderPreview()
            }
        }
    }

    private fun buildContentView(): View {
        val padding = dp(16)
        val spacing = dp(8)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        doStatusText = TextView(this)
        renderDoStatus()

        customPackageInput = EditText(this).apply {
            hint = getString(R.string.custom_package_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val addCustomButton = Button(this).apply {
            text = getString(R.string.add_custom_package)
            setOnClickListener { addCustomPackage() }
        }

        optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val previewButton = Button(this).apply {
            text = getString(R.string.preview_resolved_allowlist)
            setOnClickListener { renderPreview() }
        }

        val saveButton = Button(this).apply {
            text = getString(R.string.save_emergency_apps)
            setOnClickListener { saveSelection() }
        }

        previewText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        container.addView(doStatusText)
        container.addView(customPackageInput, lp(top = spacing))
        container.addView(addCustomButton, lp(top = spacing))
        container.addView(optionsContainer, lp(top = dp(12)))
        container.addView(previewButton, lp(top = dp(12)))
        container.addView(saveButton, lp(top = spacing))
        container.addView(previewText, lp(top = dp(12)))

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

    override fun onDestroy() {
        previewJob?.cancel()
        previewJob = null
        super.onDestroy()
    }
}



