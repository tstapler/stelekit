package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.NetworkMonitor

/** Whether the cloud plugin may upload now; there is no "upload anyway" override. */
enum class CloudNetwork {
    Online,
    Offline,

    /** Android reports the active network as metered. Desktop cannot tell and never lands here. */
    Metered,
}

fun cloudNetworkOf(isOnline: Boolean, isMetered: Boolean?): CloudNetwork = when {
    !isOnline -> CloudNetwork.Offline
    isMetered == true -> CloudNetwork.Metered
    else -> CloudNetwork.Online
}

fun NetworkMonitor.cloudNetwork(): CloudNetwork = cloudNetworkOf(isOnline, isMetered)
