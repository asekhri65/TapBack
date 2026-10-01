package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * Sign-in help on the screens Dot will not describe to anybody.
 *
 * A password box, a code box, a card number: on those screens nothing goes to
 * the planning service — no text, no picture. But "carry on yourself" is
 * exactly where an older person may need help most, so Dot keeps guiding
 * here from the one kind of information that is safe to use on the phone:
 * structure. Which boxes there are, what kind each is (Android says so in
 * its input type), where they are, which has the cursor, and the label on
 * the one button that sends them. Never what anybody has typed, and never
 * any other text on the screen.
 *
 * Plain Kotlin, so the whole sequence — including a round trip to the
 * Messages app for a code — is testable without a device.
 */

enum class FieldRole { Email, Password, Code, Other }

/** One box on a credential screen. No value, ever: only what kind, where, and whether it has the cursor. */
data class AuthField(
    val role: FieldRole,
    val bounds: Bounds,
    val focused: Boolean,
    val enabled: Boolean,
    /** What to draw round it, from [ControlOutline]; null when only words will do. */
    val outline: Bounds? = bounds,
    /** The app's own name for the box — its placeholder or the label drawn in it. Never a value. */
    val label: String? = null,
)

/** The one button that sends the form, when there is exactly one. */
data class AuthButton(val label: String, val bounds: Bounds)

/** What Dot may know about a credential screen. */
data class AuthScreen(
    val packageName: String,
    val windowId: Int,
    val reason: SensitiveScreenPolicy.Reason,
    val fields: List<AuthField>,
    val submit: AuthButton?,
    val keyboardVisible: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    /** A way to make a new account, when the screen offers exactly one. */
    val create: AuthButton? = null,
    /** The app's name, as the phone knows it, for asking about an account. */
    val appName: String = "",
    /** The page's own heading says a password is being made, not entered: "Create a password". */
    val creatingPassword: Boolean = false,
) {
    /** Same boxes in the same places: the same screen, for keeping place in a sequence. */
    val layoutKey: String
        get() = "$packageName|$windowId|" + fields.joinToString(",") { "${it.role}@${it.bounds.top}" }
}

object AuthScreenReader {

    /**
     * Structure only. Field values are not read here: a password node's text
     * is never harvested at all, and for every other box only its input type,
     * length limit, hint and position are used.
     */
    fun read(
        screen: ScreenSnapshot,
        reason: SensitiveScreenPolicy.Reason,
        keyboardVisible: Boolean,
        pxPerDp: Float = ObservationBuilder.DEFAULT_PX_PER_DP,
        appName: String = "",
    ): AuthScreen {
        val fields = screen.nodes
            .filter { it.isVisibleToUser && !it.bounds.isEmpty && (it.isEditable || it.isPassword) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .map { node ->
                // A password box's own text is never harvested; its label is
                // the app's, from the hint or the words drawn inside its box.
                val label = ControlOutline.fieldLabel(node, screen, pxPerDp)
                AuthField(
                    role = roleOf(node, label),
                    bounds = node.bounds,
                    focused = node.isFocused,
                    enabled = node.isEnabled,
                    outline = (ControlOutline.resolve(node, screen, pxPerDp) as? ControlOutline.Verdict.Outline)?.bounds,
                    label = label,
                )
            }

        // A button's own label is part of the app, not of the person's data,
        // and it is only ever used here on the phone. Kept only when exactly
        // one button reads as "send": two is a guess.
        fun button(words: List<String>) = screen.nodes.filter { node ->
            node.isVisibleToUser && node.isClickable && !node.isEditable && !node.bounds.isEmpty &&
                TextNormalizer.normalize(node.text ?: node.contentDescription).let { t -> words.any { it == t } }
        }.singleOrNull()?.let {
            AuthButton((it.text ?: it.contentDescription).orEmpty().trim().take(30), it.bounds)
        }
        return AuthScreen(
            packageName = screen.packageName.orEmpty(),
            windowId = screen.windowId,
            reason = reason,
            fields = fields,
            submit = button(SUBMIT_WORDS),
            keyboardVisible = keyboardVisible,
            screenWidth = screen.screenWidth,
            screenHeight = screen.screenHeight,
            create = button(CREATE_WORDS),
            appName = appName,
            // The app's own words on the page, never a box's contents: a
            // heading that says a password is being made.
            creatingPassword = screen.nodes.any { node ->
                node.isVisibleToUser && !node.isEditable && !node.isPassword && !node.isClickable &&
                    FormFields.kindOf(node.text.orEmpty().take(60)) == FieldKind.NewPassword
            },
        )
    }

    fun roleOf(node: NodeSnapshot, label: String? = null): FieldRole {
        if (node.isPassword || InputKinds.isPassword(node.inputType)) {
            // A numeric password box that is short is a code or PIN box.
            return if (InputKinds.isNumber(node.inputType) && node.maxTextLength in 1..8) FieldRole.Code else FieldRole.Password
        }
        if (InputKinds.looksLikeCode(node)) return FieldRole.Code
        val hint = TextNormalizer.normalize(node.hintText ?: label)
        if (InputKinds.isEmail(node.inputType) || EMAIL_HINTS.any { it in hint }) return FieldRole.Email
        return FieldRole.Other
    }

    /** Buttons that make a new account rather than sign in to one. */
    private val CREATE_WORDS = listOf(
        "create new account", "create account", "create an account", "create a new account",
        "sign up", "register", "join now", "new account",
    )

    private val SUBMIT_WORDS = listOf(
        "next", "sign in", "log in", "login", "continue", "submit", "verify", "done", "confirm", "ok",
    )
    private val EMAIL_HINTS = listOf("email", "e-mail", "username", "user name", "phone or email", "mobile number")
}

/** Reading Android's InputType bits without depending on the framework class. */
object InputKinds {
    private const val CLASS_MASK = 0x0000000f
    private const val VARIATION_MASK = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val CLASS_PHONE = 0x00000003

    fun isNumber(type: Int) = type and CLASS_MASK == CLASS_NUMBER

    fun isPassword(type: Int): Boolean {
        val variation = type and VARIATION_MASK
        return when (type and CLASS_MASK) {
            CLASS_TEXT -> variation == 0x80 || variation == 0x90 || variation == 0xe0
            CLASS_NUMBER -> variation == 0x10
            else -> false
        }
    }

    fun isEmail(type: Int): Boolean {
        val variation = type and VARIATION_MASK
        return type and CLASS_MASK == CLASS_TEXT && (variation == 0x20 || variation == 0xd0)
    }

    fun isPhone(type: Int) = type and CLASS_MASK == CLASS_PHONE

    /**
     * A box that takes a one-time code: short and numeric, or labelled as one.
     * Structural, so it is caught even where no wording on the screen says
     * "code".
     */
    fun looksLikeCode(node: NodeSnapshot): Boolean {
        if (!node.isEditable) return false
        val hint = TextNormalizer.normalize(node.hintText)
        if (CODE_HINTS.any { it in hint }) return true
        return isNumber(node.inputType) && node.maxTextLength in 4..8
    }

    private val CODE_HINTS = listOf("code", "otp", "passcode", "pin")
}

/** A question Dot can answer locally, from a choice on the card. */
enum class AuthChoice(val label: String) {
    CodeOnThisPhone("On this phone"),
    CodeOnOtherDevice("On another device"),
    NoCodeSeen("I don't see one"),
    SeePasswordBox("A box for a password"),
    SeeCodeBox("A box for a code"),
    LooksFinished("It looks finished"),
    SeeError("An error message"),
    SeeSomethingElse("Something else"),
    HaveAccount("Yes, help me sign in"),
    NoAccount("No, help me create one"),
    NotSureAccount("I'm not sure"),
}

/** Whether the person already has an account here, once they have said. */
enum class AccountAnswer { Existing, New, Unsure }

/** Somewhere on a credential screen to point, found on the live screen. */
data class AuthTarget(
    val bounds: Bounds,
    val naming: String,
    val role: String,
    val packageName: String,
    val windowId: Int,
)

/** What to show next. */
sealed interface AuthStep {
    val words: String

    /**
     * Outline a box or the send button. [typing] shows the button that says
     * typing is finished, worded for [field]: what the box is for, never
     * what is in it.
     */
    data class Point(
        val target: AuthTarget,
        override val words: String,
        val typing: Boolean,
        val field: FieldKind? = null,
    ) : AuthStep

    /** Words only, with an "I'm done"-style button when [confirmLabel] is set. */
    data class Say(
        override val words: String,
        val confirmLabel: String? = null,
        val gesture: Gesture? = null,
    ) : AuthStep

    /** A choice, answered on the card. */
    data class Choose(override val words: String, val choices: List<AuthChoice>) : AuthStep

    /**
     * This part cannot go on: [appError] when the app itself is showing an
     * error, false when Dot cannot guide it reliably. Said honestly; the
     * place is saved to try again later.
     */
    data class Stop(override val words: String, val appError: Boolean) : AuthStep
}

/**
 * The sign-in subtask: which box is next, whether the code is on this phone,
 * and when uploads must stay off because another app may be showing it.
 */
class AuthAssistant(private val navigation: () -> NavigationStyle) {

    enum class Stage {
        /** Not helping with a sign-in. */
        Idle,

        /** Asked whether they already have an account here. */
        AskAccount,

        /** Going through the boxes on a credential screen. */
        Fields,

        /** Asked where the code or approval is. */
        AskWhereCode,

        /** The code is on this phone; the person has gone to read it. */
        FetchingCode,

        /** The code or approval is on another device. */
        OtherDevice,

        /** Nothing here could be identified; asked what they see. */
        WhatDoYouSee,
    }

    var stage: Stage = Stage.Idle
        private set

    /** The app the sign-in is in, so a return to it can be recognised. */
    var originPackage: String? = null
        private set

    /**
     * What the person said about having an account. Kept for the whole task,
     * not just this screen — and restored on resume from the answers the
     * session keeps — so the question is never asked twice.
     */
    var account: AccountAnswer? = null
        private set

    /**
     * True while Dot must not describe any screen to anybody, not even one
     * that looks ordinary: the person is in another app reading a code, and
     * an inbox is not something to upload.
     */
    val keepsUploadsOff: Boolean get() = stage == Stage.FetchingCode

    val active: Boolean get() = stage != Stage.Idle

    private var lastScreen: AuthScreen? = null
    private var layout: String? = null
    private val done = mutableSetOf<Int>()
    private var askedWhereFor: String? = null
    private var wentElsewhere = false

    /** "I'm not sure" about the account: said once, on the first box. */
    private var tryFirst = false

    /** The send button was tapped on this layout, and the screen is still here. */
    private var submitTriedOn: String? = null

    /**
     * Advice that holds until the person acts on it or the screen changes —
     * otherwise the next reading of the same screen would replace "look at
     * your other device" with "tap the code box" before they had looked.
     */
    private var holding: AuthStep.Say? = null

    /** Restores an account answer given earlier in the task. */
    fun rememberAccount(answer: AccountAnswer?) {
        account = answer
    }

    /** A credential screen is in front. What now? */
    fun onScreen(screen: AuthScreen): AuthStep {
        if (screen.layoutKey != layout) {
            layout = screen.layoutKey
            done.clear()
            holding = null
            submitTriedOn = null
        }
        lastScreen = screen
        if (originPackage == null) originPackage = screen.packageName
        when (stage) {
            Stage.FetchingCode ->
                if (screen.packageName == originPackage && wentElsewhere) {
                    // Back from reading the code: on to the code box.
                    stage = Stage.Fields
                    wentElsewhere = false
                    holding = null
                } else if (screen.fields.any { it.role == FieldRole.Code && it.focused } && screen.keyboardVisible) {
                    // Read it from the notifications without leaving, and
                    // already in the box: that is the code step under way.
                    stage = Stage.Fields
                    holding = null
                } else {
                    return fetching()
                }
            Stage.OtherDevice -> return otherDevice()
            else -> Unit
        }
        holding?.let { return it }

        if (screen.reason == SensitiveScreenPolicy.Reason.Payment) {
            stage = Stage.Fields
            return AuthStep.Say(
                "This screen asks for payment details. Enter them yourself only if you mean to pay — TapBack won't look.",
                confirmLabel = DONE,
            )
        }

        val codeScreen = screen.reason == SensitiveScreenPolicy.Reason.VerificationCode ||
            screen.fields.any { it.role == FieldRole.Code }
        if (codeScreen && askedWhereFor != screen.packageName && stage != Stage.OtherDevice) {
            askedWhereFor = screen.packageName
            stage = Stage.AskWhereCode
        }
        if (stage == Stage.AskWhereCode) return whereIsTheCode()
        if (stage == Stage.WhatDoYouSee) return whatDoYouSee()

        // Signing in and making an account are different paths, and which
        // one is the person's to say. Asked only where the screen offers
        // both and nobody has answered yet in this task.
        val offersBoth = screen.create != null && screen.fields.any { it.role == FieldRole.Password }
        if (offersBoth && account == null && !codeScreen) {
            stage = Stage.AskAccount
            return accountQuestion(screen)
        }
        if (stage == Stage.AskAccount && account == null) return accountQuestion(screen)
        if (account == AccountAnswer.New && screen.create != null) {
            stage = Stage.Fields
            return pointOrSay(
                screen, screen.create.bounds, screen.create.label, "Button",
                "Tap “${screen.create.label}”. TapBack will help with each part after that.",
            )
        }

        if (screen.fields.isEmpty()) {
            // Android is showing nothing Dot can point at — a protected screen,
            // or a box drawn without accessibility. Ask rather than guess.
            stage = Stage.WhatDoYouSee
            return whatDoYouSee()
        }
        if (stage == Stage.Idle || stage == Stage.AskAccount) stage = Stage.Fields
        return nextField(screen)
    }

    /** Somewhere other than the sign-in, while the code is being fetched. */
    fun onElsewhere(): AuthStep {
        wentElsewhere = true
        return awayWords()
    }

    private fun awayWords() = AuthStep.Say(
        "Read the code — TapBack won't look. Then go back to the sign-in. " +
            LocalWording.back(navigation()).substringAfter(": ").replaceFirstChar(Char::uppercase),
        confirmLabel = HAVE_CODE,
        gesture = Gesture.Back(navigation()),
    )

    /** The small "Done typing" fallback, for a box whose end cannot be seen. */
    fun onDoneTyping() {
        val screen = lastScreen ?: return
        val current = screen.fields.indexOfFirst { it.focused }.takeIf { it >= 0 } ?: firstUndone(screen) ?: return
        done += current
    }

    /**
     * The send button was tapped. An attempt, not a result: if this same
     * screen is still here afterwards, the next words say so.
     */
    fun onSubmitTapped() {
        submitTriedOn = layout
    }

    fun onChoice(choice: AuthChoice): AuthStep {
        return when (choice) {
            AuthChoice.CodeOnThisPhone -> {
                stage = Stage.FetchingCode
                wentElsewhere = false
                fetching()
            }
            AuthChoice.CodeOnOtherDevice -> {
                stage = Stage.OtherDevice
                otherDevice()
            }
            AuthChoice.NoCodeSeen -> {
                stage = Stage.Fields
                hold(
                    "Look on this screen for “Resend” or “Try another way”, and tap it if you see it. " +
                        "Messages can take a minute to arrive.",
                )
            }
            AuthChoice.SeePasswordBox -> {
                stage = Stage.Fields
                hold(
                    "Tap the password box and type your password — TapBack can't see it. Then tap the button " +
                        "underneath, often “Next” or “Sign in”.",
                )
            }
            AuthChoice.SeeCodeBox -> {
                stage = Stage.AskWhereCode
                whereIsTheCode()
            }
            AuthChoice.LooksFinished -> {
                end()
                AuthStep.Say("Good. TapBack will look at the screen again.", confirmLabel = null)
            }
            // The app is saying no. Dot cannot read what it says on a private
            // screen, so it does not say why — only that it did not go through.
            AuthChoice.SeeError -> AuthStep.Stop("The app is showing a message instead of going on.", appError = true)
            AuthChoice.SeeSomethingElse -> {
                stage = Stage.WhatDoYouSee
                whatDoYouSee()
            }
            AuthChoice.HaveAccount, AuthChoice.NoAccount, AuthChoice.NotSureAccount -> {
                account = when (choice) {
                    AuthChoice.HaveAccount -> AccountAnswer.Existing
                    AuthChoice.NoAccount -> AccountAnswer.New
                    else -> AccountAnswer.Unsure
                }
                tryFirst = account == AccountAnswer.Unsure
                stage = Stage.Fields
                lastScreen?.let(::onScreen) ?: AuthStep.Say("TapBack will look at the screen again.")
            }
        }
    }

    /**
     * Everything on the sign-in screen the person may need to reach — its
     * boxes and buttons — for keeping the card off them. Positions only.
     */
    fun reachableRegions(): List<Bounds> {
        val screen = lastScreen ?: return emptyList()
        return screen.fields.map { it.outline ?: it.bounds } + listOfNotNull(screen.submit?.bounds, screen.create?.bounds)
    }

    /** "I'm done" or "I have the code". */
    fun onContinue() {
        holding = null
        when (stage) {
            // Approved or read elsewhere: back to the boxes.
            Stage.OtherDevice -> stage = Stage.Fields
            // Still here, holding the code in mind: on to the box. Still in
            // another app: they are told how to come back, and the return
            // itself moves things on.
            Stage.FetchingCode -> if (!wentElsewhere) stage = Stage.Fields
            else -> Unit
        }
    }

    /** What to say while the code is being fetched: away, or not yet gone. */
    fun fetchingStep(): AuthStep = if (wentElsewhere) awayWords() else fetching()

    private fun fetching() = AuthStep.Say(
        "Open the message with your code. Slide down from the top of the screen to see notifications, " +
            "or open your Messages or email app. Read the code, then come back here.",
        confirmLabel = HAVE_CODE,
    )

    private fun otherDevice() = AuthStep.Say(
        "Look at your other device. If it asks you to approve, tap Yes there. If it shows a code, " +
            "type it into the box here. TapBack will wait.",
        confirmLabel = DONE,
    )

    private fun hold(words: String) = AuthStep.Say(words, confirmLabel = DONE).also { holding = it }

    /**
     * Leaves the sign-in subtask. The account answer is kept: it belongs to
     * the task, and a later sign-in screen in the same task must not ask it
     * again.
     */
    fun end() {
        stage = Stage.Idle
        originPackage = null
        lastScreen = null
        layout = null
        askedWhereFor = null
        wentElsewhere = false
        holding = null
        submitTriedOn = null
        done.clear()
    }

    /** A new task: nothing carries over, not even the account answer. */
    fun reset() {
        end()
        account = null
        tryFirst = false
        seenAsked = 0
    }

    // ---------------------------------------------------------------- steps

    private fun accountQuestion(screen: AuthScreen) = AuthStep.Choose(
        accountQuestionFor(screen.appName),
        listOf(AuthChoice.HaveAccount, AuthChoice.NoAccount, AuthChoice.NotSureAccount),
    )

    private fun whereIsTheCode() = AuthStep.Choose(
        "Where is the code or the approval request?",
        listOf(AuthChoice.CodeOnThisPhone, AuthChoice.CodeOnOtherDevice, AuthChoice.NoCodeSeen),
    )

    /** Times "What do you see?" was asked in this task. Bounded: after that, an honest pause. */
    private var seenAsked = 0

    private fun whatDoYouSee(): AuthStep {
        if (++seenAsked > MAX_WHAT_DO_YOU_SEE) {
            return AuthStep.Stop("I can't guide this part reliably, because I can't see it.", appError = false)
        }
        return AuthStep.Choose(
            "TapBack can't see this part of the screen. What do you see?",
            listOf(AuthChoice.SeePasswordBox, AuthChoice.SeeCodeBox, AuthChoice.SeeError, AuthChoice.LooksFinished, AuthChoice.SeeSomethingElse),
        )
    }

    /**
     * A private screen with nothing Dot may point at, and nothing to say
     * which kind of private it is: not "carry on yourself", but what it can
     * honestly do — payment gets its own words; anything else, a question.
     */
    fun onPrivateWithoutDetail(reason: SensitiveScreenPolicy.Reason): AuthStep {
        if (originPackage == null) originPackage = lastScreen?.packageName
        if (reason == SensitiveScreenPolicy.Reason.Payment) {
            stage = Stage.Fields
            return AuthStep.Say(
                "This screen asks for payment details. Enter them yourself only if you mean to pay — TapBack won't look.",
                confirmLabel = DONE,
            )
        }
        stage = Stage.WhatDoYouSee
        return whatDoYouSee()
    }

    private fun firstUndone(screen: AuthScreen): Int? =
        screen.fields.indices.firstOrNull { it !in done && screen.fields[it].enabled }

    private fun nextField(screen: AuthScreen): AuthStep {
        // Moving the cursor to a later box means the earlier ones are done.
        val focused = screen.fields.indexOfFirst { it.focused }
        if (focused > 0) (0 until focused).forEach { done += it }

        val next = firstUndone(screen)
        val submit = screen.submit
        if (next == null) {
            if (submit == null) {
                return AuthStep.Say("Now tap the button that sends it — it often says “Next” or “Sign in”.", confirmLabel = DONE)
            }
            // Tapped once already and still here: a message on the page,
            // most likely. Dot cannot read it on a private screen, so it says
            // what to look for.
            val words = if (submitTriedOn == layout) {
                "The screen hasn't moved on. If it shows a message, do what it says, then tap “${submit.label}” again."
            } else {
                "Now tap “${submit.label}”."
            }
            return pointOrSay(screen, submit.bounds, submit.label, "Button", words)
        }
        val field = screen.fields[next]
        // What comes after this box, so nobody has to tell Dot they have
        // finished typing: moving to the next box, or tapping the button, is
        // itself the sign.
        val after = screen.fields.indices.firstOrNull { it > next && it !in done && screen.fields[it].enabled }
            ?.let { " Then tap the ${boxName(screen.fields[it])}." }
            ?: submit?.let { " Then tap “${it.label}”." }
            .orEmpty()
        val intro = if (tryFirst) {
            tryFirst = false
            "Try signing in first — if it says there's no account, tap “${screen.create?.label ?: "Create new account"}”. "
        } else {
            ""
        }
        val typing = field.focused && screen.keyboardVisible
        val words = intro + if (typing) {
            "${entry(field)}.$after"
        } else {
            "Tap the ${boxName(field)} and ${entry(field).replaceFirstChar(Char::lowercase)}.$after"
        }
        val outline = field.outline ?: return AuthStep.Say(words)
        return AuthStep.Point(target(screen, outline, boxName(field), "EditText"), words, typing, kindOf(field, screen.creatingPassword))
    }

    /**
     * What a box is for, for the words on the button that says it is typed:
     * the app's own name for it where it gives one. A password box on the way
     * to a new account is a password being created.
     */
    private fun kindOf(field: AuthField, creating: Boolean = false): FieldKind {
        val named = FormFields.kindOf(field.label.orEmpty())
        return when (field.role) {
            FieldRole.Password ->
                if (named == FieldKind.NewPassword || account == AccountAnswer.New || creating) FieldKind.NewPassword else FieldKind.Password
            FieldRole.Code -> FieldKind.Code
            FieldRole.Email -> if (named == FieldKind.EmailOrPhone || named == FieldKind.Phone) named else FieldKind.Email
            FieldRole.Other -> named.takeUnless { it == FieldKind.Password || it == FieldKind.NewPassword || it == FieldKind.Code } ?: FieldKind.Unknown
        }
    }

    /** An outline when the control has a trustworthy one, words alone when not. */
    private fun pointOrSay(screen: AuthScreen, bounds: Bounds?, naming: String, role: String, words: String): AuthStep =
        if (bounds == null) AuthStep.Say(words) else AuthStep.Point(target(screen, bounds, naming, role), words, typing = false)

    private fun target(screen: AuthScreen, bounds: Bounds, naming: String, role: String) =
        AuthTarget(bounds, naming, role, screen.packageName, screen.windowId)

    /** What a person would call the box: by the app's own label where it has one. */
    private fun boxName(field: AuthField): String {
        val plain = when (field.role) {
            FieldRole.Email -> "email box"
            FieldRole.Password -> "password box"
            FieldRole.Code -> "code box"
            FieldRole.Other -> "box"
        }
        // The app's own label where it says more than the kind of box does:
        // "Mobile number or email", "First name" — not "Password" again.
        val label = field.label?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return plain
        val saysNoMore = TextNormalizer.normalize(label) == TextNormalizer.normalize(plain.removeSuffix(" box"))
        return if (saysNoMore || field.role == FieldRole.Password || field.role == FieldRole.Code) plain else "“$label” box"
    }

    /** What goes in, as an instruction: specific wherever the app said what the box is for. */
    private fun entry(field: AuthField): String {
        val label = field.label?.trim().orEmpty()
        val words = TextNormalizer.normalize(label)
        return when (field.role) {
            FieldRole.Password -> "Type your password — TapBack can't see it"
            FieldRole.Code -> "Type the code you were sent — TapBack can't see it"
            FieldRole.Email -> when {
                ("mobile" in words || "phone" in words) && "email" in words -> "Enter your mobile number or email address"
                "user" in words -> "Enter your username"
                "phone" in words || "mobile" in words -> "Enter your phone number"
                else -> "Enter your email address"
            }
            FieldRole.Other -> when {
                label.isEmpty() -> "Type what the screen asks for — this box has no name TapBack can read"
                words.startsWith("enter ") || words.startsWith("your ") -> label.replaceFirstChar(Char::uppercase)
                else -> "Enter your ${label.replaceFirstChar(Char::lowercase)}"
            }
        }
    }

    companion object {
        /** "What do you see?" at most this many times in a task, then an honest pause. */
        const val MAX_WHAT_DO_YOU_SEE = 2

        const val DONE = "I'm done"
        const val HAVE_CODE = "I have the code"

        /** The account question, the same wherever it is asked, so an answer can be found again. */
        fun accountQuestionFor(appName: String): String {
            val app = appName.trim()
            return when {
                app.isEmpty() -> "Do you already have an account for this app?"
                app.first().lowercaseChar() in "aeiou" -> "Do you already have an $app account?"
                else -> "Do you already have a $app account?"
            }
        }

        /** Reads a recorded answer back into what it means, for resuming. */
        fun accountAnswerFrom(question: String, answer: String): AccountAnswer? {
            if (!question.startsWith("Do you already have a")) return null
            return when (answer) {
                AuthChoice.HaveAccount.label -> AccountAnswer.Existing
                AuthChoice.NoAccount.label -> AccountAnswer.New
                AuthChoice.NotSureAccount.label -> AccountAnswer.Unsure
                else -> null
            }
        }
    }
}
