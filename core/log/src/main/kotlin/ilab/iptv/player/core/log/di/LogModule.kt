package ilab.iptv.player.core.log.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.log.AndroidClock
import ilab.iptv.player.core.log.LogBus
import ilab.iptv.player.core.log.LogSink
import ilab.iptv.player.core.log.LogcatSink
import ilab.iptv.player.core.log.MemoryRingSink
import ilab.iptv.player.core.log.UuidSessionIdFactory
import ilab.iptv.player.core.log.UrlRedactor
import ilab.iptv.player.core.log.UptimeMs
import javax.inject.Singleton

/**
 * Wires the log skeleton (docs/02 §9 extension point E8, docs/03 §2/§5).
 *
 * The sinks are registered as a Hilt set: `LogBus` receives `Set<LogSink>`, so a new outlet (file,
 * crash, metric bridge, …) is added by binding one more `@IntoSet` and nothing else changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object LogModule {

    @Provides
    @Singleton
    fun provideAndroidClock(): AndroidClock = AndroidClock()

    @Provides
    @Singleton
    fun provideClock(clock: AndroidClock): Clock = clock

    @Provides
    @Singleton
    fun provideUptime(clock: AndroidClock): UptimeMs = clock

    @Provides
    @Singleton
    fun provideSessionIdFactory(): SessionIdFactory = UuidSessionIdFactory()

    @Provides
    @Singleton
    fun provideRedactor(): Redactor = UrlRedactor()

    /**
     * Singleton on purpose: the in-app console reads the *same* ring the bus writes to. The
     * `@IntoSet` binding below takes this instance as a parameter, so the set element and the
     * console handle are one object (binding the same class twice would create two rings).
     */
    @Provides
    @Singleton
    fun provideMemoryRingSink(): MemoryRingSink = MemoryRingSink()

    @Provides
    @IntoSet
    fun provideMemoryRingSinkAsSink(sink: MemoryRingSink): LogSink = sink

    @Provides
    @IntoSet
    fun provideLogcatSink(): LogSink = LogcatSink()

    @Provides
    @Singleton
    fun provideLogBus(
        sinks: Set<@JvmSuppressWildcards LogSink>,
        clock: Clock,
        uptime: UptimeMs,
        sessionIds: SessionIdFactory,
        redactor: Redactor,
    ): LogBus = LogBus(
        sinks = sinks,
        clock = clock,
        uptime = uptime,
        sessionIds = sessionIds,
        redactor = redactor,
    )

    @Provides
    @Singleton
    fun provideLogger(bus: LogBus): Logger = bus
}
