package ilab.iptv.player.core.domain.wizard

/**
 * The "have we already shown the first-run wizard?" memory (docs/04 P2-9 item 1).
 *
 * WHY IT IS A PORT AND NOT A `SharedPreferences` CALL: the answer decides which activity the
 * launcher opens, and that decision is the one thing in this card worth pinning with a table of
 * tests. [FirstRunGate] is pure; this interface is the two-line seam the screen and the router use.
 *
 * **The read is synchronous on purpose.** It runs on the cold-start path the §6.1/`S6` budget is
 * measured against, and it is one boolean. DataStore would make it a suspend round trip and force a
 * loading frame before the app knows where to go, for a value that is a single flag.
 */
interface FirstRunStore {

    /** True once the wizard has been completed (or ruled out for an existing install). */
    fun isCompleted(): Boolean

    /**
     * Records completion. Idempotent: writing it twice is the same as writing it once.
     *
     * It also **drops the saved step** ([saveProgress]): a finished wizard has nothing to resume, so
     * the completion flag and the progress are never both set.
     */
    fun markCompleted()

    /**
     * The step the user left the wizard on, or null when there is no run to resume.
     *
     * WHY THIS EXISTS (NEW-1): the completion flag is only written by "walked all three steps"
     * (docs/04 P2-9 item 1), so an abandoned run comes back on the next launch. Coming back to step 1
     * every time was harmless while the wizard was walkable; with a wizard that can be abandoned on
     * any step it would make the user re-decide 选源 and re-watch a refresh they already started.
     * Remembering the step turns "the wizard comes back" into "the wizard comes back *where it was*",
     * which is what removes the every-launch loop NEW-1 was made of.
     */
    fun savedProgress(): WizardState?

    /** Remembers [state] for the next launch. [WizardStep.FINISHED] is not a resumable step. */
    fun saveProgress(state: WizardState)
}

/**
 * What the launcher knows before it decides where to go (docs/04 P2-9 item 1).
 *
 * [importedPlaylist] and [subscriptionCount] are the two pieces of evidence that say "this install
 * has been used before". The **channel count is deliberately not part of it**: a fresh install
 * already carries the synthetic `p1-2-fixture` catalog (docs/02 §5.1), so "there are channels" is
 * true for a brand-new user too and would silently disable the wizard for everybody.
 */
data class FirstRunFacts(
    /** The setting this card adds: "wizard already handled". */
    val wizardCompleted: Boolean,
    /** A remembered local import (`last-import.json`) exists. */
    val importedPlaylist: Boolean,
    /** Rows in the `source` table — the user's own subscriptions (built-ins are not in it). */
    val subscriptionCount: Int,
) {
    /** Upgrade evidence: the user configured something the wizard would otherwise ask them for. */
    val hasPriorUse: Boolean get() = importedPlaylist || subscriptionCount > 0
}

/** Where the launcher sends the user. */
enum class FirstRunRoute {
    /** First run with nothing configured: show the wizard. */
    WIZARD,

    /** A returning user (the flag is set): go straight to the channel list. */
    BROWSE,

    /**
     * The **upgrade** case: the flag is absent, but the install already has a playlist or
     * subscriptions — the build that wrote them predates this setting. Go to the list *and* record
     * completion, so a user who has been watching TV for weeks is not asked to pick a source as if
     * they had never started the app.
     */
    BROWSE_UPGRADE,
}

/**
 * The first-run decision as a pure function (docs/04 P2-9 item 1: "已完成的用户不再打扰（含升级场景）").
 *
 * | flag | prior use | route |
 * |---|---|---|
 * | set | – | `BROWSE` |
 * | unset | yes | `BROWSE_UPGRADE` (and write the flag) |
 * | unset | no | `WIZARD` |
 *
 * A later launch of a `WIZARD` user who never finished still gets the wizard: the flag is written
 * only on completion, so an abandoned run is not mistaken for a finished one.
 */
object FirstRunGate {

    fun decide(facts: FirstRunFacts): FirstRunRoute = when {
        facts.wizardCompleted -> FirstRunRoute.BROWSE
        facts.hasPriorUse -> FirstRunRoute.BROWSE_UPGRADE
        else -> FirstRunRoute.WIZARD
    }

    /** True when [route] must also write the completion flag (the upgrade path only). */
    fun recordsCompletion(route: FirstRunRoute): Boolean = route == FirstRunRoute.BROWSE_UPGRADE
}
