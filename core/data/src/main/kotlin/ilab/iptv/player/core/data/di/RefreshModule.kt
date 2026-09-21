package ilab.iptv.player.core.data.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.domain.scoring.DefaultScorer
import ilab.iptv.player.core.domain.scoring.Scorer
import ilab.iptv.player.core.domain.selection.DefaultStreamSelector
import ilab.iptv.player.core.domain.selection.StreamSelector
import ilab.iptv.player.core.model.DeviceProfile
import javax.inject.Singleton

/**
 * Wiring for the P2-4b back half: the two pure policies (docs/02 §4.3 `Scorer` /
 * `StreamSelector`) and the [DeviceProfile] the scoring's 设备兼容 dimension reads (docs/02 §6.1).
 *
 * The policies are plain objects, so "which rules score a stream" is decided here, in one place,
 * instead of inside the pipeline (docs/02 §9 E6).
 *
 * **DeviceProfile note (报 arch 的开放项)**: `:core:player` has a private `deviceProfile(context)`
 * helper with the same body (docs/02 §7.4). `:core:data` may not depend on `:core:player` (§3.2
 * rule 3), so the profile is derived twice for now. The convergence — one shared provider the app and
 * the player both read — is an arch call; the values here must stay identical to that helper's.
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshModule {

    @Provides
    @Singleton
    fun provideScorer(): Scorer = DefaultScorer()

    @Provides
    @Singleton
    fun provideStreamSelector(): StreamSelector = DefaultStreamSelector()

    /**
     * What the device can decode, probed — never hard-coded per model (docs/02 §4.7). Only the fields
     * the §6.1 device-compatibility rule reads today are filled; a real audio-passthrough probe lands
     * with the S1/S2 follow-up.
     */
    @Provides
    @Singleton
    fun provideDeviceProfile(@ApplicationContext context: Context): DeviceProfile {
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        val profile = DeviceProfile(
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            ramMb = (memoryInfo.totalMem / (1024 * 1024)).toInt(),
            audioPassthrough = emptySet(),
            maxWidth = 1_920,
            maxHeight = 1_080,
            maxFrameRate = 60f,
        )
        return profile
    }
}
