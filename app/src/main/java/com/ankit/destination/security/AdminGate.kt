package com.ankit.destination.security

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AdminGate(private val activity: AppCompatActivity) {
    private val appLock = AppLockManager(activity)

    fun runWithCapability(
        capability: AdminCapability,
        onGranted: () -> Unit
    ) {
        if (!CapabilityPolicy.requiresPassword(capability)) {
            onGranted()
            return
        }
        if (appLock.isSessionUnlocked()) {
            onGranted()
            return
        }
        if (!appLock.isPasswordSet()) {
            showSetPasswordDialog(onGranted)
            return
        }
        showUnlockDialog(onGranted)
    }

    fun lockNow() {
        appLock.clearSession()
    }

    private fun showSetPasswordDialog(onGranted: () -> Unit) {
        val first = passwordInput("Set admin password")
        val second = passwordInput("Confirm admin password")
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(first)
            addView(second)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Create Admin Password")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                if (a != b) {
                    Toast.makeText(activity, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!appLock.setPassword(a)) {
                    Toast.makeText(activity, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onGranted()
            }
        }
        dialog.show()
    }

    private fun showUnlockDialog(onGranted: () -> Unit) {
        val input = passwordInput("Admin password")
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Unlock Admin Action")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ok = appLock.unlock(input.text.toString())
                if (!ok) {
                    Toast.makeText(activity, "Incorrect password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onGranted()
            }
        }
        dialog.show()
    }

    private fun passwordInput(hint: String): EditText {
        return EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }
}
