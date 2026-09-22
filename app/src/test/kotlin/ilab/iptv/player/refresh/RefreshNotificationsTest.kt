package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * NEW-20260922-003: the refresh channel was defined and never created, so the first
 * `setForeground` killed the process. What can break here is the *decision* (create when missing,
 * report whether it is now there), so that half runs on a hand-written sink — `NotificationManager`
 * itself cannot be built in a JVM test, and everything it does here is two binder calls.
 */
class RefreshNotificationsTest {

    @Test
    fun `creates the channel when it is missing and reports it usable`() {
        val sink = FakeChannelSink()

        val usable = RefreshNotifications.ensureChannel(sink, NAME, DESCRIPTION)

        assertThat(usable).isTrue()
        assertThat(sink.created).hasSize(1)
        assertThat(sink.created.single().id).isEqualTo(RefreshNotifications.CHANNEL_ID)
        assertThat(sink.created.single().name).isEqualTo(NAME)
        assertThat(sink.created.single().description).isEqualTo(DESCRIPTION)
    }

    @Test
    fun `leaves an existing channel alone`() {
        val sink = FakeChannelSink(exists = true)

        val usable = RefreshNotifications.ensureChannel(sink, NAME, DESCRIPTION)

        assertThat(usable).isTrue()
        assertThat(sink.created).isEmpty()
    }

    /**
     * The degradation path: a platform that accepts the call but does not end up with the channel
     * must make the worker skip `setForeground`, not try it and die.
     */
    @Test
    fun `reports not usable when the channel does not appear`() {
        val sink = FakeChannelSink(acceptsCreation = false)

        val usable = RefreshNotifications.ensureChannel(sink, NAME, DESCRIPTION)

        assertThat(usable).isFalse()
        assertThat(sink.created).hasSize(1)
    }

    /** A refusing OEM build throws out of `createNotificationChannel`; that is an answer, not a crash. */
    @Test
    fun `reports not usable when creation throws`() {
        val sink = FakeChannelSink(throwsOnCreate = true)

        val usable = RefreshNotifications.ensureChannel(sink, NAME, DESCRIPTION)

        assertThat(usable).isFalse()
    }

    private class FakeChannelSink(
        private var exists: Boolean = false,
        private val acceptsCreation: Boolean = true,
        private val throwsOnCreate: Boolean = false,
    ) : RefreshNotifications.ChannelSink {

        data class Created(val id: String, val name: String, val description: String)

        val created = mutableListOf<Created>()

        override fun channelExists(): Boolean = exists

        override fun createChannel(id: String, name: String, description: String) {
            created += Created(id, name, description)
            if (throwsOnCreate) throw IllegalStateException("channel refused")
            exists = acceptsCreation
        }
    }

    private companion object {
        const val NAME = "刷新通知"
        const val DESCRIPTION = "后台刷新频道源时的进度"
    }
}
