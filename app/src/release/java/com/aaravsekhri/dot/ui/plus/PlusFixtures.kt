package com.aaravsekhri.dot.ui.plus

import androidx.compose.runtime.Composable

/** Release builds have no fixture screens: there is nothing to choose, and nothing is drawn. */
enum class PlusFixture(val label: String)

@Suppress("UNUSED_PARAMETER")
@Composable
fun PlusFixtureScreen(fixture: PlusFixture, onClose: () -> Unit) = Unit
