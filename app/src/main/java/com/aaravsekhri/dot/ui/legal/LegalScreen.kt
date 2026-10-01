package com.aaravsekhri.dot.ui.legal

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.design.Stage
import com.aaravsekhri.dot.ui.design.StageBack
import com.aaravsekhri.dot.ui.design.StageBullet
import com.aaravsekhri.dot.ui.design.StageGap
import com.aaravsekhri.dot.ui.design.StageHeadline
import com.aaravsekhri.dot.ui.design.StageScaffold
import androidx.compose.material3.Text

/** TapBack's own documents, carried in the app so they are there offline and never a guessed address. */
enum class LegalDocument(@StringRes val title: Int, @RawRes val text: Int) {
    Terms(R.string.legal_terms_title, R.raw.terms_of_service),
    Privacy(R.string.legal_privacy_title, R.raw.privacy_policy),
}

/**
 * A document as plain reading: a date line, then sections. The source is a
 * small text format — "## " starts a section, "- " a list item, anything
 * else a paragraph — so a translation is a copy of the file, not code.
 */
@Composable
fun LegalScreen(document: LegalDocument, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val blocks = remember(document, resources) {
        resources.openRawResource(document.text).bufferedReader().use { parse(it.readText()) }
    }
    StageScaffold(
        header = { StageBack(stringResource(R.string.legal_back), onBack) },
        footer = null,
    ) {
        StageHeadline(stringResource(document.title))
        StageGap(8.dp)
        blocks.forEach { block ->
            when (block) {
                is Block.Dated -> Text(block.text, style = Stage.Small, color = Stage.Secondary)
                is Block.Heading -> {
                    StageGap(20.dp)
                    Text(block.text, style = Stage.CardTitle, color = Stage.Charcoal, modifier = Modifier.semantics { heading() })
                    StageGap(4.dp)
                }
                is Block.Paragraph -> {
                    StageGap(8.dp)
                    Text(block.text, style = Stage.Supporting, color = Stage.Charcoal)
                }
                is Block.Items -> {
                    StageGap(8.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { block.items.forEach { StageBullet(it) } }
                }
            }
        }
        StageGap(24.dp)
        Spacer(Modifier)
    }
}

internal sealed interface Block {
    data class Dated(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Items(val items: List<String>) : Block
}

internal fun parse(source: String): List<Block> {
    val out = mutableListOf<Block>()
    var items = mutableListOf<String>()
    fun flush() {
        if (items.isNotEmpty()) out += Block.Items(items)
        items = mutableListOf()
    }
    source.lines().map(String::trim).filter(String::isNotEmpty).forEachIndexed { i, line ->
        when {
            line.startsWith("## ") -> { flush(); out += Block.Heading(line.removePrefix("## ")) }
            line.startsWith("- ") -> items += line.removePrefix("- ")
            i == 0 -> out += Block.Dated(line)
            else -> { flush(); out += Block.Paragraph(line) }
        }
    }
    flush()
    return out
}
