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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer
import com.ankit.destination.schedule.ScheduleEvaluator
import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.AdminGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SchedulesActivity : AppCompatActivity() {
    private lateinit var policyEngine: PolicyEngine
    private lateinit var db: FocusDatabase
    private lateinit var adminGate: AdminGate

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyEngine = PolicyEngine(this)
        db = FocusDatabase.get(this)
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
                ScheduleEnforcer(this@SchedulesActivity)
                    .enforceNowAsync("SchedulesActivityResume", hostActivity = this@SchedulesActivity)
            }
            val blocks = withContext(Dispatchers.IO) { db.scheduleDao().getAllBlocks() }
            val blockGroups = withContext(Dispatchers.IO) {
                db.scheduleDao().getAllBlockGroups()
                    .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                    .mapValues { it.value.toSet() }
            }
            renderLoadedState(blocks, blockGroups)
        }
    }

    private fun renderLoadedState(blocks: List<ScheduleBlock>, blockGroups: Map<Long, Set<String>>) {
        val snapshot = policyEngine.diagnosticsSnapshot()
        val nextTransition = snapshot.scheduleNextTransitionAtMs?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: "none"
        statusText.text = buildString {
            append("Schedule computed active: ${snapshot.scheduleLockComputed}\n")
            append("Schedule lock active: ${snapshot.scheduleLockActive}\n")
            append("Strict computed active: ${snapshot.scheduleStrictComputed}\n")
            append("Strict lock active: ${snapshot.scheduleStrictActive}\n")
            append("Schedule blocked groups: ${snapshot.scheduleBlockedGroups.joinToString().ifBlank { "none" }}\n")
            append("Reason: ${snapshot.scheduleLockReason ?: "none"}\n")
            append("Next transition: $nextTransition")
        }

        listContainer.removeAllViews()
        if (blocks.isEmpty()) {
            listContainer.addView(TextView(this).apply { text = "No schedules yet." })
            return
        }

        blocks.forEach { block ->
            listContainer.addView(buildBlockView(block, blockGroups[block.id].orEmpty()))
        }
    }

    private fun buildBlockView(block: ScheduleBlock, groups: Set<String>): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val name = TextView(this).apply {
            val imm = if (block.immutable) ", immutable" else ""
            val strict = if (block.strict) ", strict" else ""
            text = "${block.name} (${if (block.enabled) "enabled" else "disabled"}, ${block.kind}$strict$imm)"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val details = TextView(this).apply {
            val groupsText = if (groups.isEmpty()) "-" else groups.joinToString(", ")
            text = "Days=${daysMaskToText(block.daysMask)}  ${minuteToText(block.startMinute)}-${minuteToText(block.endMinute)}  TZ=${block.timezoneMode}  Groups=$groupsText"
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val edit = Button(this).apply {
            text = "Edit"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                    showEditorDialog(block, groups)
                }
            }
        }
        val delete = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                    deleteBlock(block)
                }
            }
        }
        buttons.addView(
            edit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            delete,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        wrapper.addView(name)
        wrapper.addView(details)
        wrapper.addView(buttons, lp(top = dp(8)))
        return wrapper
    }

    private fun deleteBlock(block: ScheduleBlock) {
        if (block.immutable) {
            Toast.makeText(this, "This schedule is immutable.", Toast.LENGTH_SHORT).show()
            return
        }
        if (policyEngine.isScheduleLockActive()) {
            Toast.makeText(this, "Locked by schedule. Changes are blocked until transition.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.scheduleDao().deleteBlockWithGroups(block.id)
            }
            ScheduleEnforcer(this@SchedulesActivity).enforceNowAsync("ScheduleDeleted", hostActivity = this@SchedulesActivity)
            val blocks = withContext(Dispatchers.IO) { db.scheduleDao().getAllBlocks() }
            val blockGroups = withContext(Dispatchers.IO) {
                db.scheduleDao().getAllBlockGroups()
                    .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                    .mapValues { it.value.toSet() }
            }
            renderLoadedState(blocks, blockGroups)
        }
    }

    private fun showEditorDialog(existing: ScheduleBlock?, existingGroups: Set<String> = emptySet()) {
        if (existing?.immutable == true) {
            Toast.makeText(this, "This schedule is immutable.", Toast.LENGTH_SHORT).show()
            return
        }
        if (policyEngine.isScheduleLockActive()) {
            Toast.makeText(this, "Locked by schedule. Changes are blocked until transition.", Toast.LENGTH_SHORT).show()
            return
        }

        val dayLabels = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayChecks = dayLabels.map { CheckBox(this).apply { text = it } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val nameInput = EditText(this).apply {
            hint = "Name"
            setText(existing?.name ?: "")
        }
        val startInput = EditText(this).apply {
            hint = "Start HH:MM"
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(existing?.startMinute?.let { minuteToText(it) } ?: "09:00")
        }
        val endInput = EditText(this).apply {
            hint = "End HH:MM"
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(existing?.endMinute?.let { minuteToText(it) } ?: "13:00")
        }
        val enabledCheck = CheckBox(this).apply {
            text = "Enabled"
            isChecked = existing?.enabled ?: true
        }
        val kindInput = EditText(this).apply {
            hint = "Kind: NUCLEAR or GROUPS"
            setText(existing?.kind ?: ScheduleBlockKind.NUCLEAR.name)
        }
        val strictCheck = CheckBox(this).apply {
            text = "Strict block (auto-suspend new installs while active)"
            isChecked = existing?.strict ?: false
        }
        val groupIdsInput = EditText(this).apply {
            hint = "Group IDs (comma separated, for GROUPS kind)"
            setText(existingGroups.joinToString(", "))
        }

        val existingMask = existing?.daysMask ?: (ScheduleEvaluator.dayToBit(java.time.DayOfWeek.MONDAY) or
            ScheduleEvaluator.dayToBit(java.time.DayOfWeek.TUESDAY) or
            ScheduleEvaluator.dayToBit(java.time.DayOfWeek.WEDNESDAY) or
            ScheduleEvaluator.dayToBit(java.time.DayOfWeek.THURSDAY) or
            ScheduleEvaluator.dayToBit(java.time.DayOfWeek.FRIDAY))
        dayChecks.forEachIndexed { index, box ->
            box.isChecked = (existingMask and (1 shl index)) != 0
        }

        root.addView(nameInput)
        root.addView(startInput, lp(top = dp(8)))
        root.addView(endInput, lp(top = dp(8)))
        root.addView(enabledCheck, lp(top = dp(8)))
        root.addView(kindInput, lp(top = dp(8)))
        root.addView(strictCheck, lp(top = dp(8)))
        root.addView(groupIdsInput, lp(top = dp(8)))
        dayChecks.forEach { root.addView(it) }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Schedule" else "Edit Schedule")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Schedule" }
                val startMinute = parseTimeToMinute(startInput.text.toString().trim())
                val endMinute = parseTimeToMinute(endInput.text.toString().trim())
                if (startMinute == null || endMinute == null) {
                    Toast.makeText(this, "Invalid time. Use HH:MM", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                var mask = 0
                dayChecks.forEachIndexed { index, checkBox ->
                    if (checkBox.isChecked) mask = mask or (1 shl index)
                }
                if (mask == 0) {
                    Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val kind = runCatching {
                    ScheduleBlockKind.valueOf(kindInput.text.toString().trim().uppercase())
                }.getOrElse {
                    Toast.makeText(this, "Kind must be NUCLEAR or GROUPS", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val groupIds = groupIdsInput.text.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (kind == ScheduleBlockKind.GROUPS && groupIds.isEmpty()) {
                    Toast.makeText(this, "GROUPS schedule needs at least one group id", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val block = ScheduleBlock(
                    id = existing?.id ?: 0L,
                    name = name,
                    daysMask = mask,
                    startMinute = startMinute,
                    endMinute = endMinute,
                    enabled = enabledCheck.isChecked,
                    kind = kind.name,
                    strict = kind == ScheduleBlockKind.GROUPS && strictCheck.isChecked,
                    immutable = existing?.immutable ?: false,
                    timezoneMode = existing?.timezoneMode ?: "DEVICE_LOCAL"
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val blockId = db.scheduleDao().upsert(block)
                        db.scheduleDao().replaceGroupsForBlock(
                            blockId = blockId,
                            groupIds = if (kind == ScheduleBlockKind.GROUPS) groupIds else emptyList()
                        )
                    }
                    ScheduleEnforcer(this@SchedulesActivity)
                        .enforceNowAsync("ScheduleSaved", hostActivity = this@SchedulesActivity)
                    val blocks = withContext(Dispatchers.IO) { db.scheduleDao().getAllBlocks() }
                    val blockGroups = withContext(Dispatchers.IO) {
                        db.scheduleDao().getAllBlockGroups()
                            .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                            .mapValues { it.value.toSet() }
                    }
                    renderLoadedState(blocks, blockGroups)
                }
            }
            .show()
    }

    private fun buildContentView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        statusText = TextView(this)
        val addButton = Button(this).apply {
            text = "Add Schedule"
            setOnClickListener {
                adminGate.runWithCapability(AdminCapability.EDIT_GROUP_SETTINGS) {
                    showEditorDialog(existing = null, existingGroups = emptySet())
                }
            }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadAndRender(enforce = true) }
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(statusText)
        container.addView(addButton, lp(top = dp(8)))
        container.addView(refreshButton, lp(top = dp(8)))
        container.addView(listContainer, lp(top = dp(12)))
        root.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun parseTimeToMinute(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun minuteToText(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return "%02d:%02d".format(h, m)
    }

    private fun daysMaskToText(mask: Int): String {
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return labels.filterIndexed { index, _ -> (mask and (1 shl index)) != 0 }
            .joinToString(", ")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = top
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        super.onDestroy()
    }
}
