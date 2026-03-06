package com.ankit.destination.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.data.AppGroupMap
import com.ankit.destination.data.AppLimit
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GroupEmergencyConfig
import com.ankit.destination.data.GroupEmergencyState
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

class BudgetsActivity : AppCompatActivity() {
    private lateinit var db: FocusDatabase
    private lateinit var policyEngine: PolicyEngine
    private lateinit var adminGate: AdminGate

    private lateinit var statusText: TextView
    private lateinit var appLimitsContainer: LinearLayout
    private lateinit var groupLimitsContainer: LinearLayout
    private lateinit var mappingsContainer: LinearLayout
    private lateinit var touchGrassStatusText: TextView

    private lateinit var appPackageInput: EditText
    private lateinit var appMinutesInput: EditText

    private lateinit var groupIdInput: EditText
    private lateinit var groupNameInput: EditText
    private lateinit var groupDailyMinutesInput: EditText
    private lateinit var groupHourlyMinutesInput: EditText
    private lateinit var groupOpensInput: EditText

    private lateinit var mappingPackageInput: EditText
    private lateinit var mappingGroupInput: EditText

    private lateinit var thresholdInput: EditText
    private lateinit var breakMinutesInput: EditText

    private val budgetOrchestrator by lazy { BudgetOrchestrator(this) }
    private var loadJob: Job? = null
    private var emergencyPuzzleTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FocusDatabase.get(this)
        policyEngine = PolicyEngine(this)
        adminGate = AdminGate(this)
        setContentView(buildContentView())
        loadAndRender(enforce = false)
    }

    override fun onResume() {
        super.onResume()
        loadAndRender(enforce = true)
    }

    private fun loadAndRender(enforce: Boolean) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            if (enforce) {
                ScheduleEnforcer(this@BudgetsActivity).enforceNowAsync(
                    trigger = "BudgetsActivityResume",
                    hostActivity = this@BudgetsActivity
                )
            }
            val dao = db.budgetDao()
            val appLimits = withContext(Dispatchers.IO) { dao.getAllAppLimits() }
            val groupLimits = withContext(Dispatchers.IO) { dao.getAllGroupLimits() }
            val mappings = withContext(Dispatchers.IO) { dao.getAllMappings() }
            val dayKey = UsageWindow.dayKey(java.time.ZonedDateTime.now())
            val emergencyConfigs = withContext(Dispatchers.IO) { dao.getAllGroupEmergencyConfigs() }
            val emergencyStates = withContext(Dispatchers.IO) { dao.getGroupEmergencyStatesForDay(dayKey) }
            render(appLimits, groupLimits, mappings, emergencyConfigs, emergencyStates)
        }
    }

    private fun render(
        appLimits: List<AppLimit>,
        groupLimits: List<GroupLimit>,
        mappings: List<AppGroupMap>,
        emergencyConfigs: List<GroupEmergencyConfig>,
        emergencyStates: List<GroupEmergencyState>
    ) {
        val snapshot = policyEngine.diagnosticsSnapshot()
        val usageGranted = UsageAccess.hasUsageAccess(this)
        val nextCheck = snapshot.budgetNextCheckAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "none"
        statusText.text = buildString {
            append("Usage access: $usageGranted\n")
            append("Budget timezone policy: device local day/hour\n")
            append("Mapping policy: one app belongs to one group\n")
            append("Budget blocked packages: ${snapshot.budgetBlockedPackages.size}\n")
            append("Budget blocked groups: ${snapshot.budgetBlockedGroupIds.size}\n")
            append("Budget reason: ${snapshot.budgetReason ?: "none"}\n")
            append("Next budget check: $nextCheck\n")
            append("Schedule enforced: ${snapshot.scheduleLockActive}\n")
            append("Current lock reason: ${snapshot.currentLockReason ?: "none"}")
        }
        touchGrassStatusText.text = buildString {
            append("Touch Grass threshold: ${snapshot.touchGrassThreshold}\n")
            append("Touch Grass break minutes: ${snapshot.touchGrassBreakMinutes}\n")
            append("Unlock day: ${snapshot.unlockCountDay ?: "none"}\n")
            append("Unlock count today: ${snapshot.unlockCountToday}\n")
            append("Break active: ${snapshot.touchGrassBreakActive}\n")
            val breakUntil = snapshot.touchGrassBreakUntilMs?.let {
                DateFormat.getDateTimeInstance().format(Date(it))
            } ?: "none"
            append("Break until: $breakUntil")
        }
        thresholdInput.setText(snapshot.touchGrassThreshold.toString())
        breakMinutesInput.setText(snapshot.touchGrassBreakMinutes.toString())

        renderAppLimits(appLimits)
        renderGroupLimits(groupLimits, emergencyConfigs, emergencyStates)
        renderMappings(mappings)
    }

    private fun renderAppLimits(appLimits: List<AppLimit>) {
        appLimitsContainer.removeAllViews()
        if (appLimits.isEmpty()) {
            appLimitsContainer.addView(TextView(this).apply { text = "No app limits configured." })
            return
        }
        appLimits.sortedBy { it.packageName }.forEach { limit ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${limit.packageName}: ${limit.dailyLimitMs / 60_000}m/day (${if (limit.enabled) "on" else "off"})"
            }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.EDIT_INDIVIDUAL_APP_SETTINGS) {
                        deleteAppLimit(limit.packageName)
                    }
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(delete)
            appLimitsContainer.addView(row)
        }
    }

    private fun renderGroupLimits(
        groupLimits: List<GroupLimit>,
        emergencyConfigs: List<GroupEmergencyConfig>,
        emergencyStates: List<GroupEmergencyState>
    ) {
        groupLimitsContainer.removeAllViews()
        if (groupLimits.isEmpty()) {
            groupLimitsContainer.addView(TextView(this).apply { text = "No group limits configured." })
            return
        }
        val configByGroup = emergencyConfigs.associateBy { it.groupId }
        val stateByGroup = emergencyStates.associateBy { it.groupId }
        val nowMs = System.currentTimeMillis()
        groupLimits.sortedBy { it.name }.forEach { limit ->
            val emergencyConfig = configByGroup[limit.groupId]
            val emergencyState = stateByGroup[limit.groupId]
            val unlocksPerDay = emergencyConfig?.unlocksPerDay ?: 0
            val unlocksUsed = emergencyState?.unlocksUsedToday ?: 0
            val remainingUnlocks = (unlocksPerDay - unlocksUsed).coerceAtLeast(0)
            val emergencyUntil = emergencyState?.activeUntilEpochMs
            val emergencyActive = emergencyUntil != null && emergencyUntil > nowMs
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = buildString {
                    append("${limit.name} (${limit.groupId}) daily=${limit.dailyLimitMs / 60_000}m hourly=${limit.hourlyLimitMs / 60_000}m opens=${limit.opensPerDay}\n")
                    append(
                        if (emergencyConfig?.enabled == true) {
                            "Emergency: on, unlocks $remainingUnlocks/$unlocksPerDay, ${emergencyConfig.minutesPerUnlock}m per unlock"
                        } else {
                            "Emergency: off"
                        }
                    )
                    if (emergencyActive) {
                        append("\nEmergency active until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(emergencyUntil))}")
                    }
                }
            }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                        deleteGroupLimit(limit.groupId)
                    }
                }
            }
            top.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(delete)
            row.addView(top)

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val emergencySettings = Button(this).apply {
                text = "Emergency Settings"
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                        showEmergencyConfigDialog(limit, emergencyConfig)
                    }
                }
            }
            val emergencyUnlock = Button(this).apply {
                text = "Emergency Unlock"
                isEnabled = emergencyConfig?.enabled == true && remainingUnlocks > 0
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                        showEmergencyUnlockFlow(limit, emergencyConfig, remainingUnlocks)
                    }
                }
            }
            actions.addView(emergencySettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(emergencyUnlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actions)
            groupLimitsContainer.addView(row)
        }
    }

    private fun renderMappings(mappings: List<AppGroupMap>) {
        mappingsContainer.removeAllViews()
        if (mappings.isEmpty()) {
            mappingsContainer.addView(TextView(this).apply { text = "No app-group mappings configured." })
            return
        }
        mappings.sortedWith(compareBy({ it.groupId }, { it.packageName })).forEach { map ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply { text = "${map.packageName} -> ${map.groupId}" }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    adminGate.runWithCapability(AdminCapability.REMOVE_APP_FROM_GROUP) {
                        deleteMapping(map.packageName, map.groupId)
                    }
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(delete)
            mappingsContainer.addView(row)
        }
    }

    private fun saveAppLimit() {
        val pkg = appPackageInput.text.toString().trim()
        val minutes = appMinutesInput.text.toString().trim().toLongOrNull()
        if (pkg.isBlank() || minutes == null || minutes <= 0) {
            Toast.makeText(this, "Enter package and positive daily minutes", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.budgetDao().upsertAppLimit(
                    AppLimit(packageName = pkg, dailyLimitMs = minutes * 60_000L, enabled = true)
                )
            }
            appPackageInput.setText("")
            appMinutesInput.setText("")
            loadAndRender(enforce = true)
        }
    }

    private fun saveGroupLimit() {
        val groupId = groupIdInput.text.toString().trim()
        val name = groupNameInput.text.toString().trim().ifEmpty { groupId }
        val daily = groupDailyMinutesInput.text.toString().trim().toLongOrNull()
        val hourly = groupHourlyMinutesInput.text.toString().trim().toLongOrNull()
        val opens = groupOpensInput.text.toString().trim().toIntOrNull()
        if (groupId.isBlank() || daily == null || daily <= 0 || hourly == null || hourly <= 0 || opens == null || opens <= 0) {
            Toast.makeText(this, "Enter group id, daily/hourly minutes, and opens/day", Toast.LENGTH_SHORT).show()
            return
        }
        if (hourly > daily) {
            Toast.makeText(this, "Hourly limit must be <= daily limit", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.budgetDao().upsertGroupLimit(
                    GroupLimit(
                        groupId = groupId,
                        name = name,
                        dailyLimitMs = daily * 60_000L,
                        hourlyLimitMs = hourly * 60_000L,
                        opensPerDay = opens,
                        enabled = true
                    )
                )
            }
            groupIdInput.setText("")
            groupNameInput.setText("")
            groupDailyMinutesInput.setText("")
            groupHourlyMinutesInput.setText("")
            groupOpensInput.setText("")
            loadAndRender(enforce = true)
        }
    }

    private fun saveMapping() {
        val pkg = mappingPackageInput.text.toString().trim()
        val group = mappingGroupInput.text.toString().trim()
        if (pkg.isBlank() || group.isBlank()) {
            Toast.makeText(this, "Enter package and group id", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.budgetDao().upsertSingleGroupMapping(AppGroupMap(packageName = pkg, groupId = group))
            }
            mappingPackageInput.setText("")
            mappingGroupInput.setText("")
            Toast.makeText(this@BudgetsActivity, "Mapping saved (replaces prior group for app)", Toast.LENGTH_SHORT).show()
            loadAndRender(enforce = true)
        }
    }

    private fun showEmergencyConfigDialog(limit: GroupLimit, current: GroupEmergencyConfig?) {
        val enabled = CheckBox(this).apply {
            text = "Enable emergency usage for this group"
            isChecked = current?.enabled == true
        }
        val unlocksInput = numberInput("Emergency unlocks/day").apply {
            setText((current?.unlocksPerDay ?: 2).toString())
        }
        val minutesInput = numberInput("Minutes per unlock").apply {
            setText((current?.minutesPerUnlock ?: 10).toString())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
            addView(enabled)
            addView(unlocksInput, lp(top = dp(8)))
            addView(minutesInput, lp(top = dp(8)))
        }

        AlertDialog.Builder(this)
            .setTitle("Emergency usage: ${limit.name}")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val unlocks = unlocksInput.text.toString().trim().toIntOrNull()
                val minutes = minutesInput.text.toString().trim().toIntOrNull()
                if (enabled.isChecked && (unlocks == null || unlocks <= 0 || minutes == null || minutes <= 0)) {
                    Toast.makeText(this, "Enter positive unlock/day and minutes/unlock", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val config = GroupEmergencyConfig(
                    groupId = limit.groupId,
                    enabled = enabled.isChecked,
                    unlocksPerDay = (unlocks ?: 0).coerceAtLeast(0),
                    minutesPerUnlock = (minutes ?: 0).coerceAtLeast(0)
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.budgetDao().upsertGroupEmergencyConfig(config) }
                    loadAndRender(enforce = true)
                }
            }
            .show()
    }

    private fun showEmergencyUnlockFlow(limit: GroupLimit, config: GroupEmergencyConfig?, remainingUnlocks: Int) {
        if (config == null || !config.enabled) {
            Toast.makeText(this, "Emergency usage is not enabled for ${limit.name}", Toast.LENGTH_SHORT).show()
            return
        }
        if (remainingUnlocks <= 0) {
            Toast.makeText(this, "No emergency unlocks left today for ${limit.name}", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency unlock: ${limit.name}")
            .setMessage(
                "This consumes 1 unlock (${config.minutesPerUnlock} minutes).\n" +
                    "Remaining today: $remainingUnlocks/${config.unlocksPerDay}"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                showEmergencyFrictionPuzzle(limit, config)
            }
            .show()
    }

    private fun showEmergencyFrictionPuzzle(limit: GroupLimit, config: GroupEmergencyConfig) {
        val a = Random.nextInt(7, 18)
        val b = Random.nextInt(7, 18)
        val answer = a + b

        val timerText = TextView(this).apply { text = "Wait 30s before confirming..." }
        val prompt = TextView(this).apply { text = "Solve to unlock: $a + $b = ?" }
        val input = numberInput("Answer")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
            addView(timerText)
            addView(prompt, lp(top = dp(8)))
            addView(input, lp(top = dp(8)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Emergency friction")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start Session", null)
            .show()

        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = false
        positive.setOnClickListener {
            val value = input.text.toString().trim().toIntOrNull()
            if (value != answer) {
                Toast.makeText(this, "Incorrect puzzle answer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            activateEmergencyUnlock(limit.groupId, config.minutesPerUnlock)
        }

        emergencyPuzzleTimer?.cancel()
        val timer = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                timerText.text = "Wait ${millisUntilFinished / 1_000}s before confirming..."
            }

            override fun onFinish() {
                timerText.text = "Timer complete. Solve puzzle to continue."
                positive.isEnabled = true
            }
        }
        emergencyPuzzleTimer = timer
        dialog.setOnDismissListener {
            if (emergencyPuzzleTimer === timer) {
                timer.cancel()
                emergencyPuzzleTimer = null
            }
        }
        timer.start()
    }

    private fun activateEmergencyUnlock(groupId: String, minutesPerUnlock: Int) {
        lifecycleScope.launch {
            val result = budgetOrchestrator.activateEmergencyUnlock(groupId)
            if (!result.success) {
                Toast.makeText(this@BudgetsActivity, result.message, Toast.LENGTH_SHORT).show()
                loadAndRender(enforce = false)
                return@launch
            }
            Toast.makeText(
                this@BudgetsActivity,
                "Emergency unlock active for $minutesPerUnlock minutes",
                Toast.LENGTH_SHORT
            ).show()
            ScheduleEnforcer(this@BudgetsActivity).enforceNowAsync(
                trigger = "GroupEmergencyUnlock:$groupId",
                hostActivity = this@BudgetsActivity
            )
            loadAndRender(enforce = false)
        }
    }

    private fun saveTouchGrassConfig() {
        val threshold = thresholdInput.text.toString().trim().toIntOrNull()
        val breakMinutes = breakMinutesInput.text.toString().trim().toIntOrNull()
        if (threshold == null || threshold <= 0 || breakMinutes == null || breakMinutes <= 0) {
            Toast.makeText(this, "Enter positive threshold and break minutes", Toast.LENGTH_SHORT).show()
            return
        }
        policyEngine.setTouchGrassConfig(threshold, breakMinutes)
        lifecycleScope.launch {
            ScheduleEnforcer(this@BudgetsActivity).enforceNowAsync(
                trigger = "TouchGrassConfigChanged",
                hostActivity = this@BudgetsActivity
            )
            loadAndRender(enforce = false)
        }
    }

    private fun deleteAppLimit(packageName: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.budgetDao().deleteAppLimit(packageName) }
            loadAndRender(enforce = true)
        }
    }

    private fun deleteGroupLimit(groupId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.budgetDao().deleteMappingsForGroup(groupId)
                db.budgetDao().deleteGroupEmergencyConfig(groupId)
                db.budgetDao().deleteDomainRulesForScope("GROUP", groupId)
                db.budgetDao().deleteGroupLimit(groupId)
            }
            loadAndRender(enforce = true)
        }
    }

    private fun deleteMapping(packageName: String, groupId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.budgetDao().deleteMapping(packageName, groupId) }
            loadAndRender(enforce = true)
        }
    }

    private fun buildContentView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusText = TextView(this)

        val usageGuideButton = Button(this).apply {
            text = "Usage Access Guide"
            setOnClickListener {
                startActivity(Intent(this@BudgetsActivity, UsageAccessGuideActivity::class.java))
            }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadAndRender(enforce = true) }
        }

        appPackageInput = input("App package (com.example.app)")
        appMinutesInput = numberInput("Daily minutes")
        val saveAppButton = Button(this).apply {
            text = "Save App Limit"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_INDIVIDUAL_APP_SETTINGS) {
                    saveAppLimit()
                }
            }
        }
        appLimitsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        groupIdInput = input("Group id (social)")
        groupNameInput = input("Group name (Social)")
        groupDailyMinutesInput = numberInput("Group daily minutes")
        groupHourlyMinutesInput = numberInput("Group hourly minutes")
        groupOpensInput = numberInput("Group opens/day")
        val saveGroupButton = Button(this).apply {
            text = "Save Group Limit"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                    saveGroupLimit()
                }
            }
        }
        groupLimitsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        mappingPackageInput = input("Mapping package")
        mappingGroupInput = input("Mapping group id")
        val saveMappingButton = Button(this).apply {
            text = "Add Mapping"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.ADD_APP_TO_GROUP) {
                    saveMapping()
                }
            }
        }
        mappingsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        touchGrassStatusText = TextView(this)
        thresholdInput = numberInput("Touch Grass unlock threshold")
        breakMinutesInput = numberInput("Touch Grass break minutes")
        val saveTouchGrassButton = Button(this).apply {
            text = "Save Touch Grass Config"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                    saveTouchGrassConfig()
                }
            }
        }

        container.addView(statusText)
        container.addView(usageGuideButton, lp(top = dp(8)))
        container.addView(refreshButton, lp(top = dp(8)))
        container.addView(sectionTitle("App Limits"), lp(top = dp(16)))
        container.addView(appPackageInput, lp(top = dp(8)))
        container.addView(appMinutesInput, lp(top = dp(8)))
        container.addView(saveAppButton, lp(top = dp(8)))
        container.addView(appLimitsContainer, lp(top = dp(8)))
        container.addView(sectionTitle("Group Limits"), lp(top = dp(16)))
        container.addView(groupIdInput, lp(top = dp(8)))
        container.addView(groupNameInput, lp(top = dp(8)))
        container.addView(groupDailyMinutesInput, lp(top = dp(8)))
        container.addView(groupHourlyMinutesInput, lp(top = dp(8)))
        container.addView(groupOpensInput, lp(top = dp(8)))
        container.addView(saveGroupButton, lp(top = dp(8)))
        container.addView(groupLimitsContainer, lp(top = dp(8)))
        container.addView(sectionTitle("App Group Mapping"), lp(top = dp(16)))
        container.addView(mappingPackageInput, lp(top = dp(8)))
        container.addView(mappingGroupInput, lp(top = dp(8)))
        container.addView(saveMappingButton, lp(top = dp(8)))
        container.addView(mappingsContainer, lp(top = dp(8)))
        container.addView(sectionTitle("Touch Grass"), lp(top = dp(16)))
        container.addView(touchGrassStatusText, lp(top = dp(8)))
        container.addView(thresholdInput, lp(top = dp(8)))
        container.addView(breakMinutesInput, lp(top = dp(8)))
        container.addView(saveTouchGrassButton, lp(top = dp(8)))

        root.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun input(hintText: String): EditText {
        return EditText(this).apply { hint = hintText }
    }

    private fun numberInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun sectionTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
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
        emergencyPuzzleTimer?.cancel()
        emergencyPuzzleTimer = null
        super.onDestroy()
    }
}
