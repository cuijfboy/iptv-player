package ilab.iptv.player.core.log.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.log.AndroidDispatcherProvider
import javax.inject.Singleton

/**
 * Binds the process-wide [DispatcherProvider] (docs/02 §4.1/§10, §4.5 C6).
 *
 * **Why here and not in a module of its own:** `:core:log` is the lowest Android module and already
 * owns the platform binding of every `:core:common` interface it can satisfy — `Clock`,
 * `SessionIdFactory`, `Redactor`, `Logger` (`LogModule`). A dispatcher provider is the same kind of
 * object: a platform default for a frozen pure-Kotlin interface, needed by `:core:data` (refresh,
 * catalog, import) and `:core:player` alike, and by nothing that `:core:log` does not already sit
 * under. Creating a `:core:async` module would mean a new entry in the §3.2 matrix — a docs/02
 * change this round must not make.
 *
 * `@Singleton` on purpose: `single`/`engine` each own a thread, so two instances would mean two
 * threads pretending to be "the one".
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = AndroidDispatcherProvider()
}
