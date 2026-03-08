package com.ankit.destination.ui

import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.ankit.destination.policy.AppProtectionSnapshot
import com.ankit.destination.policy.PolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class AppOption(
    val packageName: String,
    val label: String,
    val isSelectable: Boolean = true,
    val supportingTag: String? = null
) {
    val displayLabel: String = "$label\n$packageName"
}

data class SpinnerOption<T>(
    val label: String,
    val value: T
) {
    override fun toString(): String = label
}

internal suspend fun loadInstalledAppOptions(
    context: Context,
    includePackageNames: Set<String> = emptySet(),
    launchableOnly: Boolean = true,
    includeHiddenApps: Boolean = false,
    disabledPackageReasons: Map<String, String> = emptyMap(),
    protectionSnapshot: AppProtectionSnapshot? = null
): List<AppOption> = withContext(Dispatchers.IO) {
    val packageManager = context.packageManager
    val resolvedProtectionSnapshot = protectionSnapshot
        ?: PolicyEngine(context.applicationContext).getAppProtectionSnapshotAsync()
    val catalogEntries = SharedInstalledAppCatalogCache.getCatalog(
        context = context,
        launchableOnly = launchableOnly
    ).filterNot { entry ->
        !includeHiddenApps && resolvedProtectionSnapshot.shouldHideFromStandardLists(entry.packageName)
    }
    val cachedPackageNames = catalogEntries.asSequence()
        .map(InstalledAppCatalogEntry::packageName)
        .toHashSet()
    val includedEntries = includePackageNames
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(cachedPackageNames::contains)
        .map { packageName ->
            resolveInstalledAppCatalogEntry(
                packageManager = packageManager,
                packageName = packageName
            )
        }
        .filterNot { entry ->
            !includeHiddenApps && resolvedProtectionSnapshot.shouldHideFromStandardLists(entry.packageName)
        }
        .toList()

    buildAppOptions(
        catalogEntries = catalogEntries,
        includedEntries = includedEntries,
        disabledPackageReasons = disabledPackageReasons
    )
}

internal fun buildAppOptions(
    catalogEntries: List<InstalledAppCatalogEntry>,
    includedEntries: List<InstalledAppCatalogEntry>,
    disabledPackageReasons: Map<String, String>
): List<AppOption> {
    val mergedEntries = linkedMapOf<String, InstalledAppCatalogEntry>()
    catalogEntries.forEach { entry ->
        mergedEntries[entry.packageName] = entry
    }
    includedEntries.forEach { entry ->
        mergedEntries[entry.packageName] = entry
    }

    return mergedEntries.values
        .map { entry ->
            AppOption(
                packageName = entry.packageName,
                label = entry.label,
                isSelectable = !disabledPackageReasons.containsKey(entry.packageName),
                supportingTag = disabledPackageReasons[entry.packageName]
            )
        }
        .sortedWith(compareBy<AppOption> { it.label.lowercase(Locale.getDefault()) }.thenBy { it.packageName })
}

fun <T> AppCompatActivity.spinnerAdapter(options: List<SpinnerOption<T>>): ArrayAdapter<SpinnerOption<T>> {
    return ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        options
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
}

fun AppCompatActivity.showAppPickerDialog(
    title: String,
    options: List<AppOption>,
    onSelected: (AppOption) -> Unit
) {
    val searchInput = EditText(this).apply {
        hint = "Search apps"
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val listView = ListView(this)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ).apply { topMargin = dp(8) }
        )
    }

    var filtered = options
    val adapter = ArrayAdapter(
        this,
        android.R.layout.simple_list_item_1,
        filtered.map(AppOption::displayLabel)
    )
    listView.adapter = adapter

    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(content)
        .setNegativeButton("Cancel", null)
        .create()

    listView.setOnItemClickListener { _, _, position, _ ->
        val item = filtered.getOrNull(position) ?: return@setOnItemClickListener
        dialog.dismiss()
        onSelected(item)
    }

    searchInput.doAfterTextChanged { editable ->
        val query = editable?.toString().orEmpty().trim().lowercase(Locale.getDefault())
        filtered = if (query.isBlank()) {
            options
        } else {
            options.filter { option ->
                option.label.lowercase(Locale.getDefault()).contains(query) ||
                    option.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        }
        adapter.clear()
        adapter.addAll(filtered.map(AppOption::displayLabel))
        adapter.notifyDataSetChanged()
    }

    dialog.show()
}

fun AppCompatActivity.showSingleNumberPickerDialog(
    title: String,
    initialValue: Int,
    minSelectableValue: Int,
    maxSelectableValue: Int,
    onSelected: (Int) -> Unit
) {
    val picker = NumberPicker(this).apply {
        minValue = minSelectableValue
        maxValue = maxSelectableValue
        wrapSelectorWheel = false
        value = initialValue.coerceIn(minSelectableValue, maxSelectableValue)
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(picker)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Select") { _, _ ->
            onSelected(picker.value)
        }
        .show()
}

fun AppCompatActivity.showTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onSelected: (hour: Int, minute: Int) -> Unit
) {
    TimePickerDialog(
        this,
        { _, hourOfDay, minute ->
            onSelected(hourOfDay, minute)
        },
        initialHour,
        initialMinute,
        true
    ).apply {
        setTitle(title)
    }.show()
}

fun View.setEnabledRecursively(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.45f
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).setEnabledRecursively(enabled)
        }
    }
}

fun formatMinutesLabel(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

fun minuteToTimeLabel(totalMinutes: Int): String {
    val hour = totalMinutes / 60
    val minute = totalMinutes % 60
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}

fun deriveGroupId(name: String): String {
    return name
        .trim()
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
