package ilab.iptv.player.core.domain.playback

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import org.junit.Test

/*
 * Audits the docs/02 §4.6 table against the code: one row per `FailureClass`, the doc's `retryable`
 * column, the doc's action recipe, and event codes that really exist in `EventCodes` (docs/03 §3.3).
 */
class FailureClassPolicyTest {

    @Test
    fun `every failure class has exactly one row`() {
        val plans = FailurePolicies.all()
        assertThat(plans).hasSize(FailureClass.entries.size)
        assertThat(plans.map { it.failure }).containsExactlyElementsIn(FailureClass.entries)
        FailureClass.entries.forEach { failureClass ->
            assertThat(FailurePolicies.plan(failureClass).failure).isEqualTo(failureClass)
        }
    }

    @Test
    fun `every row logs a registered event code`() {
        FailurePolicies.all().forEach { plan ->
            assertThat(EventCodes.isRegistered(plan.logCode)).isTrue()
        }
    }

    @Test
    fun `failover rows use PLAY_FAILOVER, non-switching rows use their own code`() {
        val switching = FailurePolicies.all().filter { it.emitsFailoverEvent }.map { it.failure }
        assertThat(switching.map { FailurePolicies.plan(it).logCode }.toSet())
            .containsExactly(EventCodes.PLAY_FAILOVER)

        assertThat(FailurePolicies.plan(FailureClass.STORAGE).logCode).isEqualTo(EventCodes.DB_FAIL)
        assertThat(FailurePolicies.plan(FailureClass.PERMISSION).logCode)
            .isEqualTo(EventCodes.PLAY_PREPARE_FAIL)
        assertThat(FailurePolicies.plan(FailureClass.CANCELLED).logCode).isEqualTo(EventCodes.PLAY_END)
    }

    @Test
    fun `the retryable column matches the AppError factories`() {
        val expected = mapOf(
            FailureClass.HTTP_CLIENT to false,
            FailureClass.HTTP_SERVER to true,
            FailureClass.TIMEOUT to true,
            FailureClass.NET_UNREACHABLE to true,
            FailureClass.TLS to false,
            FailureClass.PARSE to false,
            FailureClass.DECODE_UNSUPPORTED to false,
            FailureClass.DECODE_CORRUPT to true,
            FailureClass.EMPTY_MEDIA to false,
            FailureClass.PLAYLIST_GONE to false,
            FailureClass.NO_CAPABILITY to false,
            FailureClass.STORAGE to true,
            FailureClass.PERMISSION to false,
            FailureClass.CANCELLED to false,
            FailureClass.UNKNOWN to false,
        )
        expected.forEach { (failureClass, retryable) ->
            assertThat(FailurePolicies.plan(failureClass).retryable).isEqualTo(retryable)
            assertThat(failure(failureClass).retryable).isEqualTo(retryable)
        }
    }

    @Test
    fun `the recipes match the doc rows`() {
        assertThat(FailurePolicies.plan(FailureClass.HTTP_CLIENT).steps)
            .containsExactly(FailoverStep.SWITCH_NEXT)
        assertThat(FailurePolicies.plan(FailureClass.HTTP_CLIENT).demotion)
            .isEqualTo(StreamDemotion.PERMANENT)

        assertThat(FailurePolicies.plan(FailureClass.HTTP_SERVER).steps)
            .containsExactly(FailoverStep.BACKOFF, FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT)
        assertThat(FailurePolicies.plan(FailureClass.TIMEOUT).steps)
            .containsExactly(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT)
        assertThat(FailurePolicies.plan(FailureClass.DECODE_CORRUPT).steps)
            .containsExactly(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT)
        assertThat(FailurePolicies.plan(FailureClass.PLAYLIST_GONE).steps)
            .containsExactly(FailoverStep.RESOLVE_FRESH, FailoverStep.GIVE_UP)
        assertThat(FailurePolicies.plan(FailureClass.NO_CAPABILITY).steps)
            .containsExactly(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT)

        listOf(
            FailureClass.NET_UNREACHABLE,
            FailureClass.TLS,
            FailureClass.PARSE,
            FailureClass.DECODE_UNSUPPORTED,
            FailureClass.EMPTY_MEDIA,
            FailureClass.UNKNOWN,
        ).forEach { failureClass ->
            assertThat(FailurePolicies.plan(failureClass).steps).containsExactly(FailoverStep.SWITCH_NEXT)
        }
    }

    @Test
    fun `only the doc's two rows ask for a special demotion`() {
        assertThat(FailurePolicies.plan(FailureClass.HTTP_CLIENT).demotion)
            .isEqualTo(StreamDemotion.PERMANENT)
        assertThat(FailurePolicies.plan(FailureClass.DECODE_UNSUPPORTED).demoteCodecCombination).isTrue()
        assertThat(FailurePolicies.plan(FailureClass.NO_CAPABILITY).retryWithoutPassthrough).isTrue()

        val plain = FailurePolicies.all()
            .filter { it.demotion != StreamDemotion.NONE }
            .map { it.failure }
        assertThat(plain).containsExactly(FailureClass.HTTP_CLIENT)
    }

    @Test
    fun `storage permission and cancellation never fail over`() {
        listOf(FailureClass.STORAGE, FailureClass.PERMISSION, FailureClass.CANCELLED).forEach { failureClass ->
            val plan = FailurePolicies.plan(failureClass)
            assertThat(plan.emitsFailoverEvent).isFalse()
            assertThat(plan.steps).isEmpty()
            assertThat(plan.demotion).isEqualTo(StreamDemotion.NONE)
            assertThat(FailurePolicies.emitsFailoverEvent(failureClass)).isFalse()
        }
        assertThat(FailurePolicies.plan(FailureClass.STORAGE).userMessage).isNotNull()
        assertThat(FailurePolicies.plan(FailureClass.PERMISSION).userMessage).isNotNull()
        assertThat(FailurePolicies.plan(FailureClass.CANCELLED).userMessage).isNull()
    }

    @Test
    fun `the no-backup path carries a user message`() {
        // docs/01 F4: 无备胎组 ends with 明确提示"该频道暂时不可用".
        assertThat(FailurePolicies.plan(FailureClass.PLAYLIST_GONE).userMessage)
            .isEqualTo("该频道暂时不可用")
        val switchingRows = FailurePolicies.all().filter { it.emitsFailoverEvent && it.userMessage != null }
        assertThat(switchingRows.map { it.failure }).containsExactly(FailureClass.PLAYLIST_GONE)
    }

    @Test
    fun `plans are stable objects, not rebuilt per call`() {
        assertThat(FailurePolicies.plan(FailureClass.TIMEOUT))
            .isSameInstanceAs(FailurePolicies.plan(FailureClass.TIMEOUT))
    }
}
