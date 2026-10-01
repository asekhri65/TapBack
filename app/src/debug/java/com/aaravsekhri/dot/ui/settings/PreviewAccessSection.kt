package com.aaravsekhri.dot.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.account.AccountResult
import com.aaravsekhri.dot.account.PlusRepository
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.components.UnderlinedTextAction
import com.aaravsekhri.dot.ui.plus.longDay
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotType
import kotlinx.coroutines.launch

/**
 * Test builds only: a preview code from the person running TapBack's
 * service, for recording lessons. The service checks the code against its
 * own configuration and grants this install temporary Plus; nothing here
 * decides anything, and the code is not kept.
 *
 * Shown as what it is — preview access with an end date — never as a
 * purchase.
 */
@Composable
fun ColumnScope.PreviewAccessSection() {
    val context = LocalContext.current
    val plus = remember(context) { PlusRepository.get(context) }
    val state by plus.state.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val allowance = state.allowance

    if (allowance?.plusPreview == true) {
        Text(
            "Preview access to TapBack Plus until ${allowance.plusExpiresAtMs?.let(::longDay) ?: "soon"}. " +
                "This isn’t a purchase, and it ends by itself.",
            style = DotType.CardBody,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        UnderlinedTextAction(
            text = "End preview now",
            onClick = {
                if (busy) return@UnderlinedTextAction
                busy = true
                scope.launch {
                    message = when (plus.revokePreview()) {
                        is AccountResult.Ok -> "Preview ended."
                        is AccountResult.Failed -> "Couldn’t reach TapBack’s service. Try again."
                    }
                    busy = false
                }
            },
        )
    } else {
        Text("Have a preview code for recording lessons? Enter it here.", style = DotType.CardBody, color = DotColors.Ink)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.take(40) },
            label = { Text("Preview code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = if (busy) "Checking…" else "Redeem preview code",
            onClick = {
                if (busy || code.isBlank()) return@SecondaryButton
                busy = true
                scope.launch {
                    message = when (val r = plus.redeemPreview(code)) {
                        is AccountResult.Ok -> {
                            code = ""
                            "Preview access is on until ${longDay(r.value.expiresAtMs)}." +
                                if (plus.billingAvailable && !r.value.atRevenueCat) " RevenueCat didn’t take the grant; TapBack’s service still allows it." else ""
                        }
                        is AccountResult.Failed -> when (r.category) {
                            "preview_code_invalid" -> "That code isn’t right."
                            "preview_rate_limited" -> "Too many tries. Wait an hour, then try again."
                            "preview_off" -> "Preview codes aren’t switched on for this service."
                            "preview_exhausted" -> "This code has already been used as many times as it allows."
                            "preview_revoked" -> "Preview access was ended for this install."
                            else -> "Couldn’t reach TapBack’s service. Try again."
                        }
                    }
                    busy = false
                }
            },
        )
    }
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = DotType.CardMeta, color = DotColors.Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
