package com.ankit.destination.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager

internal enum class PackageSuspendBackendStatus {
    HIDDEN,
    DPM_FALLBACK,
    DPM_ONLY
}

internal data class PackageSuspendPrototypeStatus(
    val backend: PackageSuspendBackendStatus,
    val hiddenErrorMessage: String? = null
)

internal data class PackageSuspendCallOptions(
    val suspended: Boolean,
    val dialogTitle: String?,
    val dialogMessageTemplate: String?
)

internal fun buildPackageSuspendCallOptions(
    suspended: Boolean,
    reasonTokens: Set<String> = emptySet()
): PackageSuspendCallOptions {
    val reasonAwareCopy = reasonTokens
        .takeIf { suspended }
        ?.let(HiddenSuspendDialogReasonFormatter::buildDialogCopy)
    return PackageSuspendCallOptions(
        suspended = suspended,
        dialogTitle = (reasonAwareCopy?.title ?: FocusConfig.prototypeHiddenSuspendDialogTitle)
            .takeIf { suspended },
        dialogMessageTemplate = (reasonAwareCopy?.messageTemplate
            ?: FocusConfig.prototypeHiddenSuspendDialogMessageTemplate)
            .takeIf { suspended }
    )
}

private data class SuspendDialogCopy(
    val title: String,
    val messageTemplate: String
)

private object HiddenSuspendDialogReasonFormatter {
    private enum class MessageTemplateKind {
        DEFAULT,
        SCHEDULE,
        HOURLY,
        DAILY,
        OPENS
    }

    private data class Candidate(
        val priority: Int,
        val groupId: String?,
        val reasonLabel: String,
        val messageTemplateKind: MessageTemplateKind
    )

    fun buildDialogCopy(reasonTokens: Set<String>): SuspendDialogCopy? {
        val candidates = reasonTokens.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull(::toCandidate)
            .toList()
        val primary = candidates
            .sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.reasonLabel })
            .firstOrNull()
            ?: return null
        val title = FocusConfig.prototypeHiddenSuspendDialogTitle
        val messageTemplate = buildString {
            append(
                when (primary.messageTemplateKind) {
                    MessageTemplateKind.SCHEDULE -> FocusConfig.prototypeHiddenSuspendScheduleMessageTemplate
                    MessageTemplateKind.HOURLY -> FocusConfig.prototypeHiddenSuspendHourlyMessageTemplate
                    MessageTemplateKind.DAILY -> FocusConfig.prototypeHiddenSuspendDailyMessageTemplate
                    MessageTemplateKind.OPENS -> FocusConfig.prototypeHiddenSuspendOpensMessageTemplate
                    MessageTemplateKind.DEFAULT -> FocusConfig.prototypeHiddenSuspendDialogMessageTemplate
                }
            )
            if (!primary.groupId.isNullOrBlank()) {
                append("/ ${primary.groupId} block is active")
            }
        }
        return SuspendDialogCopy(
            title = title,
            messageTemplate = messageTemplate
        )
    }

    private fun toCandidate(rawToken: String): Candidate? {
        val token = rawToken.trim()
        if (token.isBlank()) return null
        val upper = token.uppercase()

        when (upper) {
            EffectiveBlockReason.ALWAYS_BLOCKED.name ->
                return Candidate(
                    priority = 0,
                    groupId = null,
                    reasonLabel = "Always blocked",
                    messageTemplateKind = MessageTemplateKind.DEFAULT
                )
            EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name ->
                return Candidate(
                    priority = 1,
                    groupId = null,
                    reasonLabel = "Accessibility required",
                    messageTemplateKind = MessageTemplateKind.DEFAULT
                )
            EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name ->
                return Candidate(
                    priority = 2,
                    groupId = null,
                    reasonLabel = "Usage access required",
                    messageTemplateKind = MessageTemplateKind.DEFAULT
                )
            EffectiveBlockReason.STRICT_INSTALL.name ->
                return Candidate(
                    priority = 3,
                    groupId = null,
                    reasonLabel = "Strict schedule block",
                    messageTemplateKind = MessageTemplateKind.SCHEDULE
                )
            "BUDGET" ->
                return Candidate(
                    priority = 7,
                    groupId = null,
                    reasonLabel = "Usage budget reached",
                    messageTemplateKind = MessageTemplateKind.DEFAULT
                )
        }

        if (upper.startsWith("GROUP:")) {
            val parts = token.split(':')
            if (parts.size >= 3) {
                val groupId = parts[1].trim().takeIf(String::isNotBlank)
                val reasonLabel = reasonLabelFromToken(parts.last()) ?: return null
                val priority = priorityFromToken(parts.last())
                return Candidate(
                    priority = priority,
                    groupId = groupId,
                    reasonLabel = reasonLabel,
                    messageTemplateKind = messageTemplateKindFromToken(parts.last())
                )
            }
        }
        if (upper.startsWith("APP:")) {
            val reasonLabel = reasonLabelFromToken(token.substringAfter(':')) ?: return null
            val priority = priorityFromToken(token.substringAfter(':'))
            return Candidate(
                priority = priority,
                groupId = null,
                reasonLabel = reasonLabel,
                messageTemplateKind = messageTemplateKindFromToken(token.substringAfter(':'))
            )
        }
        if (upper.startsWith("GROUP_")) {
            val reasonToken = upper.substringAfter("GROUP_")
            val reasonLabel = reasonLabelFromToken(reasonToken) ?: return null
            val priority = priorityFromToken(reasonToken)
            return Candidate(
                priority = priority,
                groupId = null,
                reasonLabel = reasonLabel,
                messageTemplateKind = messageTemplateKindFromToken(reasonToken)
            )
        }
        if (upper.startsWith("APP_")) {
            val reasonToken = upper.substringAfter("APP_")
            val reasonLabel = reasonLabelFromToken(reasonToken) ?: return null
            val priority = priorityFromToken(reasonToken)
            return Candidate(
                priority = priority,
                groupId = null,
                reasonLabel = reasonLabel,
                messageTemplateKind = messageTemplateKindFromToken(reasonToken)
            )
        }
        val reasonLabel = reasonLabelFromToken(token) ?: return null
        return Candidate(
            priority = priorityFromToken(token),
            groupId = null,
            reasonLabel = reasonLabel,
            messageTemplateKind = messageTemplateKindFromToken(token)
        )
    }

    private fun messageTemplateKindFromToken(token: String): MessageTemplateKind {
        val upper = token.trim().uppercase()
        return when {
            upper.contains("SCHEDULED_BLOCK") || upper.contains("SCHEDULE_GROUP") ->
                MessageTemplateKind.SCHEDULE
            upper.contains("HOURLY_CAP") ->
                MessageTemplateKind.HOURLY
            upper.contains("DAILY_CAP") ->
                MessageTemplateKind.DAILY
            upper.contains("OPENS_CAP") ->
                MessageTemplateKind.OPENS
            else -> MessageTemplateKind.DEFAULT
        }
    }

    private fun reasonLabelFromToken(token: String): String? {
        val upper = token.trim().uppercase()
        return when {
            upper.contains("SCHEDULED_BLOCK") || upper.contains("SCHEDULE_GROUP") ->
                "Scheduled block"
            upper.contains("HOURLY_CAP") ->
                "Hourly limit reached"
            upper.contains("DAILY_CAP") ->
                "Daily limit reached"
            upper.contains("OPENS_CAP") ->
                "Daily opens limit reached"
            upper.contains(EffectiveBlockReason.STRICT_INSTALL.name) ->
                "Strict schedule block"
            upper.contains(EffectiveBlockReason.ALWAYS_BLOCKED.name) ->
                "Always blocked"
            upper.contains(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name) ->
                "Accessibility required"
            upper.contains(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name) ->
                "Usage access required"
            upper == "BUDGET" || upper.contains("USAGE_BLOCK") ->
                "Usage budget reached"
            else -> null
        }
    }

    private fun priorityFromToken(token: String): Int {
        val upper = token.trim().uppercase()
        return when {
            upper.contains(EffectiveBlockReason.ALWAYS_BLOCKED.name) -> 0
            upper.contains(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name) -> 1
            upper.contains(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name) -> 2
            upper.contains(EffectiveBlockReason.STRICT_INSTALL.name) -> 3
            upper.contains("SCHEDULED_BLOCK") || upper.contains("SCHEDULE_GROUP") -> 4
            upper.contains("HOURLY_CAP") -> 5
            upper.contains("DAILY_CAP") -> 6
            upper.contains("OPENS_CAP") -> 7
            upper == "BUDGET" || upper.contains("USAGE_BLOCK") -> 8
            else -> 100
        }
    }
}

internal data class PackageSuspendAttemptResult(
    val result: PackageSuspendResult,
    val status: PackageSuspendPrototypeStatus
)

internal interface PackageSuspendBackend {
    fun setPackagesSuspended(
        packages: List<String>,
        options: PackageSuspendCallOptions
    ): PackageSuspendResult
}

internal class PackageSuspendCoordinator(
    private val hiddenBackend: PackageSuspendBackend?,
    private val dpmBackend: PackageSuspendBackend
) {
    fun setPackagesSuspended(
        packages: List<String>,
        options: PackageSuspendCallOptions
    ): PackageSuspendAttemptResult {
        val activeHiddenBackend = hiddenBackend
        if (activeHiddenBackend == null) {
            return PackageSuspendAttemptResult(
                result = dpmBackend.setPackagesSuspended(packages, options),
                status = PackageSuspendPrototypeStatus(PackageSuspendBackendStatus.DPM_ONLY)
            )
        }

        return try {
            PackageSuspendAttemptResult(
                result = activeHiddenBackend.setPackagesSuspended(packages, options),
                status = PackageSuspendPrototypeStatus(PackageSuspendBackendStatus.HIDDEN)
            )
        } catch (hiddenFailure: Throwable) {
            PackageSuspendAttemptResult(
                result = dpmBackend.setPackagesSuspended(packages, options),
                status = PackageSuspendPrototypeStatus(
                    backend = PackageSuspendBackendStatus.DPM_FALLBACK,
                    hiddenErrorMessage = hiddenFailure.message ?: hiddenFailure.javaClass.simpleName
                )
            )
        }
    }
}

internal enum class HiddenSuspendMethodVariant {
    SUSPEND_DIALOG_INFO,
    STRING_DIALOG_MESSAGE
}

internal object HiddenSuspendMethodSelector {
    fun select(lastParameterTypeNames: List<String>): HiddenSuspendMethodVariant? {
        return when {
            "android.content.pm.SuspendDialogInfo" in lastParameterTypeNames ->
                HiddenSuspendMethodVariant.SUSPEND_DIALOG_INFO
            String::class.java.name in lastParameterTypeNames ->
                HiddenSuspendMethodVariant.STRING_DIALOG_MESSAGE
            else -> null
        }
    }
}

internal class DpmPackageSuspendBackend(
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) : PackageSuspendBackend {
    override fun setPackagesSuspended(
        packages: List<String>,
        options: PackageSuspendCallOptions
    ): PackageSuspendResult {
        val failed = dpm.setPackagesSuspended(
            adminComponent,
            packages.toTypedArray(),
            options.suspended
        ).toSet()
        return PackageSuspendResult(
            failedPackages = failed,
            errors = emptyList()
        )
    }
}

internal class HiddenPackageSuspendBackend(
    private val packageManager: PackageManager
) : PackageSuspendBackend {
    @Volatile
    private var bootstrapResult: HiddenSuspendBootstrapResult? = null

    override fun setPackagesSuspended(
        packages: List<String>,
        options: PackageSuspendCallOptions
    ): PackageSuspendResult {
        val bootstrap = bootstrapResult ?: synchronized(this) {
            bootstrapResult ?: resolveBootstrap().also { bootstrapResult = it }
        }
        return when (bootstrap) {
            is HiddenSuspendBootstrapResult.Available -> bootstrap.invoker.invoke(packages, options)
            is HiddenSuspendBootstrapResult.Unavailable -> throw IllegalStateException(bootstrap.reason)
        }
    }

    private fun resolveBootstrap(): HiddenSuspendBootstrapResult {
        val method = findSuspendMethod()
            ?: return HiddenSuspendBootstrapResult.Unavailable("Hidden setPackagesSuspended overload not found")
        val variant = HiddenSuspendMethodSelector.select(
            listOf(method.parameterTypes.lastOrNull()?.name.orEmpty())
        ) ?: return HiddenSuspendBootstrapResult.Unavailable("Hidden setPackagesSuspended overload shape unsupported")
        return try {
            when (variant) {
                HiddenSuspendMethodVariant.SUSPEND_DIALOG_INFO -> {
                    val suspendDialogFactory = SuspendDialogFactory()
                    HiddenSuspendBootstrapResult.Available { packages, options ->
                        invokeSuspendMethod(
                                method = method,
                                packages = packages,
                                suspended = options.suspended,
                                dialogPayload = options.dialogMessageTemplate
                                    ?.takeIf { options.suspended }
                                    ?.let { message ->
                                        suspendDialogFactory.create(
                                            title = options.dialogTitle,
                                            message = message
                                        )
                                    }
                        )
                    }
                }
                HiddenSuspendMethodVariant.STRING_DIALOG_MESSAGE -> {
                    HiddenSuspendBootstrapResult.Available { packages, options ->
                        invokeSuspendMethod(
                            method = method,
                            packages = packages,
                            suspended = options.suspended,
                            dialogPayload = options.dialogMessageTemplate?.takeIf { options.suspended }
                        )
                    }
                }
            }
        } catch (throwable: Throwable) {
            HiddenSuspendBootstrapResult.Unavailable(
                "Hidden suspend bootstrap failed: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
        }
    }

    // AGENT-FLAG: Prototype-only non-SDK API entrypoint - do not remove or broaden without review.
    private fun invokeSuspendMethod(
        method: java.lang.reflect.Method,
        packages: List<String>,
        suspended: Boolean,
        dialogPayload: Any?
    ): PackageSuspendResult {
        val failedPackages = method.invoke(
            packageManager,
            packages.toTypedArray(),
            suspended,
            null,
            null,
            dialogPayload
        ).toFailedPackageSet()
        return PackageSuspendResult(
            failedPackages = failedPackages,
            errors = emptyList()
        )
    }

    private fun findSuspendMethod(): java.lang.reflect.Method? {
        return packageManager.javaClass.classHierarchy()
            .flatMap { clazz -> clazz.declaredMethods.asSequence() }
            .filter { method -> method.name == METHOD_SET_PACKAGES_SUSPENDED }
            .filter { method ->
                method.parameterTypes.size == 5 &&
                    method.parameterTypes[0] == Array<String>::class.java &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[2].name == PERSISTABLE_BUNDLE_CLASS_NAME &&
                    method.parameterTypes[3].name == PERSISTABLE_BUNDLE_CLASS_NAME
            }
            .sortedByDescending { method ->
                when (method.parameterTypes.lastOrNull()?.name) {
                    SUSPEND_DIALOG_INFO_CLASS_NAME -> 2
                    String::class.java.name -> 1
                    else -> 0
                }
            }
            .firstOrNull()
            ?.apply { isAccessible = true }
    }

    private class SuspendDialogFactory {
        private val builderConstructor = Class.forName(SUSPEND_DIALOG_BUILDER_CLASS_NAME)
            .getDeclaredConstructor()
            .apply { isAccessible = true }
        private val builderClass = builderConstructor.declaringClass
        private val setMessageMethod = builderClass.declaredMethods
            .firstOrNull { method ->
                method.name == "setMessage" &&
                    method.parameterTypes.size == 1 &&
                    (
                        method.parameterTypes[0] == String::class.java ||
                            method.parameterTypes[0] == CharSequence::class.java
                        )
            }
            ?.apply { isAccessible = true }
            ?: error("SuspendDialogInfo.Builder#setMessage(String/CharSequence) not found")
        private val setTitleMethod = builderClass.declaredMethods
            .firstOrNull { method ->
                method.name == "setTitle" &&
                    method.parameterTypes.size == 1 &&
                    (
                        method.parameterTypes[0] == String::class.java ||
                            method.parameterTypes[0] == CharSequence::class.java
                        )
            }
            ?.apply { isAccessible = true }
            ?: error("SuspendDialogInfo.Builder#setTitle(String/CharSequence) not found")
        private val buildMethod = builderClass.getDeclaredMethod("build").apply { isAccessible = true }

        fun create(title: String?, message: String): Any {
            val builder = builderConstructor.newInstance()
            if (!title.isNullOrBlank()) {
                setTitleMethod.invoke(builder, title)
            }
            setMessageMethod.invoke(builder, message)
            return buildMethod.invoke(builder)
        }
    }

    private sealed interface HiddenSuspendBootstrapResult {
        data class Available(
            val invoker: (packages: List<String>, options: PackageSuspendCallOptions) -> PackageSuspendResult
        ) : HiddenSuspendBootstrapResult

        data class Unavailable(val reason: String) : HiddenSuspendBootstrapResult
    }

    private fun Any?.toFailedPackageSet(): Set<String> {
        val array = this as? Array<*> ?: return emptySet()
        return array.asSequence()
            .filterIsInstance<String>()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    }

    private fun Class<*>.classHierarchy(): Sequence<Class<*>> = generateSequence(this) { it.superclass }

    private companion object {
        private const val METHOD_SET_PACKAGES_SUSPENDED = "setPackagesSuspended"
        private const val PERSISTABLE_BUNDLE_CLASS_NAME = "android.os.PersistableBundle"
        private const val SUSPEND_DIALOG_INFO_CLASS_NAME = "android.content.pm.SuspendDialogInfo"
        private const val SUSPEND_DIALOG_BUILDER_CLASS_NAME = "android.content.pm.SuspendDialogInfo\$Builder"
    }
}
