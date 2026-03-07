package com.ankit.destination.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.ProvisioningConfig
import com.ankit.destination.policy.ProvisioningCoordinator
import com.ankit.destination.ui.ProvisioningComplianceActivity
import com.ankit.destination.packages.PackageChangeReceiver

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        FocusLog.i(FocusEventId.ADMIN_ENABLED, "Device admin enabled")
        PackageChangeReceiver.ensureRuntimeRegistration(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        FocusLog.w(FocusEventId.ADMIN_DISABLED, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val coordinator = ProvisioningCoordinator(context)
        val adminExtras = ProvisioningCoordinator.extractAdminExtras(intent)
        coordinator.recordProvisioningSignal(ACTION_PROFILE_PROVISIONING_COMPLETE, adminExtras)
        FocusLog.i(FocusEventId.PROVISIONING_SIGNAL, "Profile provisioning complete broadcast received")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(context, ProvisioningComplianceActivity::class.java).apply {
                    action = ProvisioningCoordinator.actionShowProvisioningStatus
                    putExtra(ProvisioningCoordinator.extraSourceAction, ACTION_PROFILE_PROVISIONING_COMPLETE)
                    putExtra(ProvisioningCoordinator.extraReturnToMain, true)
                    if (adminExtras != null) {
                        putExtra(ProvisioningConfig.extraProvisioningAdminExtrasBundle, adminExtras)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}

