package ilab.iptv.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.domain.repository.SourceRepository
import ilab.iptv.player.core.domain.wizard.FirstRunFacts
import ilab.iptv.player.core.domain.wizard.FirstRunGate
import ilab.iptv.player.core.domain.wizard.FirstRunRoute
import ilab.iptv.player.core.domain.wizard.FirstRunStore
import ilab.iptv.player.core.ui.browse.BrowseContract
import ilab.iptv.player.core.ui.wizard.WizardContract
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The launcher **and the first-run router** (docs/04 P2-9 item 1; docs/02 §8.1's
 * `WizardActivity（仅首次）` sitting in front of `BrowseActivity`).
 *
 * It used to be the P0 placeholder screen with two debug buttons. P2-9 replaces that with the
 * decision the acceptance line is about: a fresh install must land in the wizard, and a returning
 * user must land in the channel list. The two debug entries were not dropped from the app — the log
 * / device console is still reached from 设置 → 诊断 → 日志与设备信息 — they were dropped from a
 * screen that now exists only for the milliseconds the decision takes.
 *
 * THE DECISION ([FirstRunGate]) ANSWERS THREE CASES, and only one of them reads anything but the
 * flag:
 * 1. flag set → channel list (the common case; no repository is touched at all);
 * 2. flag absent but a playlist was imported before, or subscriptions exist → channel list **and
 *    record completion** — the upgrade case: this build's flag did not exist when that user set the
 *    app up, and asking them to "pick a source" would be asking them to redo finished work;
 * 3. flag absent and nothing configured → the wizard.
 *
 * Both destinations are started with `CLEAR_TASK or NEW_TASK` (docs/02 §8.1's 向导退栈 contract):
 * BACK from whatever opens next must not come back to a router that would route again.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var firstRun: FirstRunStore

    /** Read only on the first-run path: the two pieces of "this install has been used" evidence. */
    @Inject
    lateinit var sources: SourceRepository

    @Inject
    lateinit var importPort: PlaylistImportPort

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lifecycleScope.launch { route() }
    }

    override fun onStop() {
        super.onStop()
        // docs/03 §5 flush contract: going to the background forces the on-disk log's tail out
        // (`FileSink.flush` → fsync), so an exit or a power cut cannot cost the last events.
        logger.flush()
    }

    /**
     * The route itself. The flag is a synchronous read on purpose (one boolean, no loading frame);
     * the two evidence reads only happen when the flag says "first run", so a returning user's
     * cold start pays nothing for the upgrade check.
     */
    private suspend fun route() {
        if (firstRun.isCompleted()) {
            open(BrowseContract.intent(this), FirstRunRoute.BROWSE, imported = false, subscriptions = 0)
            return
        }

        // A read that fails is "no evidence", never "crash the launcher": the worst case is that an
        // existing user sees the wizard once, and the wizard does not delete anything.
        val imported = runCatching { importPort.lastImported() }.getOrNull() != null
        val subscriptions = runCatching { sources.all().size }.getOrDefault(0)

        val facts = FirstRunFacts(
            wizardCompleted = false,
            importedPlaylist = imported,
            subscriptionCount = subscriptions,
        )
        val route = FirstRunGate.decide(facts)
        if (FirstRunGate.recordsCompletion(route)) {
            // The upgrade path: write the flag so the next launch takes case 1 and never reads again.
            firstRun.markCompleted()
        }
        val destination = when (route) {
            FirstRunRoute.WIZARD -> WizardContract.intent(this)
            FirstRunRoute.BROWSE, FirstRunRoute.BROWSE_UPGRADE -> BrowseContract.intent(this)
        }
        open(destination, route, imported, subscriptions)
    }

    private fun open(intent: Intent, route: FirstRunRoute, imported: Boolean, subscriptions: Int) {
        // DEBUG level per docs/03 §3.3; visible on the console after switching to DEBUG. `route` is
        // what makes "why did the wizard not show?" answerable from the log instead of by guessing.
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "launch route decided",
            fields = mapOf(
                "screen" to "Main",
                "route" to route.name,
                "imported" to imported,
                "subscriptions" to subscriptions,
            ),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
