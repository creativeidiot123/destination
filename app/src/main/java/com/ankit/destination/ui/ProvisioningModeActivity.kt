package com.ankit.destination.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.ProvisioningConfig
import com.ankit.destination.policy.ProvisioningCoordinator

class ProvisioningModeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val coordinator = ProvisioningCoordinator(this)
        val adminExtras = ProvisioningCoordinator.extractAdminExtras(intent)
        coordinator.recordProvisioningSignal(intent.action, adminExtras)
        FocusLog.i(
            FocusEventId.PROVISIONING_MODE_REQUEST,
            "Provisioning mode requested action=${intent.action ?: "unknown"}"
        )

        val allowedModes = mutableSetOf<Int>().apply {
            intent.getIntArrayExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
                ?.let { addAll(it.toList()) }
            intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
                ?.let { addAll(it) }
        }
        val fullyManagedAllowed =
            allowedModes.isEmpty() || allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)

        if (fullyManagedAllowed) {
            val resultIntent = Intent()
                .putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                )
                .putExtra(
                    ProvisioningConfig.extraProvisioningAdminExtrasBundle,
                    adminExtras ?: ProvisioningCoordinator.buildAdminExtras()
                )
            setResult(RESULT_OK, resultIntent)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}