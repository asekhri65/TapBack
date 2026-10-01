package com.aaravsekhri.dot.ui.app

import androidx.annotation.StringRes
import com.aaravsekhri.dot.R

/** Dot's two destinations. Order here is the order they appear in the bar. */
enum class DotTab(@param:StringRes val labelRes: Int) {
    Ask(R.string.tab_ask),
    Learn(R.string.tab_learn),
}
