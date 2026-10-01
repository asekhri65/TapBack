package com.aaravsekhri.dot.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * Release builds have no developer section: the service address, connection
 * checks, request traces and fixture screens live only in the debug source
 * set, so none of their words ship in a release.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ColumnScope.DeveloperSection(onPreviewPlus: (com.aaravsekhri.dot.ui.plus.PlusFixture) -> Unit = {}) = Unit
