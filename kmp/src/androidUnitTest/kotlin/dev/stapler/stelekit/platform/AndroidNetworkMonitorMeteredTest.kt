package dev.stapler.stelekit.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.test.assertEquals

/** `isMetered` follows the active network's metered flag on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidNetworkMonitorMeteredTest {
    private fun networkInfo(type: Int) =
        ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)

    @Test
    fun isMetered_should_FollowActiveNetworkMeteredFlag_When_AndroidReportsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NetworkMonitor.init(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val monitor = NetworkMonitor()

        shadowOf(cm).setActiveNetworkInfo(networkInfo(ConnectivityManager.TYPE_MOBILE))
        assertEquals(true, monitor.isMetered)

        shadowOf(cm).setActiveNetworkInfo(networkInfo(ConnectivityManager.TYPE_WIFI))
        assertEquals(false, monitor.isMetered)
    }
}
