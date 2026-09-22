package ilab.iptv.player.wizard

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.domain.wizard.WizardUpdatePort
import javax.inject.Singleton

/**
 * P2-9 wiring for the 更新 step.
 *
 * It lives in `:app` for the same reason P2-5's `RefreshModule` does: the port's implementation is a
 * *platform* concern (WorkManager + the P2-5 scheduler) and `:app` is the only module that may see
 * both a feature's port and the refresh plumbing (§3.2: `:feature:*` must not declare WorkManager's
 * scheduler, and the wizard must not re-implement the pipeline).
 */
@Module
@InstallIn(SingletonComponent::class)
object WizardModule {

    @Provides
    @Singleton
    fun provideWizardUpdatePort(impl: WorkManagerWizardUpdate): WizardUpdatePort = impl
}
