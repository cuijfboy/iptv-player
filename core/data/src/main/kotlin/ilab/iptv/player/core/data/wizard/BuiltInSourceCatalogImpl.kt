package ilab.iptv.player.core.data.wizard

import ilab.iptv.player.core.domain.wizard.BuiltInSourceCatalog
import ilab.iptv.player.core.domain.wizard.BuiltInSourceInfo
import ilab.iptv.player.core.source.provider.BuiltInSources
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The 17 built-in aggregates of `:core:source`, exposed as the read model the 选源 step shows
 * (docs/04 P2-9 item 2 ①).
 *
 * A one-line adapter on purpose: it is the only place that knows the wizard's view of the catalogue
 * comes from `BuiltInSources.CATALOG`, so the day the catalogue moves the wizard does not have to.
 * `:core:data` may see `:core:source`; `:feature:wizard` may not (docs/02 §3.2 rule 2).
 */
@Singleton
class BuiltInSourceCatalogImpl @Inject constructor() : BuiltInSourceCatalog {

    override fun sources(): List<BuiltInSourceInfo> = BuiltInSources.CATALOG.map {
        BuiltInSourceInfo(id = it.id, label = it.label, kind = it.kind)
    }
}
