package ilab.iptv.player.core.log.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.log.FileSink
import ilab.iptv.player.core.log.LogSink
import ilab.iptv.player.core.log.file.DayKeyFormat
import ilab.iptv.player.core.log.file.JavaIoLogFileSystem
import ilab.iptv.player.core.log.file.LogFilePolicy
import ilab.iptv.player.core.log.file.LogFileStorage
import ilab.iptv.player.core.log.file.LogFileSystem
import ilab.iptv.player.core.log.file.WallClockDayKeyFormat
import javax.inject.Singleton

/**
 * Wires the on-disk log outlet (docs/03 §5 `FileSink`, §6, §14) into the same `@IntoSet` extension
 * point the logcat and memory-ring sinks use (docs/02 §9 E8) — `LogBus` itself is untouched.
 *
 * Rooted in `getExternalFilesDir`, i.e. `/sdcard/Android/data/ilab.iptv.player/files/logs/`: app
 * private storage, so no runtime permission and nothing for the user to grant (ADR-001 spirit).
 */
@Module
@InstallIn(SingletonComponent::class)
object LogFileModule {

    @Provides
    @Singleton
    fun provideLogFileSystem(): LogFileSystem = JavaIoLogFileSystem()

    @Provides
    @Singleton
    fun provideDayKeyFormat(): DayKeyFormat = WallClockDayKeyFormat()

    /**
     * Singleton on purpose: the diagnostics page reads the *same* sink the bus writes to (same trick
     * as `MemoryRingSink`), and one sink must own the active file.
     *
     * The policy is built here rather than injected because a device without an external files
     * directory has no directory to name — that case is a `null` policy (sink reports itself
     * unhealthy), not a Dagger null binding.
     */
    @Provides
    @Singleton
    fun provideFileSink(
        @ApplicationContext context: Context,
        fs: LogFileSystem,
        dayKey: DayKeyFormat,
        clock: Clock,
        redactor: Redactor,
    ): FileSink = FileSink(
        policy = LogFileStorage.logDirectory(context)?.let { LogFilePolicy(directoryPath = it) },
        dayKey = dayKey,
        clock = clock,
        fs = fs,
        redactor = redactor,
    )

    @Provides
    @IntoSet
    fun provideFileSinkAsSink(sink: FileSink): LogSink = sink
}
