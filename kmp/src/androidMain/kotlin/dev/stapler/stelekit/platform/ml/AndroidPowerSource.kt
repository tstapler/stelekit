package dev.stapler.stelekit.platform.ml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dev.stapler.stelekit.asset.pipeline.PowerSource
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Charging state from the sticky battery broadcast plus power-connected/disconnected events.
 * Nothing is registered until [isCharging] is first read, so constructing the platform caps
 * stays free of Context access. The receiver is process-lifetime on the application context.
 */
class AndroidPowerSource(private val contextProvider: () -> Context = { SteleKitContext.context }) : PowerSource {
    override val isCharging: StateFlow<Boolean> by lazy { register() }

    private fun register(): StateFlow<Boolean> {
        val context = contextProvider().applicationContext
        val state = MutableStateFlow(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> state.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> state.value = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            context.registerReceiver(receiver, filter)
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        state.value = plugged != 0
        return state
    }
}
