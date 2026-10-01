package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * When Dot stops looking and hands back to the person.
 *
 * Filtering password *nodes* is not enough once a screenshot is involved: an
 * image of a screen contains whatever is on it, including the field, the
 * keyboard preview and any code sitting in a notification. So the decision is
 * made about the screen as a whole, and it is deliberately cautious — the cost
 * of stopping on an ordinary screen is a moment's inconvenience, and the cost
 * of not stopping on a real one is somebody's bank login in a request body.
 *
 * On a screen judged sensitive Dot uploads nothing at all: no snapshot, no
 * image, no labels. It says what it can see is private, tells the person to
 * carry on themselves, and waits to be told they are done.
 */
object SensitiveScreenPolicy {

    /** Why Dot stood down, for the words shown to the person. */
    enum class Reason {
        PasswordField,
        VerificationCode,
        Payment,
        ProtectedByTheApp,
    }

    data class Verdict(val sensitive: Boolean, val reason: Reason? = null)

    /**
     * @param screenshotRefused true when the platform itself refused to take a
     *   picture of this screen. Android does that for windows marked secure,
     *   which is the app saying plainly that its contents are not for other
     *   processes. Dot takes it at its word and stops reading too.
     */
    fun judge(screen: ScreenSnapshot, screenshotRefused: Boolean = false): Verdict {
        if (screen.containsPasswordField) return Verdict(true, Reason.PasswordField)
        if (screenshotRefused) return Verdict(true, Reason.ProtectedByTheApp)

        // Structure before wording. A box Android says is a password, or a
        // short numeric box, is caught whatever the screen around it says.
        val boxes = screen.nodes.filter { it.isVisibleToUser && it.isEditable }
        if (boxes.any { InputKinds.isPassword(it.inputType) && !InputKinds.looksLikeCode(it) }) {
            return Verdict(true, Reason.PasswordField)
        }
        if (boxes.any(InputKinds::looksLikeCode)) return Verdict(true, Reason.VerificationCode)

        val words = screen.nodes.asSequence()
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .filterNotNull()
            .map(TextNormalizer::normalize)
            .filter { it.isNotBlank() }
            .toList()

        fun any(markers: List<String>) = words.any { word -> markers.any { word.contains(it) } }

        if (any(CODE_MARKERS)) return Verdict(true, Reason.VerificationCode)
        // A message showing a code, however it is phrased: "Your code is
        // 482913", "482913 is your sign-in code", an OTP in a notification.
        if (words.any(CODE_IN_TEXT::containsMatchIn)) return Verdict(true, Reason.VerificationCode)
        if (any(PAYMENT_MARKERS)) return Verdict(true, Reason.Payment)
        return Verdict(false)
    }

    /**
     * Wording chosen to be read at a glance. It never names the app or repeats
     * anything from the screen.
     */
    private val CODE_MARKERS = listOf(
        "verification code", "one time code", "one-time code", "security code",
        "enter the code", "6 digit code", "two factor", "2-step verification",
        "authentication code",
    )

    private val CODE_IN_TEXT = Regex(
        """\b(code|otp|passcode|pin)\b.{0,40}\b\d{4,8}\b|\b\d{4,8}\b.{0,40}\b(code|otp|passcode)\b""",
    )

    private val PAYMENT_MARKERS = listOf(
        "card number", "cvv", "cvc", "security number", "expiry date",
        "sort code", "account number", "payment method", "billing address",
        "pay now", "confirm payment",
    )
}
