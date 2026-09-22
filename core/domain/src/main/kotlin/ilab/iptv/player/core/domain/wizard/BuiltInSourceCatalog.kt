package ilab.iptv.player.core.domain.wizard

import ilab.iptv.player.core.model.SourceKind

/**
 * One built-in aggregate source, as the wizard's 选源 step shows it (docs/04 P2-9 item 2 ①:
 * "内置聚合源默认全开").
 *
 * Only the list *address* is built in (docs/02 §12: 不内置源地址，仅内置源清单地址) and the wizard
 * never shows the URL, so this read model carries the label and the dialect and nothing else.
 */
data class BuiltInSourceInfo(
    val id: String,
    val label: String,
    val kind: SourceKind,
)

/**
 * The built-in aggregates, read-only.
 *
 * WHY THIS PORT EXISTS AT ALL: the catalogue lives in `:core:source`'s `BuiltInSources`, and
 * docs/02 §3.2 rule 2 forbids a feature module from declaring `:core:source`. The 选源 step has to
 * name the sources the user is about to refresh, so the catalogue is exposed one layer down through
 * this interface — the same shape `PlaylistImportPort` and `SourceManagementPort` already use.
 *
 * **Every built-in is on.** Until a per-source off switch exists there is nothing to toggle, so the
 * wizard lists them as the sources that *will* be fetched rather than offering checkboxes that would
 * not be written anywhere (a switch that does nothing is worse than no switch). Reported to god as
 * the docs/02 §12 line "可在设置中关闭" that is still unimplemented.
 */
interface BuiltInSourceCatalog {

    /** The catalogue, in the order the providers are declared. */
    fun sources(): List<BuiltInSourceInfo>
}
