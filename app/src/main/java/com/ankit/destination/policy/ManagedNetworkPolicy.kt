package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.ManagedNetworkModeSetting
import com.ankit.destination.data.PrivateDnsModeSetting

sealed interface ManagedNetworkPolicy {
    data class ForcedVpn(
        val packageName: String,
        val lockdown: Boolean
    ) : ManagedNetworkPolicy

    data class ForcedPrivateDns(
        val hostname: String
    ) : ManagedNetworkPolicy

    object Unmanaged : ManagedNetworkPolicy
}

internal fun GlobalControls.toManagedNetworkPolicy(
    defaultVpnPackage: String
): ManagedNetworkPolicy {
    return when (managedNetworkModeSetting()) {
        ManagedNetworkModeSetting.FORCED_VPN -> {
            val vpnPackage = managedVpnPackage?.trim().orEmpty().ifBlank { defaultVpnPackage }
            ManagedNetworkPolicy.ForcedVpn(
                packageName = vpnPackage,
                lockdown = managedVpnLockdown
            )
        }

        ManagedNetworkModeSetting.FORCED_PRIVATE_DNS -> {
            val hostname = privateDnsHost?.trim().orEmpty()
            if (hostname.isBlank()) {
                ManagedNetworkPolicy.Unmanaged
            } else {
                ManagedNetworkPolicy.ForcedPrivateDns(hostname)
            }
        }

        ManagedNetworkModeSetting.UNMANAGED -> ManagedNetworkPolicy.Unmanaged
    }
}

internal fun GlobalControls.managedNetworkModeSetting(): ManagedNetworkModeSetting {
    return runCatching {
        ManagedNetworkModeSetting.valueOf(managedNetworkMode)
    }.getOrElse {
        if (lockVpnDns) {
            ManagedNetworkModeSetting.FORCED_VPN
        } else if (
            runCatching { PrivateDnsModeSetting.valueOf(privateDnsMode) }.getOrNull() ==
            PrivateDnsModeSetting.PROVIDER_HOSTNAME &&
            !privateDnsHost.isNullOrBlank()
        ) {
            ManagedNetworkModeSetting.FORCED_PRIVATE_DNS
        } else {
            ManagedNetworkModeSetting.UNMANAGED
        }
    }
}

internal fun ManagedNetworkPolicy.label(): String {
    return when (this) {
        is ManagedNetworkPolicy.ForcedVpn -> "Forced VPN"
        is ManagedNetworkPolicy.ForcedPrivateDns -> "Forced Private DNS"
        ManagedNetworkPolicy.Unmanaged -> "Unmanaged"
    }
}
