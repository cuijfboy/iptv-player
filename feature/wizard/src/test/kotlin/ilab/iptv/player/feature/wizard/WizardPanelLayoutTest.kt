package ilab.iptv.player.feature.wizard

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.wizard.BuiltInSourceInfo
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.model.SourceKind
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * NEW-1's proof, without a television.
 *
 * The defect was a **layout** defect — 17 text lines above four buttons in a column that could not
 * scroll — so the two claims the fix has to make are structural, and both are checkable from the files
 * this module ships:
 *
 * 1. **the buttons are not in the scrolling region.** The test walks `activity_wizard.xml`'s DOM and
 *    refuses to let any button be a descendant of a `ScrollView`. That is the rule that makes the
 *    buttons "laid out before the weighted child", which is what keeps them on screen at any panel
 *    height — and it is exactly what `WizardPanels` models;
 * 2. **the 17 built-ins are not on the decision panel.** The step-1 panel's list is gone
 *    (`wizard_source_list` no longer exists) and the list that remains
 *    (`wizard_sources_list`) is inside a `ScrollView` on the 二级页.
 *
 * On top of that it checks the model's arithmetic against the two screens the card names, and the
 * button labels against the 720p width budget, so "the main button is reachable on the first screen"
 * is a number rather than an opinion. No Robolectric and no device: the files are read from the module
 * directory, which is the unit test's working directory.
 */
class WizardPanelLayoutTest {

    private val document: Document = parse(File("src/main/res/layout/activity_wizard.xml"))
    private val strings: Map<String, String> = parseStrings(File("src/main/res/values/strings.xml"))
    private val dimens: Map<String, String> = parseDimens(File("src/main/res/values/dimens.xml"))

    /** Every id under a `ScrollView`: the region a button must never end up in. */
    private val scrolledIds: Set<String> = buildSet {
        document.getElementsByTagName("ScrollView").elements().forEach { scroll -> collectIds(scroll, this) }
    }

    // ---------------------------------------------------------------- the NEW-1 rule

    @Test
    fun `no wizard button lives inside a scrolling region`() {
        WizardPanel.entries.forEach { panel ->
            WizardPanels.actionsOf(panel).forEach { action ->
                val id = idOf(action)
                assertThat(elementById(id)).isNotNull()
                assertThat(scrolledIds).doesNotContain(id)
            }
        }
    }

    @Test
    fun `the built-in list is the thing that scrolls`() {
        // NEW-1: the list moved off the decision panel, so it is the panel's scroll container that
        // carries it — one focusable row per built-in, added in code.
        assertThat(scrolledIds).contains("wizard_sources_list")
    }

    @Test
    fun `选源 shows a summary row and no longer the 17 source lines`() {
        val summary = elementById("wizard_source_summary")
        assertThat(summary).isNotNull()
        // It has to be reachable by the remote, or the list would be unreachable again.
        assertThat(summary!!.android("focusable")).isEqualTo("true")
        assertThat(summary.android("clickable")).isEqualTo("true")
        // The defect's shape: this id was the 17-line TextView on the decision panel.
        assertThat(elementById("wizard_source_list")).isNull()
    }

    @Test
    fun `every panel the model names is in the layout`() {
        listOf(
            "wizard_step_source",
            "wizard_step_update",
            "wizard_step_watch",
            "wizard_step_sources",
        ).forEach { id -> assertThat(elementById(id)).isNotNull() }
    }

    @Test
    fun `every action the model names is a button in the layout`() {
        WizardPanel.entries.forEach { panel ->
            WizardPanels.actionsOf(panel).forEach { action ->
                assertThat(elementById(idOf(action))?.tagName).isEqualTo("Button")
            }
        }
    }

    @Test
    fun `every button sits in its own panel's footer, outside the scroll container`() {
        WizardPanel.entries.forEach { panel ->
            // The panel scrolls…
            assertThat(descendantIds(elementById(panelIdOf(panel))!!).intersect(scrolledIds)).isNotEmpty()

            // …and its buttons live in its footer group, which is a sibling of the weighted body — so
            // they are measured first and stay laid out however tall the scrolling child wants to be.
            val footerIds = descendantIds(elementById(footerIdOf(panel))!!)
            assertThat(footerIds.filter { it.startsWith("wizard_") }.toSet())
                .containsExactlyElementsIn(WizardPanels.actionsOf(panel).map(::idOf))
            WizardPanels.actionsOf(panel).forEach { action ->
                assertThat(scrolledIds).doesNotContain(idOf(action))
            }
        }
    }

    // ---------------------------------------------------------------- the first-screen budget

    @Test
    fun `every panel keeps its main button on the first screen at 1080p and 720p`() {
        listOf(WizardPanels.SCREEN_HEIGHT_720P_DP, WizardPanels.SCREEN_HEIGHT_1080P_DP).forEach { height ->
            WizardPanel.entries.forEach { panel ->
                assertThat(WizardPanels.fitsOnFirstScreen(panel, height)).isTrue()
                // …and the description it pushed down still has somewhere to be read.
                assertThat(WizardPanels.viewportDp(panel, height))
                    .isAtLeast(WizardPanels.MIN_VIEWPORT_DP)
            }
        }
    }

    @Test
    fun `the footer keeps the primary action on a row of its own`() {
        WizardPanel.entries.forEach { panel ->
            assertThat(WizardPanels.footerRows(panel).first())
                .containsExactly(WizardPanels.primaryOf(panel))
        }
    }

    @Test
    fun `the footer rows fit the 720p width even with the longest labels`() {
        WizardPanel.entries.forEach { panel ->
            WizardPanels.footerRows(panel).forEach { row ->
                val textWidth = WizardPanels.buttonWidthDp(row.size, WizardPanels.SCREEN_WIDTH_720P_DP) -
                    BUTTON_H_PADDING_DP
                row.forEach { action ->
                    val label = strings.getValue(labelKeyOf(action))
                    assertThat(textWidthDp(label, BUTTON_TEXT_SP))
                        .isAtMost(textWidth)
                }
            }
        }
    }

    @Test
    fun `the xml dimensions are the ones the model budgets with`() {
        assertThat(dimens["wizard_horizontal_padding"]).isEqualTo("${WizardPanels.HORIZONTAL_PADDING_DP}dp")
        assertThat(dimens["wizard_vertical_padding"]).isEqualTo("${WizardPanels.VERTICAL_PADDING_DP}dp")
        assertThat(dimens["wizard_header_min_height"]).isEqualTo("${WizardPanels.HEADER_MIN_HEIGHT_DP}dp")
        assertThat(dimens["wizard_hint_min_height"]).isEqualTo("${WizardPanels.HINT_MIN_HEIGHT_DP}dp")
        assertThat(dimens["wizard_section_gap"]).isEqualTo("${WizardPanels.SECTION_GAP_DP}dp")
        assertThat(dimens["wizard_button_min_height"]).isEqualTo("${WizardPanels.BUTTON_MIN_HEIGHT_DP}dp")
        assertThat(dimens["wizard_button_gap"]).isEqualTo("${WizardPanels.BUTTON_GAP_DP}dp")
    }

    // ---------------------------------------------------------------- panels and the summary

    @Test
    fun `the 二级页 belongs to 选源 and 开看 renders no panel at all`() {
        assertThat(WizardPanels.of(WizardStep.SOURCE, sourcesOpen = false)).isEqualTo(WizardPanel.SOURCE)
        assertThat(WizardPanels.of(WizardStep.SOURCE, sourcesOpen = true)).isEqualTo(WizardPanel.SOURCES)
        // The list is a 选源 level: any other step draws its own panel even while the flag lingers.
        assertThat(WizardPanels.of(WizardStep.UPDATE, sourcesOpen = true)).isEqualTo(WizardPanel.UPDATE)
        assertThat(WizardPanels.of(WizardStep.FINISHED, sourcesOpen = false)).isNull()
    }

    @Test
    fun `the summary row counts the catalogue and the list keeps its order`() {
        val catalogue = listOf(builtIn("a"), builtIn("b"), builtIn("c"))

        assertThat(WizardSourceSummary.summaryOf(catalogue)).isEqualTo(WizardSourceSummary.Summary(3))
        assertThat(WizardSourceSummary.summaryOf(catalogue).loaded).isTrue()
        // Nothing read yet is not "0 sources enabled" — the row says "reading" instead.
        assertThat(WizardSourceSummary.summaryOf(emptyList()).loaded).isFalse()
        assertThat(WizardSourceSummary.detailRows(catalogue)).containsExactly("内置源a", "内置源b", "内置源c").inOrder()
    }

    // ---------------------------------------------------------------- helpers

    /** The `Button` id each model action is rendered as. Test-local on purpose: it is the expectation. */
    private fun idOf(action: WizardAction): String = when (action) {
        WizardAction.CONTINUE -> "wizard_source_continue"
        WizardAction.IMPORT -> "wizard_source_import"
        WizardAction.SUBSCRIBE -> "wizard_source_subscribe"
        WizardAction.SKIP -> "wizard_source_skip"
        WizardAction.UPDATE_START -> "wizard_update_start"
        WizardAction.UPDATE_CANCEL -> "wizard_update_cancel"
        WizardAction.UPDATE_SKIP -> "wizard_update_skip"
        WizardAction.WATCH_OPEN -> "wizard_watch_open"
        WizardAction.WATCH_IMPORT -> "wizard_watch_import"
        WizardAction.WATCH_SKIP -> "wizard_watch_skip"
        WizardAction.SOURCES_BACK -> "wizard_sources_back"
    }

    /** The string resource each action's label comes from. */
    private fun labelKeyOf(action: WizardAction): String = when (action) {
        WizardAction.CONTINUE -> "wizard_source_continue"
        WizardAction.IMPORT -> "wizard_source_import"
        WizardAction.SUBSCRIBE -> "wizard_source_subscribe"
        WizardAction.SKIP -> "wizard_source_skip"
        WizardAction.UPDATE_START -> "wizard_update_start"
        WizardAction.UPDATE_CANCEL -> "wizard_update_cancel"
        WizardAction.UPDATE_SKIP -> "wizard_update_skip"
        WizardAction.WATCH_OPEN -> "wizard_watch_open"
        WizardAction.WATCH_IMPORT -> "wizard_watch_import"
        WizardAction.WATCH_SKIP -> "wizard_watch_skip"
        WizardAction.SOURCES_BACK -> "wizard_sources_back"
    }

    private fun builtIn(id: String) =
        BuiltInSourceInfo(id = id, label = "内置源$id", kind = SourceKind.M3U)

    private fun elementById(id: String): Element? = find(document.documentElement, id)

    private fun find(element: Element, id: String): Element? {
        if (element.android("id").removePrefix("@+id/") == id) return element
        element.childNodes.elements().forEach { child ->
            find(child, id)?.let { return it }
        }
        return null
    }

    private fun collectIds(element: Element, into: MutableSet<String>) {
        element.childNodes.elements().forEach { child ->
            child.android("id").removePrefix("@+id/").takeIf { it.isNotEmpty() }?.let(into::add)
            collectIds(child, into)
        }
    }

    /** Every id below [element] (its own excluded). */
    private fun descendantIds(element: Element): Set<String> = buildSet { collectIds(element, this) }

    private fun panelIdOf(panel: WizardPanel): String = when (panel) {
        WizardPanel.SOURCE -> "wizard_step_source"
        WizardPanel.UPDATE -> "wizard_step_update"
        WizardPanel.WATCH -> "wizard_step_watch"
        WizardPanel.SOURCES -> "wizard_step_sources"
    }

    private fun footerIdOf(panel: WizardPanel): String = when (panel) {
        WizardPanel.SOURCE -> "wizard_footer_source"
        WizardPanel.UPDATE -> "wizard_footer_update"
        WizardPanel.WATCH -> "wizard_footer_watch"
        WizardPanel.SOURCES -> "wizard_footer_sources"
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun parse(file: File): Document {
        assertThat(file.exists()).isTrue()
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun parseDimens(file: File): Map<String, String> =
        Regex("<dimen\\s+name=\"([^\"]+)\"\\s*>([^<]+)</dimen>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun parseStrings(file: File): Map<String, String> =
        Regex("<string\\s+name=\"([^\"]+)\"\\s*>([^<]*)</string>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    /**
     * The same conservative glyph model `InfoBarLayoutTest` uses: a full-width glyph is 1 em, ASCII
     * about 0.55 em, so a label's width can be checked without a font on the test host.
     */
    private fun textWidthDp(text: String, textSizeSp: Int): Int {
        val em = text.sumOf { if (it.code >= 0x2E80) 1.0 else 0.55 }
        return Math.round(em * textSizeSp).toInt()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** `Button`'s default label size on the platform theme, and its default horizontal padding. */
        const val BUTTON_TEXT_SP = 14
        const val BUTTON_H_PADDING_DP = 32
    }
}
