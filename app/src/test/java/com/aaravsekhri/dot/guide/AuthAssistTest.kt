package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.AuthAssistant
import com.aaravsekhri.dot.guide.ai.AuthButton
import com.aaravsekhri.dot.guide.ai.AuthField
import com.aaravsekhri.dot.guide.ai.AuthChoice
import com.aaravsekhri.dot.guide.ai.AuthScreen
import com.aaravsekhri.dot.guide.ai.AuthScreenReader
import com.aaravsekhri.dot.guide.ai.AuthStep
import com.aaravsekhri.dot.guide.ai.FieldRole
import com.aaravsekhri.dot.guide.ai.Gesture
import com.aaravsekhri.dot.guide.ai.NavigationStyle
import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.ai.SensitiveScreenPolicy
import com.aaravsekhri.dot.guide.ai.SensitiveScreenPolicy.Reason
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-in help on the screens Dot never describes to anybody.
 *
 * Everything here runs on structure alone — what kind of box, where, which
 * has the cursor, what the send button says — and these tests hold it to
 * that: no field value is ever read, and the steps go one box at a time.
 */
class AuthAssistTest {

    // Android InputType bits.
    private val email = 0x21
    private val password = 0x81
    private val number = 0x2

    private fun screen(pkg: String = "com.example.bank", build: MutableList<NodeSnapshot>.() -> Unit): ScreenSnapshot {
        val nodes = mutableListOf<NodeSnapshot>()
        nodes.build()
        return ScreenSnapshot(pkg, nodes, 1080, 2400, windowId = 3)
    }

    private fun MutableList<NodeSnapshot>.box(
        top: Int,
        type: Int,
        hint: String = "",
        pw: Boolean = false,
        focused: Boolean = false,
        value: String? = null,
        max: Int = -1,
    ) = add(
        NodeSnapshot(
            id = size, parentId = null, depth = 1, windowId = 3, packageName = "p",
            className = "android.widget.EditText", viewId = null, text = if (pw) null else value,
            contentDescription = null, bounds = Bounds(60, top, 1020, top + 140),
            isVisibleToUser = true, isEnabled = true, isClickable = true, isFocusable = true,
            isScrollable = false, isPassword = pw, isCheckable = false,
            isFocused = focused, isEditable = true, hintText = hint, inputType = type, maxTextLength = max,
        ),
    )

    private fun MutableList<NodeSnapshot>.button(top: Int, label: String) = add(
        NodeSnapshot(
            id = size, parentId = null, depth = 1, windowId = 3, packageName = "p",
            className = "android.widget.Button", viewId = null, text = label, contentDescription = null,
            bounds = Bounds(600, top, 1020, top + 130), isVisibleToUser = true, isEnabled = true,
            isClickable = true, isFocusable = true, isScrollable = false, isPassword = false, isCheckable = false,
        ),
    )

    private fun MutableList<NodeSnapshot>.text(top: Int, words: String) = add(
        NodeSnapshot(
            id = size, parentId = null, depth = 1, windowId = 3, packageName = "p",
            className = "android.widget.TextView", viewId = null, text = words, contentDescription = null,
            bounds = Bounds(60, top, 1020, top + 80), isVisibleToUser = true, isEnabled = true,
            isClickable = false, isFocusable = false, isScrollable = false, isPassword = false, isCheckable = false,
        ),
    )

    private fun read(s: ScreenSnapshot, keyboard: Boolean = false): AuthScreen {
        val verdict = SensitiveScreenPolicy.judge(s)
        assertTrue("expected a private screen", verdict.sensitive)
        return AuthScreenReader.read(s, verdict.reason!!, keyboard)
    }

    private val assistant = AuthAssistant { NavigationStyle.Gestures }

    // -------------------------------------------------------------- the gate

    @Test
    fun `a short numeric box is a code box even when nothing says code`() {
        val s = screen {
            text(300, "Enter the 6 numbers we sent to you")
            box(600, number, max = 6)
        }
        assertEquals(Reason.VerificationCode, SensitiveScreenPolicy.judge(s).reason)
    }

    @Test
    fun `a message showing a code is private however it is worded`() {
        listOf("Your code is 482913", "482913 is your sign-in code", "Use OTP 5519 to continue").forEach { words ->
            val inbox = screen("com.google.android.apps.messaging") { text(400, words) }
            assertEquals(words, Reason.VerificationCode, SensitiveScreenPolicy.judge(inbox).reason)
        }
    }

    @Test
    fun `an email box on its own is not private, and its contents are never sent`() {
        val s = screen {
            box(600, email, hint = "Email", value = "someone@example.com")
            button(900, "Next")
        }
        assertFalse(SensitiveScreenPolicy.judge(s).sensitive)
        val observation = ObservationBuilder.build("s", s, 1, "", 0)
        val field = observation.candidates.single { it.editable }
        assertEquals("Email", field.naming)
        assertEquals("", field.label)
        assertEquals("filled", field.value)
        assertFalse(observation.candidates.any { "someone@example.com" in it.label || "someone" in it.value })
    }

    @Test
    fun `the structure read of a private screen carries no values`() {
        val s = screen {
            box(500, email, hint = "Email", value = "someone@example.com")
            box(700, password, hint = "Password", pw = true)
            button(900, "Sign in")
        }
        val auth = read(s)
        assertEquals(listOf(FieldRole.Email, FieldRole.Password), auth.fields.map { it.role })
        assertFalse("no value anywhere in what was read", auth.toString().contains("someone"))
        assertEquals("Sign in", auth.submit?.label)
    }

    // --------------------------------------------------------- one box at a time

    @Test
    fun `a password screen goes box, then typing, then the send button`() {
        val first = assistant.onScreen(read(screen { box(700, password, "Password", pw = true); button(900, "Next") }))
        first as AuthStep.Point
        assertEquals("password box", first.target.naming)
        assertFalse(first.typing)
        assertTrue(first.words.contains("TapBack can't see it"))

        val typing = assistant.onScreen(
            read(screen { box(700, password, "Password", pw = true, focused = true); button(900, "Next") }, keyboard = true),
        ) as AuthStep.Point
        assertTrue(typing.typing)
        // What comes next is part of the step, so there is nothing to confirm.
        assertEquals("Type your password — TapBack can't see it. Then tap “Next”.", typing.words)

        assistant.onDoneTyping()
        val send = assistant.onScreen(
            read(screen { box(700, password, "Password", pw = true, focused = true); button(900, "Next") }, keyboard = true),
        ) as AuthStep.Point
        assertEquals("Now tap “Next”.", send.words)
        assertEquals(900, send.target.bounds.top)
    }

    @Test
    fun `email comes before password on a screen that has both`() {
        val s = read(screen { box(500, email, "Email"); box(700, password, "Password", pw = true); button(900, "Sign in") })
        val step = assistant.onScreen(s) as AuthStep.Point
        assertEquals("email box", step.target.naming)
        assertTrue(step.words.contains("email address"))
    }

    @Test
    fun `moving the cursor to the next box counts the earlier one as done`() {
        assistant.onScreen(read(screen { box(500, email, "Email"); box(700, password, "Password", pw = true) }))
        val step = assistant.onScreen(
            read(screen { box(500, email, "Email"); box(700, password, "Password", pw = true, focused = true) }, keyboard = true),
        ) as AuthStep.Point
        assertEquals("password box", step.target.naming)
    }

    @Test
    fun `two send-like buttons are not guessed between`() {
        val s = read(screen { box(700, password, "Password", pw = true); button(900, "Next"); button(1100, "Continue") })
        assertNull(s.submit)
        assistant.onScreen(s)
        assistant.onDoneTyping()
        val step = assistant.onScreen(s)
        assertTrue("words only: ${step.words}", step is AuthStep.Say)
    }

    // ------------------------------------------------------------ verification

    private val codeScreen = screen("com.example.bank") {
        text(300, "Enter the verification code")
        box(600, number, max = 6)
        button(900, "Verify")
    }

    @Test
    fun `a code screen first asks where the code is`() {
        val step = assistant.onScreen(read(codeScreen)) as AuthStep.Choose
        assertEquals(
            listOf(AuthChoice.CodeOnThisPhone, AuthChoice.CodeOnOtherDevice, AuthChoice.NoCodeSeen),
            step.choices,
        )
        assertEquals("the same question while it waits", step, assistant.onScreen(read(codeScreen)))
    }

    @Test
    fun `on this phone - fetch, come back, type it - with uploads off meanwhile`() {
        assistant.onScreen(read(codeScreen))
        val fetch = assistant.onChoice(AuthChoice.CodeOnThisPhone) as AuthStep.Say
        assertTrue(fetch.words.contains("notifications"))
        assertTrue("nothing may be sent while a code may be on screen", assistant.keepsUploadsOff)
        assertEquals("still here: the advice holds", fetch, assistant.onScreen(read(codeScreen)))

        val away = assistant.onElsewhere() as AuthStep.Say
        assertEquals(Gesture.Back(NavigationStyle.Gestures), away.gesture)
        assertTrue(assistant.keepsUploadsOff)

        val back = assistant.onScreen(read(codeScreen)) as AuthStep.Point
        assertEquals("code box", back.target.naming)
        assertFalse("back in the sign-in: the ordinary rules again", assistant.keepsUploadsOff)
    }

    @Test
    fun `on another device - wait, and only then the code box`() {
        assistant.onScreen(read(codeScreen))
        val wait = assistant.onChoice(AuthChoice.CodeOnOtherDevice) as AuthStep.Say
        assertEquals(AuthAssistant.DONE, wait.confirmLabel)
        assertEquals("holds while they look", wait, assistant.onScreen(read(codeScreen)))
        assistant.onContinue()
        assertTrue(assistant.onScreen(read(codeScreen)) is AuthStep.Point)
    }

    @Test
    fun `nothing Dot can see leads to a question, not a guessed target`() {
        val protected = AuthScreen("com.example.bank", 3, Reason.ProtectedByTheApp, emptyList(), null, false, 1080, 2400)
        val step = assistant.onScreen(protected) as AuthStep.Choose
        assertTrue(AuthChoice.SeePasswordBox in step.choices)
        val words = assistant.onChoice(AuthChoice.SeePasswordBox) as AuthStep.Say
        assertTrue(words.words.contains("password box"))
    }

    @Test
    fun `saying it looks finished ends the sign-in help`() {
        val protected = AuthScreen("com.example.bank", 3, Reason.ProtectedByTheApp, emptyList(), null, false, 1080, 2400)
        assistant.onScreen(protected)
        assistant.onChoice(AuthChoice.LooksFinished)
        assertFalse(assistant.active)
    }

    // ------------------------------------------------------------- accounts

    private fun loginWithCreate() = AuthScreen(
        "com.example.social", 3, Reason.PasswordField,
        listOf(
            AuthField(FieldRole.Email, Bounds(48, 479, 1032, 593), false, true, Bounds(48, 419, 1032, 593), "Mobile number or email"),
            AuthField(FieldRole.Password, Bounds(48, 689, 1032, 803), false, true, Bounds(48, 629, 1032, 803), "Password"),
        ),
        submit = AuthButton("Log in", Bounds(48, 900, 1032, 1020)),
        keyboardVisible = false, screenWidth = 1080, screenHeight = 2400,
        create = AuthButton("Create new account", Bounds(48, 1500, 1032, 1620)),
        appName = "Example Social",
    )

    @Test
    fun `a screen offering both asks whether they have an account, once`() {
        val step = assistant.onScreen(loginWithCreate()) as AuthStep.Choose
        assertEquals("Do you already have an Example Social account?", step.words)
        assertEquals(listOf(AuthChoice.HaveAccount, AuthChoice.NoAccount, AuthChoice.NotSureAccount), step.choices)

        val signIn = assistant.onChoice(AuthChoice.HaveAccount) as AuthStep.Point
        assertEquals("the whole box, not the field in it", Bounds(48, 419, 1032, 593), signIn.target.bounds)
        assertTrue(signIn.words, signIn.words.contains("Enter your mobile number or email address") ||
            signIn.words.contains("enter your mobile number or email address"))
        assertTrue("the answer holds", assistant.onScreen(loginWithCreate()) is AuthStep.Point)
    }

    @Test
    fun `no account goes to the create button`() {
        assistant.onScreen(loginWithCreate())
        val create = assistant.onChoice(AuthChoice.NoAccount) as AuthStep.Point
        assertEquals("Create new account", create.target.naming)
        assertTrue(create.words.startsWith("Tap “Create new account”"))
    }

    @Test
    fun `not sure suggests trying to sign in first, then carries on`() {
        assistant.onScreen(loginWithCreate())
        val first = assistant.onChoice(AuthChoice.NotSureAccount) as AuthStep.Point
        assertTrue(first.words, first.words.startsWith("Try signing in first"))
    }

    @Test
    fun `an answer given earlier in the task is not asked again`() {
        assistant.rememberAccount(com.aaravsekhri.dot.guide.ai.AccountAnswer.Existing)
        assertTrue(assistant.onScreen(loginWithCreate()) is AuthStep.Point)
    }

    @Test
    fun `each step says what comes after it, so nobody has to say they finished`() {
        assistant.rememberAccount(com.aaravsekhri.dot.guide.ai.AccountAnswer.Existing)
        val email = assistant.onScreen(loginWithCreate()) as AuthStep.Point
        assertTrue(email.words, email.words.endsWith("Then tap the password box."))
        val typingPassword = assistant.onScreen(
            loginWithCreate().let { it.copy(fields = listOf(it.fields[0], it.fields[1].copy(focused = true)), keyboardVisible = true) },
        ) as AuthStep.Point
        assertTrue(typingPassword.words, typingPassword.words.endsWith("Then tap “Log in”."))
    }

    @Test
    fun `tapping send and staying on the same screen is said plainly`() {
        assistant.rememberAccount(com.aaravsekhri.dot.guide.ai.AccountAnswer.Existing)
        val screen = loginWithCreate()
        assistant.onScreen(screen)
        assistant.onDoneTyping()
        assistant.onDoneTyping()
        assistant.onScreen(screen)
        assistant.onDoneTyping()
        val send = assistant.onScreen(screen) as AuthStep.Point
        assertEquals("Now tap “Log in”.", send.words)
        assistant.onSubmitTapped()
        val again = assistant.onScreen(screen) as AuthStep.Point
        assertTrue(again.words, again.words.startsWith("The screen hasn't moved on."))
    }

    @Test
    fun `a box with no trustworthy outline is said in words`() {
        val screen = AuthScreen(
            "com.example.bank", 3, Reason.PasswordField,
            listOf(AuthField(FieldRole.Password, Bounds(0, 0, 1080, 2300), false, true, outline = null)),
            null, false, 1080, 2400,
        )
        assertTrue(assistant.onScreen(screen) is AuthStep.Say)
    }

    @Test
    fun `a password box on the way to a new account is a password being created, and says so`() {
        val page = screen {
            text(200, "Create a password")
            box(600, password, hint = "Password", pw = true, focused = true)
            button(900, "Next")
        }
        val creating = AuthAssistant { NavigationStyle.Gestures }.apply { rememberAccount(com.aaravsekhri.dot.guide.ai.AccountAnswer.New) }
        val step = creating.onScreen(read(page, keyboard = true)) as AuthStep.Point
        assertTrue(step.typing)
        assertEquals(com.aaravsekhri.dot.guide.ai.FieldKind.NewPassword, step.field)

        // The page's own heading says so, whatever was answered earlier.
        val unknown = AuthAssistant { NavigationStyle.Gestures }.onScreen(read(page, keyboard = true))
        assertEquals(com.aaravsekhri.dot.guide.ai.FieldKind.NewPassword, (unknown as AuthStep.Point).field)

        val login = screen {
            text(200, "Log in")
            box(600, password, hint = "Password", pw = true, focused = true)
            button(900, "Next")
        }
        val signingIn = AuthAssistant { NavigationStyle.Gestures }.apply { rememberAccount(com.aaravsekhri.dot.guide.ai.AccountAnswer.Existing) }
        val existing = signingIn.onScreen(read(login, keyboard = true)) as AuthStep.Point
        assertEquals(com.aaravsekhri.dot.guide.ai.FieldKind.Password, existing.field)
    }
}
