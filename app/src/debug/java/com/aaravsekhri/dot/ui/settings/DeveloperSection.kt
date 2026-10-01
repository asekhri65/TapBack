package com.aaravsekhri.dot.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.BuildConfig
import com.aaravsekhri.dot.guide.ai.DotServiceHttp
import com.aaravsekhri.dot.guide.ai.ServiceTrace
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.theme.DotColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * For developers only, and only in a debug build: where this build asks for
 * guidance, whether it can reach it right now, and what recent requests did.
 *
 * Kept here, out of guidance cards: a person using TapBack is told that
 * guidance isn't answering and that their place is saved; the address, the
 * exception and the correlation id are for whoever runs the service.
 */
@Composable
fun ColumnScope.DeveloperSection(onPreviewPlus: (com.aaravsekhri.dot.ui.plus.PlusFixture) -> Unit = {}) {
    if (!BuildConfig.DEBUG) return
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var traces by remember { mutableStateOf(ServiceTrace.recent()) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

    Body("Service: ${BuildConfig.DOT_BACKEND_URL.ifBlank { "(none configured)" }}")
    Spacer(Modifier.height(10.dp))
    SecondaryButton(
        text = "Check connection",
        onClick = {
            result = "Checking…"
            scope.launch {
                val url = BuildConfig.DOT_BACKEND_URL
                val started = System.currentTimeMillis()
                result = if (url.isBlank()) {
                    "No service address in this build (dot.backend.url in local.properties)."
                } else {
                    withContext(Dispatchers.IO) {
                        when (val r = DotServiceHttp.exchange("$url/healthz", "GET", emptyMap(), null, 5_000)) {
                            is DotServiceHttp.Result.Answered -> {
                                val ms = System.currentTimeMillis() - started
                                val provider = runCatching { JSONObject(r.body).optString("provider") }.getOrDefault("?")
                                when {
                                    r.code != 200 -> "Reached it, but it answered HTTP ${r.code} in $ms ms."
                                    provider == "stub" -> "Reached it in $ms ms, but it is the STUB service, not the real model."
                                    provider.isBlank() || provider == "null" -> "Reached it in $ms ms, but no model provider is configured."
                                    else -> "Reachable in $ms ms · provider $provider."
                                }
                            }
                            is DotServiceHttp.Result.Failed ->
                                "Not reachable: ${r.error.javaClass.simpleName}. On the computer, run backend/dev.sh status (or start)."
                        }
                    }
                }
                traces = ServiceTrace.recent()
            }
        },
    )
    result?.let {
        Spacer(Modifier.height(8.dp))
        Meta(it)
    }
    Spacer(Modifier.height(12.dp))
    Meta("Recent requests (no content, keys or screens):")
    Spacer(Modifier.height(4.dp))
    if (traces.isEmpty()) {
        Text("none yet", style = mono, color = DotColors.Muted)
    } else {
        traces.take(8).forEach { Text(it.line(), style = mono, color = DotColors.Ink) }
    }
    Spacer(Modifier.height(16.dp))
    Meta("TapBack Plus screens from FIXTURE data (example price, no store, nothing bought):")
    com.aaravsekhri.dot.ui.plus.PlusFixture.entries.forEach { fixture ->
        com.aaravsekhri.dot.ui.components.UnderlinedTextAction(text = fixture.label, onClick = { onPreviewPlus(fixture) })
    }
}
