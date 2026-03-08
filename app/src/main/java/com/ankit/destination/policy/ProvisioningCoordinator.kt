package com.ankit.destination.policy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageAccessMonitor

class ProvisioningCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val facade = DevicePolicyFacade(appContext)
    private val store = PolicyStore(appContext)

    enum class FinalizationState {
        PENDING,
        SUCCESS,
        FAILED
    }

    data class Snapshot(
        val adminComponent: String,
        val adminActive: Boolean,
        val deviceOwnerActive: Boolean,
        val usageAccessGranted: Boolean,
        val accessibilityServiceEnabled: Boolean,
        val accessibilityServiceRunning: Boolean,
        val qrValidation: ProvisioningConfig.ValidationResult,
        val lastSignalAction: String?,
        val lastSignalAtMs: Long?,
        val lastSource: String?,
        val lastEnrollmentId: String?,
        val lastSchemaVersion: Int?,
        val lastFinalizationState: FinalizationState?,
        val lastFinalizationMessage: String?,
        val lastFinalizationAtMs: Long?
    )

    data class FinalizationResult(
        val state: FinalizationState,
        val message: String,
        val verificationIssues: List<String> = emptyList()
    ) {
        val completed: Boolean = state == FinalizationState.SUCCESS
    }

    fun adminComponentString(): String = facade.adminComponent.flattenToShortString()

    fun qrPayload(): String = ProvisioningConfig.buildQrPayload(adminComponentString())

    fun snapshot(): Snapshot {
        val adminComponent = adminComponentString()
        val accessibilityState = AccessibilityStatusMonitor.refreshNow(appContext, "provisioning_snapshot")
        return Snapshot(
            adminComponent = adminComponent,
            adminActive = facade.isAdminActive(),
            deviceOwnerActive = facade.isDeviceOwner(),
            usageAccessGranted = UsageAccess.hasUsageAccess(appContext),
            accessibilityServiceEnabled = accessibilityState.enabled,
            accessibilityServiceRunning = AccessibilityStatusMonitor.serviceRunning(accessibilityState),
            qrValidation = ProvisioningConfig.validateQrProvisioning(adminComponent),
            lastSignalAction = store.getProvisioningSignalAction(),
            lastSignalAtMs = store.getProvisioningSignalAtMs(),
            lastSource = store.getProvisioningSource(),
            lastEnrollmentId = store.getProvisioningEnrollmentId(),
            lastSchemaVersion = store.getProvisioningSchemaVersion(),
            lastFinalizationState = store.getProvisioningFinalizationState()?.let {
                runCatching { FinalizationState.valueOf(it) }.getOrNull()
            },
            lastFinalizationMessage = store.getProvisioningFinalizationMessage(),
            lastFinalizationAtMs = store.getProvisioningFinalizationAtMs()
        )
    }

    fun recordProvisioningSignal(action: String?, adminExtras: PersistableBundle?) {
        store.recordProvisioningSignal(
            action = action,
            source = adminExtras?.getString(ProvisioningConfig.adminExtraSource),
            enrollmentId = adminExtras?.getString(ProvisioningConfig.adminExtraEnrollmentId),
            schemaVersion = adminExtras?.getInt(ProvisioningConfig.adminExtraSchemaVersion)
                ?.takeIf { it > 0 }
        )
        FocusLog.i(
            FocusEventId.PROVISIONING_SIGNAL,
            "Provisioning signal action=${action ?: "unknown"} source=${adminExtras?.getString(ProvisioningConfig.adminExtraSource) ?: "n/a"}"
        )
    }

    fun finalizeProvisioning(trigger: String, adminExtras: PersistableBundle?): FinalizationResult {
        UsageAccessMonitor.refreshNow(
            context = appContext,
            reason = "provisioning_finalize",
            requestPolicyRefreshIfChanged = false
        )
        recordProvisioningSignal(trigger, adminExtras)
        val accessibilityState = AccessibilityStatusMonitor.refreshNow(appContext, "provisioning_finalize")
        provisioningPendingReason(
            isDeviceOwner = facade.isDeviceOwner(),
            usageAccessGranted = UsageAccess.hasUsageAccess(appContext),
            accessibilityServiceEnabled = accessibilityState.enabled,
            accessibilityServiceRunning = AccessibilityStatusMonitor.serviceRunning(accessibilityState)
        )?.let { message ->
            store.recordProvisioningFinalization(FinalizationState.PENDING.name, message)
            return FinalizationResult(FinalizationState.PENDING, message)
        }

        FocusLog.i(FocusEventId.PROVISIONING_FINALIZE_START, "Finalizing provisioning trigger=$trigger")
        val engineResult = PolicyApplyOrchestrator.applyNowBlocking(
            context = appContext,
            reason = "provisioning_finalize"
        )
        val finalization = if (engineResult.success) {
            FinalizationResult(
                state = FinalizationState.SUCCESS,
                message = "Provisioning finalized. Baseline policy applied.",
                verificationIssues = engineResult.verification.issues
            )
        } else {
            FinalizationResult(
                state = FinalizationState.FAILED,
                message = engineResult.message,
                verificationIssues = engineResult.verification.issues
            )
        }
        store.recordProvisioningFinalization(finalization.state.name, finalization.message)
        if (finalization.completed) {
            FocusLog.i(FocusEventId.PROVISIONING_FINALIZE_DONE, finalization.message)
        } else {
            FocusLog.w(FocusEventId.PROVISIONING_FINALIZE_FAIL, finalization.message)
        }
        return finalization
    }

    companion object {
        const val actionShowProvisioningStatus: String =
            "com.ankit.destination.action.SHOW_PROVISIONING_STATUS"
        const val extraSourceAction: String =
            "com.ankit.destination.extra.PROVISIONING_SOURCE_ACTION"
        const val extraReturnToMain: String =
            "com.ankit.destination.extra.PROVISIONING_RETURN_TO_MAIN"

        fun buildAdminExtras(): PersistableBundle {
            val defaults = ProvisioningConfig.defaultAdminExtras()
            return PersistableBundle().apply {
                putString(ProvisioningConfig.adminExtraSource, defaults.source)
                putString(ProvisioningConfig.adminExtraEnrollmentId, defaults.enrollmentId)
                putInt(ProvisioningConfig.adminExtraSchemaVersion, defaults.schemaVersion)
            }
        }

        fun extractAdminExtras(intent: Intent): PersistableBundle? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    ProvisioningConfig.extraProvisioningAdminExtrasBundle,
                    PersistableBundle::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(ProvisioningConfig.extraProvisioningAdminExtrasBundle)
            }
        }
    }
}

internal fun provisioningPendingReason(
    isDeviceOwner: Boolean,
    usageAccessGranted: Boolean,
    accessibilityServiceEnabled: Boolean,
    accessibilityServiceRunning: Boolean
): String? {
    return when {
        !isDeviceOwner -> "Device owner not active yet; continue setup to finish enrollment."
        !usageAccessGranted -> "Grant Usage Access to Destination before finishing enrollment."
        !accessibilityServiceEnabled -> "Enable Destination Accessibility before finishing enrollment."
        !accessibilityServiceRunning -> "Destination Accessibility is enabled, but the enforcement service is not running yet."
        else -> null
    }
}
