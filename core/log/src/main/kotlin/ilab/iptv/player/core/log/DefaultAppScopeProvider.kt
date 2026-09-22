package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.AppScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process' one application-wide [CoroutineScope] (docs/02 §4.5 C6: "只用注入的
 * `AppScopeProvider.appScope`；禁止 `GlobalScope`").
 *
 * Like [AndroidDispatcherProvider], it is the platform default of a `:core:common` interface that had
 * no binding: P3-6's cold-start trigger is the first caller that has to launch work from
 * `Application.onCreate`, where there is no lifecycle and no caller-owned scope to borrow.
 *
 * `SupervisorJob` because one failing startup job (a dead network, a locked database) must not cancel
 * the others — the app still has to start. The scope is process-lifetime and is never cancelled: it
 * belongs to the process, not to a session.
 */
class DefaultAppScopeProvider : AppScopeProvider {

    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
