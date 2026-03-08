package com.ankit.destination.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.ankit.destination.policy.ProvisioningCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ShizukuStatus(
    val serviceAvailable: Boolean,
    val permissionGranted: Boolean,
    val backendLabel: String,
    val detail: String
)

data class DeviceOwnerSetupSnapshot(
    val adminComponent: String,
    val adminActive: Boolean,
    val deviceOwnerActive: Boolean,
    val usageAccessGranted: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    val qrPayload: String?,
    val qrReady: Boolean,
    val qrErrors: List<String>,
    val adbSetDeviceOwnerCommand: String,
    val adbVerifyCommand: String,
    val shizukuStatus: ShizukuStatus
)

data class ShellCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val summary: String
) {
    val succeeded: Boolean = exitCode == 0
}

class DeviceOwnerSetupController(context: Context) {
    private val appContext = context.applicationContext
    private val coordinator = ProvisioningCoordinator(appContext)

    fun snapshot(): DeviceOwnerSetupSnapshot {
        val snapshot = coordinator.snapshot()
        return DeviceOwnerSetupSnapshot(
            adminComponent = snapshot.adminComponent,
            adminActive = snapshot.adminActive,
            deviceOwnerActive = snapshot.deviceOwnerActive,
            usageAccessGranted = snapshot.usageAccessGranted,
            accessibilityServiceEnabled = snapshot.accessibilityServiceEnabled,
            accessibilityServiceRunning = snapshot.accessibilityServiceRunning,
            qrPayload = snapshot.qrValidation.takeIf { it.isReady }?.let { coordinator.qrPayload() },
            qrReady = snapshot.qrValidation.isReady,
            qrErrors = snapshot.qrValidation.errors,
            adbSetDeviceOwnerCommand = buildSetDeviceOwnerCommand(snapshot.adminComponent),
            adbVerifyCommand = ADB_VERIFY_DEVICE_OWNER_COMMAND,
            shizukuStatus = shizukuStatus()
        )
    }

    fun buildSetDeviceOwnerCommand(adminComponent: String = coordinator.adminComponentString()): String {
        return "dpm set-device-owner $adminComponent"
    }

    fun shizukuStatus(): ShizukuStatus {
        val binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderReady &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val backendUid = if (binderReady) runCatching { Shizuku.getUid() }.getOrNull() else null
        val backendLabel = when (backendUid) {
            0 -> "root"
            2000 -> "adb"
            null -> "unavailable"
            else -> "uid $backendUid"
        }
        val detail = when {
            !binderReady -> "Shizuku service is not running or not installed."
            !permissionGranted -> "Shizuku service is connected, but this app is not authorized yet."
            else -> "Shizuku is ready via $backendLabel identity."
        }
        return ShizukuStatus(
            serviceAvailable = binderReady,
            permissionGranted = permissionGranted,
            backendLabel = backendLabel,
            detail = detail
        )
    }

    suspend fun runShizukuSetDeviceOwner(): ShellCommandResult {
        return runShizukuCommand(buildSetDeviceOwnerCommand(), "set-device-owner")
    }

    suspend fun runShizukuVerifyDeviceOwner(): ShellCommandResult {
        return runShizukuCommand(VERIFY_DEVICE_OWNER_COMMAND, "verify-device-owner")
    }

    private suspend fun runShizukuCommand(command: String, purpose: String): ShellCommandResult {
        val status = shizukuStatus()
        if (!status.serviceAvailable) {
            return ShellCommandResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "",
                summary = "Shizuku is not available."
            )
        }
        if (!status.permissionGranted) {
            return ShellCommandResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "",
                summary = "Authorize this app in Shizuku before running shell commands."
            )
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resultBundle = withTimeout(SHIZUKU_COMMAND_TIMEOUT_MS) {
                    bindShellService().use { shellService ->
                        shellService.runCommand(command)
                    }
                }
                val exitCode = resultBundle.getInt(ShizukuShellUserService.KEY_EXIT_CODE, -1)
                val stdout = resultBundle.getString(ShizukuShellUserService.KEY_STDOUT).orEmpty().trim()
                val stderr = resultBundle.getString(ShizukuShellUserService.KEY_STDERR).orEmpty().trim()
                ShellCommandResult(
                    command = command,
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    summary = summarizeShellResult(
                        purpose = purpose,
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr
                    )
                )
            }.getOrElse { throwable ->
                ShellCommandResult(
                    command = command,
                    exitCode = -1,
                    stdout = "",
                    stderr = throwable.message ?: throwable.javaClass.simpleName,
                    summary = "Shizuku command failed to start: ${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun bindShellService(): BoundShellService {
        return suspendCancellableCoroutine { continuation ->
            val componentName = ComponentName(appContext, ShizukuShellUserService::class.java)
            val args = Shizuku.UserServiceArgs(componentName)
                .processNameSuffix("do_shell")
                .tag("device_owner_shell")
                .version(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service
                    if (binder == null) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Shizuku service returned a null binder")
                            )
                        }
                        runCatching { Shizuku.unbindUserService(args, this, true) }
                        return
                    }
                    if (continuation.isActive) {
                        continuation.resume(BoundShellService(args, this, Messenger(binder)))
                    } else {
                        Shizuku.unbindUserService(args, this, true)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Shizuku service disconnected before command execution")
                        )
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { throwable ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(throwable)
                    }
                }
        }
    }

    private fun summarizeShellResult(
        purpose: String,
        exitCode: Int,
        stdout: String,
        stderr: String
    ): String {
        val combined = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }.lowercase()
        if (exitCode == 0) {
            return when (purpose) {
                "verify-device-owner" -> {
                    stdout.ifBlank { "Device owner command succeeded." }
                }
                else -> "Device owner was assigned successfully. Re-open Destination to finalize policy."
            }
        }
        return when {
            "already some accounts" in combined || "already provisioned" in combined || "already set-up" in combined ->
                "Device owner can only be assigned on a fresh or factory-reset device with no accounts."
            "already has a device owner" in combined || "device owner is already set" in combined ->
                "This device already has a device owner."
            "unknown admin" in combined || "bad component name" in combined ->
                "The admin receiver component was not recognized by the system."
            "permission denied" in combined || "securityexception" in combined ->
                "The shell identity was rejected. Re-check Shizuku authorization and device state."
            purpose == "verify-device-owner" && combined.isBlank() ->
                "Unable to read the current device owner."
            combined.isBlank() ->
                "Command failed with exit code $exitCode."
            else -> combined.lineSequence().first().trim().replaceFirstChar { it.uppercase() }
        }
    }

    private companion object {
        private const val VERIFY_DEVICE_OWNER_COMMAND = "dpm get-device-owner"
        private const val ADB_VERIFY_DEVICE_OWNER_COMMAND = "adb shell dpm get-device-owner"
        private const val SHIZUKU_COMMAND_TIMEOUT_MS = 12_000L
    }

    private class BoundShellService(
        private val args: Shizuku.UserServiceArgs,
        private val connection: ServiceConnection,
        private val shellMessenger: Messenger
    ) : AutoCloseable {
        override fun close() {
            Shizuku.unbindUserService(args, connection, true)
        }

        suspend fun runCommand(command: String): Bundle {
            return suspendCancellableCoroutine { continuation ->
                var replyHandlerRef: Handler? = null
                val replyHandler = Handler(Looper.getMainLooper()) { message ->
                    if (message.what == ShizukuShellUserService.MSG_COMMAND_RESULT) {
                        if (continuation.isActive) {
                            replyHandlerRef?.removeCallbacksAndMessages(null)
                            continuation.resume(message.data)
                        }
                        true
                    } else {
                        false
                    }
                }
                replyHandlerRef = replyHandler
                val request = Message.obtain(null, ShizukuShellUserService.MSG_RUN_COMMAND).apply {
                    data = Bundle().apply {
                        putString(ShizukuShellUserService.KEY_COMMAND, command)
                    }
                    replyTo = Messenger(replyHandler)
                }
                continuation.invokeOnCancellation {
                    replyHandlerRef?.removeCallbacksAndMessages(null)
                }
                runCatching { shellMessenger.send(request) }
                    .onFailure { throwable ->
                        if (continuation.isActive) {
                            continuation.resume(
                                Bundle().apply {
                                    putInt(ShizukuShellUserService.KEY_EXIT_CODE, -1)
                                    putString(
                                        ShizukuShellUserService.KEY_STDERR,
                                        throwable.message ?: throwable.javaClass.simpleName
                                    )
                                }
                            )
                        }
                    }
            }
        }

        inline fun <T> use(block: (BoundShellService) -> T): T {
            return try {
                block(this)
            } finally {
                close()
            }
        }
    }
}
