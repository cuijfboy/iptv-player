package ilab.iptv.player.feature.player

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The system-integration bindings of the player (P1-7).
 *
 * Kept apart from [PlayerFailoverModule] on purpose: that module is the frozen fail-over policy of
 * `:core:domain`, this one is platform plumbing (the connectivity listener behind
 * [NetworkRetryGate]). Mixing them would make the policy's wiring look like it needs a `Context`.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerSystemModule {

    @Provides
    @Singleton
    fun provideNetworkAvailability(@ApplicationContext context: Context): NetworkAvailability =
        ConnectivityNetworkAvailability(context)
}
