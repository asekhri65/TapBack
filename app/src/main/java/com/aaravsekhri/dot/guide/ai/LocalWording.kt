package com.aaravsekhri.dot.guide.ai

/**
 * Words the phone writes itself, rather than taking from the model.
 *
 * Only for instructions whose wording has to agree with a fact the phone
 * knows better: which way a finger moves to reveal content in a given
 * direction, and how this particular phone gets to its Home screen. A model
 * that says "scroll down" might mean the content or the finger; these
 * sentences say both halves out loud, so they cannot mean the wrong one.
 *
 * Plain Kotlin so the agreement between direction and gesture is a unit test,
 * not a hope. The app is English-only at present; when it is not, these move
 * to resources unchanged.
 */
/** The action key a keyboard shows, when it says which. Only these fixed words are ever read from a keyboard. */
enum class KeyboardAction { Search, Send, Go, Done, Next, Enter }

object LocalWording {

    fun home(navigation: NavigationStyle): String = when (navigation) {
        NavigationStyle.Gestures ->
            "Go to your Home screen: slide one finger up from the very bottom edge of the screen."
        NavigationStyle.ThreeButton ->
            "Go to your Home screen: tap the round Home button at the bottom of the screen."
        NavigationStyle.TwoButton ->
            "Go to your Home screen: tap the long Home button at the bottom of the screen."
        // Not pretending to know: both common ways, said plainly.
        NavigationStyle.Unknown ->
            "Go to your Home screen: tap the Home button at the bottom, or slide up from the bottom edge."
    }

    fun back(navigation: NavigationStyle): String = when (navigation) {
        NavigationStyle.Gestures ->
            "Go back one screen: slide one finger in from the left or right edge of the screen."
        NavigationStyle.ThreeButton, NavigationStyle.TwoButton ->
            "Go back one screen: tap the Back arrow at the bottom of the screen."
        NavigationStyle.Unknown ->
            "Go back one screen: tap the Back arrow, or slide in from the side edge of the screen."
    }

    /** The first thing said when a guide starts inside Dot itself. */
    fun leaveDot(navigation: NavigationStyle): String =
        "Let's start from your Home screen. " + home(navigation).substringAfter(": ").replaceFirstChar(Char::uppercase)

    /**
     * Dot is in front in the middle of a task. Back is not the way out of
     * Dot — from Dot's own screen it goes to the Home screen, not to the app —
     * so the way back to the app is recent apps, where it is the latest.
     *
     * @param app the app's name as the phone shows it, or blank when not known.
     */
    fun returnFromDot(app: String, navigation: NavigationStyle): String {
        val name = app.trim().take(40)
        val which = if (name.isBlank()) "the app you were using" else name
        val lead = if (name.isBlank()) "Now go back to the app you were using." else "Now go back to $name."
        return "$lead ${recents(navigation)} Then tap $which."
    }

    /** Opening recent apps, the way this phone does it. */
    fun recents(navigation: NavigationStyle): String = when (navigation) {
        NavigationStyle.Gestures ->
            "Slide one finger up from the very bottom edge, hold for a moment, then let go."
        NavigationStyle.ThreeButton ->
            "Tap the square Recent apps button at the bottom of the screen."
        NavigationStyle.TwoButton ->
            "Slide the long Home button up, hold for a moment, then let go."
        NavigationStyle.Unknown ->
            "Open your recent apps: tap the square button at the bottom, or slide up from the bottom edge and hold."
    }

    /**
     * Revealing content in one direction takes the finger the other way.
     *
     * Stated in both terms — what moves and where it comes from — because
     * "scroll down" is ambiguous to exactly the people Dot is for.
     */
    fun scroll(reveal: Reveal, lookingFor: String): String {
        val gesture = when (reveal) {
            Reveal.Below -> "Slide your finger up the screen to see more below."
            Reveal.Above -> "Slide your finger down the screen to see more above."
            Reveal.Right -> "Slide your finger to the left to see more on the right."
            Reveal.Left -> "Slide your finger to the right to see more on the left."
        }
        val target = lookingFor.trim().trim('"', '“', '”').take(60)
        return if (target.isBlank()) gesture else "Look for “$target”. $gesture"
    }

    /** Opening the list of all apps, with Pebble showing the place on the screen. */
    fun openAppListHere(): String = "Start where Pebble is and slide your finger up."

    /**
     * Opening the list of all apps on a Home screen whose way in is not known
     * well enough to point at: said in words, with the common way first.
     */
    fun openAppListInWords(): String =
        "Open the list of all your apps. On many phones, you slide your finger up from the middle of the Home screen; some have an Apps button."

    /** The swipe opened recent apps instead: back to Home first. */
    fun leaveRecents(navigation: NavigationStyle): String = "That opened your recent apps. " + home(navigation)

    /** Which way the finger itself travels to reveal content in [reveal]'s direction. */
    fun fingerDirection(reveal: Reveal): String = when (reveal) {
        Reveal.Below -> "up"
        Reveal.Above -> "down"
        Reveal.Right -> "left"
        Reveal.Left -> "right"
    }

    /** The keyboard's own send key, whatever it is drawn as on this keyboard. */
    fun submitWithKeyboard(action: KeyboardAction? = null): String = when (action) {
        KeyboardAction.Search -> "Press the Search key on the keyboard: the magnifying glass."
        KeyboardAction.Send -> "Press the Send key on the keyboard: the arrow."
        KeyboardAction.Go -> "Press the Go key on the keyboard: the arrow."
        KeyboardAction.Done -> "Press the Done key on the keyboard: the tick."
        KeyboardAction.Next -> "Press the Next key on the keyboard: the arrow."
        KeyboardAction.Enter -> "Press the Enter key on the keyboard: the bent arrow."
        null -> "Press the search or enter key on the keyboard, at the bottom right."
    }

    fun tap(name: String): String = if (name.isBlank()) "Tap it." else "Tap $name."

    /** A typing step the planner left unworded. */
    fun typeInto(field: String, text: String): String {
        val words = text.trim().take(MAX_TYPED)
        val box = field.trim().take(40).ifBlank { "the box" }.let { if (it == "the box") it else "the “$it” box" }
        return if (words.isBlank()) "Tap $box and type what you want to find." else "Type “$words” in $box."
    }

    /**
     * Words to type are shown whole: cut short, "…Washington, DC" became
     * "…Washington, " and the learner typed something else. The planner's
     * text is already capped at this length.
     */
    private const val MAX_TYPED = 120

    fun typing(instruction: String, text: String): String {
        val words = text.trim().take(MAX_TYPED)
        return if (words.isBlank() || instruction.contains(words, ignoreCase = true)) {
            instruction
        } else {
            "$instruction Type: “$words”"
        }
    }
}
