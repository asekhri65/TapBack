package com.aaravsekhri.dot.experiment

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Stand-ins for the third-party screens Dot has to point at, for testing on a
 * device. Debug builds only.
 *
 * Nothing here is real: no account exists, nothing typed goes anywhere, the
 * "install" is a progress bar on a timer and the "post" button does nothing.
 * Each page reproduces the *structure* that made a real app's outline wrong,
 * built from ordinary views so the accessibility tree is what a real app's
 * would be:
 *
 * - `search` — a search field at the foot of the page, just above the
 *   keyboard, inside a rounded pill with icon buttons; its hint wraps to two
 *   lines until it has the cursor, so it changes height when focused.
 * - `store` → Install → progress → Open → `login`.
 * - `login` — each box is a bordered container whose text field sits in its
 *   lower part, with the field's name drawn inside it; "Log in" and
 *   "Create new account" on the same screen; a validation error when empty.
 * - `signup1` → `signup2` → `terms` → `welcome`.
 * - `birthday` — a date box showing today's date, "(0 years old)" beside
 *   it, and a spinner date picker; Next with that date is refused with a
 *   message, and a real one shows "Saving…" and then the password page, all
 *   in the same window, as a sign-up that swaps its content does.
 * - `signup-error` — the app saying it could not create the account.
 * - `edge` — controls against the left, right and bottom edges.
 * - `card` — one tappable card holding two buttons of its own.
 */
// A test screen in English only, like the rest of the debug fixtures.
@android.annotation.SuppressLint("SetTextI18n")
class FixtureAppsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        show(intent.getStringExtra("page") ?: "search")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(stroke: Int, radiusDp: Int, fill: Int = Color.WHITE) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun page(scroll: Boolean = true): Pair<View, LinearLayout> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setBackgroundColor(Color.rgb(240, 242, 245))
        }
        return if (scroll) {
            ScrollView(this).apply { isFillViewport = true; addView(column) } to column
        } else {
            column to column
        }
    }

    private fun LinearLayout.title(words: String, size: Float = 24f) = addView(
        TextView(context).apply {
            text = words
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(20))
        },
    )

    private fun LinearLayout.body(words: String) = TextView(context).apply {
        text = words
        textSize = 16f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(4), 0, dp(12))
    }.also { addView(it) }

    private fun LinearLayout.wide(label: String, filled: Boolean = true, onClick: () -> Unit) = addView(
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            setTextColor(if (filled) Color.WHITE else Color.rgb(8, 102, 255))
            background = rounded(
                stroke = Color.rgb(8, 102, 255),
                radiusDp = 24,
                fill = if (filled) Color.rgb(8, 102, 255) else Color.TRANSPARENT,
            )
            setOnClickListener { onClick() }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) },
    )

    /**
     * A bordered box with its text field in the lower part and the field's
     * name drawn inside it — the shape a login form's boxes take. The field
     * is what accessibility calls the control; the border is the container.
     */
    private fun LinearLayout.labelledBox(name: String, type: Int): EditText {
        val field = EditText(context).apply {
            hint = name
            inputType = type
            textSize = 17f
            background = null
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        val box = FrameLayout(context).apply {
            background = rounded(Color.rgb(205, 208, 213), 12)
            // Tapping anywhere on the box puts the cursor in it.
            isClickable = true
            setOnClickListener { field.requestFocus() }
            addView(
                field,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM),
            )
        }
        addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(12) })
        return field
    }

    /**
     * Keeps the page clear of the status bar, the navigation bar and the
     * keyboard. An app drawn edge to edge is not resized for the keyboard by
     * the system any more; it moves its own content up, as this does.
     */
    override fun setContentView(view: View) {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(240, 242, 245))
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Only needed where the platform draws edge to edge by default.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                    insets
                }
            }
        }
        super.setContentView(frame)
    }

    private fun show(page: String) {
        handler.removeCallbacksAndMessages(null)
        when {
            page == "search" -> search()
            page.startsWith("results:") -> results(page.removePrefix("results:"))
            page == "store" -> store()
            page == "login" -> login()
            page == "signup1" -> signupName()
            page == "signup2" -> signupPassword()
            page == "birthday" -> birthday()
            page == "signup-error" -> signupError()
            page == "terms" -> terms()
            page == "welcome" -> welcome()
            page == "edge" -> edge()
            page == "card" -> card()
            page == "under" -> under()
            page == "tall" -> tall()
            page == "loading" -> loading(finishes = true)
            page == "blank" -> loading(finishes = false)
            else -> search()
        }
    }

    // ------------------------------------------------------------- search

    private fun search() {
        val (root, column) = page(scroll = false)
        column.title("Search apps")
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Best puzzle games", "Online shopping", "Learning something new", "Photo editing").forEach { words ->
            chips.addView(
                Button(this).apply {
                    text = words
                    isAllCaps = false
                    textSize = 14f
                    background = rounded(Color.rgb(160, 165, 175), 8, Color.rgb(230, 233, 240))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) },
            )
        }
        column.addView(HorizontalScrollView(this).apply { addView(chips) })
        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        val field = EditText(this).apply {
            hint = "What are you interested in?"
            textSize = 17f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            // Wraps to two lines until it has the cursor, as a long hint in a
            // narrow box does: the field is a different height once focused.
            minLines = 2
            setOnFocusChangeListener { _, focused -> minLines = if (focused) 1 else 2 }
            setOnEditorActionListener { v, action, event ->
                val search = action == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (search) show("results:${v.text.toString().trim()}")
                search
            }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(8, 102, 255), 26, Color.rgb(236, 238, 246))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // Tapping anywhere on the pill puts the cursor in it, as a real
            // search bar does — which is also what makes the pill a node.
            isClickable = true
            setOnClickListener { field.requestFocus() }
            addView(
                ImageButton(context).apply {
                    setImageResource(android.R.drawable.ic_menu_search)
                    background = null
                    contentDescription = "Search"
                    setOnClickListener { show("results:${field.text.toString().trim()}") }
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                ImageButton(context).apply {
                    setImageResource(android.R.drawable.ic_btn_speak_now)
                    background = null
                    contentDescription = "Voice search"
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
        }
        column.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun results(query: String) {
        val (root, column) = page()
        column.title("Results for “$query”", 20f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(220, 220, 220), 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { show("store") }
            addView(
                TextView(context).apply {
                    text = "Example Social"
                    textSize = 18f
                    setTextColor(Color.BLACK)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        column.addView(row)
        column.body("Example Tools · Example Games")
        setContentView(root)
    }

    // -------------------------------------------------------------- store

    private fun store() {
        val (root, column) = page()
        column.title("Example Social")
        column.body("Example Inc. · Contains ads · 4.1 ★")
        val status = column.body("")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(actions)

        fun installed() {
            status.text = "Installed"
            actions.removeAllViews()
            actions.wide("Uninstall", filled = false) { show("store") }
            actions.wide("Open") { show("login") }
        }

        fun install() {
            actions.removeAllViews()
            var percent = 0
            val tick = object : Runnable {
                override fun run() {
                    percent += 10
                    if (percent >= 100) {
                        installed()
                    } else {
                        status.text = "Installing… $percent%"
                        handler.postDelayed(this, 2000)
                    }
                }
            }
            status.text = "Pending…"
            handler.postDelayed(tick, 2000)
        }
        actions.wide("Install") { install() }
        setContentView(root)
    }

    // -------------------------------------------------------------- login

    private fun login() {
        val (root, column) = page()
        column.title("Example Social", 28f)
        val email = column.labelledBox(
            "Mobile number or email",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        val password = column.labelledBox(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val error = column.body("").apply { setTextColor(Color.rgb(200, 30, 30)) }
        column.wide("Log in") {
            // Validation on the page: an empty box is an error, not a new page.
            when {
                email.text.isNullOrBlank() -> error.text = "Enter your mobile number or email address."
                password.text.isNullOrBlank() -> error.text = "Enter your password."
                else -> show("welcome")
            }
        }
        column.body("Forgot password?").apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(View(this), LinearLayout.LayoutParams(0, dp(80)))
        column.wide("Create new account", filled = false) { show("signup1") }
        setContentView(root)
    }

    private fun signupName() {
        val (root, column) = page()
        column.title("What's your name?")
        column.body("Enter the name you use in real life.")
        column.labelledBox("First name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        column.labelledBox("Last name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        column.wide("Next") { show("signup2") }
        setContentView(root)
    }

    private fun birthday() {
        val (root, column) = page()
        column.title("What's your birthday?")
        column.body("Choose your date of birth. You can always make this private later.")
        val today = java.util.Calendar.getInstance()
        var chosen = today.clone() as java.util.Calendar
        val age = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }
        val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.US)
        val box = EditText(this).apply {
            hint = "Birthday"
            textSize = 17f
            isFocusable = false
            isCursorVisible = false
            background = rounded(Color.rgb(205, 208, 213), 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val error = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(200, 30, 30))
            visibility = View.GONE
        }
        fun refresh() {
            box.setText(format.format(chosen.time))
            var years = today.get(java.util.Calendar.YEAR) - chosen.get(java.util.Calendar.YEAR)
            if (today.get(java.util.Calendar.DAY_OF_YEAR) < chosen.get(java.util.Calendar.DAY_OF_YEAR)) years--
            age.text = "Birthday ($years years old)"
        }
        refresh()
        box.setOnClickListener {
            @Suppress("DEPRECATION")
            android.app.DatePickerDialog(
                this,
                android.app.AlertDialog.THEME_HOLO_LIGHT,
                { _, y, m, d ->
                    chosen = java.util.Calendar.getInstance().apply { set(y, m, d) }
                    error.visibility = View.GONE
                    refresh()
                },
                chosen.get(java.util.Calendar.YEAR),
                chosen.get(java.util.Calendar.MONTH),
                chosen.get(java.util.Calendar.DAY_OF_MONTH),
            ).show()
        }
        column.addView(age)
        column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        column.addView(error)
        column.wide("Next") {
            val years = today.get(java.util.Calendar.YEAR) - chosen.get(java.util.Calendar.YEAR)
            if (years < 5) {
                error.text = "It looks like you entered the wrong info. Please be sure to use your real birthday."
                error.visibility = View.VISIBLE
            } else {
                saving()
            }
        }
        setContentView(root)
    }

    /** The app itself saying the account could not be made: an explicit error, not an unreadable screen. */
    private fun signupError() {
        val (root, column) = page()
        column.title("Something went wrong")
        column.body("We couldn't create your account right now. Please try again later.")
        column.wide("OK", filled = false) { }
        setContentView(root)
    }

    /** A moment of "Saving…", then the password page: the same window throughout. */
    private fun saving() {
        val (root, column) = page(scroll = false)
        column.title("Saving…")
        column.addView(android.widget.ProgressBar(this))
        setContentView(root)
        handler.postDelayed({ signupPassword() }, 4_000)
    }

    private fun signupPassword() {
        val (root, column) = page()
        column.title("Create a password")
        column.body("Create a password with at least 6 letters or numbers.")
        column.labelledBox("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        column.wide("Next") { show("terms") }
        setContentView(root)
    }

    private fun terms() {
        val (root, column) = page()
        column.title("Agree to Example's terms")
        column.body("By tapping I agree, you agree to create an account and to Example's Terms and Privacy Policy.")
        column.wide("I agree") { show("welcome") }
        setContentView(root)
    }

    private fun welcome() {
        val (root, column) = page()
        column.title("Welcome to Example Social")
        val composer = TextView(this).apply {
            text = "What's on your mind?"
            textSize = 17f
            setTextColor(Color.GRAY)
            background = rounded(Color.rgb(205, 208, 213), 20)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { this.text = "(Nothing is posted: this is a test screen.)" }
        }
        column.addView(composer)
        setContentView(root)
    }

    // ------------------------------------------------------------ geometry

    private fun edge() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        fun corner(label: String, gravity: Int) = frame.addView(
            Button(this).apply {
                text = label
                isAllCaps = false
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), gravity),
        )
        corner("Skip", Gravity.TOP or Gravity.END)
        corner("Help", Gravity.BOTTOM or Gravity.START)
        frame.addView(
            EditText(this).apply {
                hint = "Search settings"
                inputType = InputType.TYPE_CLASS_TEXT
                setBackgroundColor(Color.rgb(236, 238, 246))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.CENTER_VERTICAL),
        )
        setContentView(frame)
    }

    /** The one button needed sits exactly where a card at the bottom would. */
    private fun under() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(240, 242, 245)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        column.title("Share your first post")
        column.body("Your post: “Hello from my new phone!”")
        val posted = column.body("")
        frame.addView(column)
        frame.addView(
            Button(this).apply {
                text = "Post"
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(8, 102, 255), 24, Color.rgb(8, 102, 255))
                // Nothing is posted anywhere: this is a test screen.
                setOnClickListener { posted.text = "Posted (on this test screen only)." }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM).apply {
                setMargins(dp(16), 0, dp(16), dp(40))
            },
        )
        setContentView(frame)
    }

    /**
     * A page between steps: nothing but a spinner, as a sign-up flow shows
     * while it fetches its next page. With [finishes], the form arrives after
     * five seconds; without, it never does.
     */
    private fun loading(finishes: Boolean) {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(
            android.widget.ProgressBar(this),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER),
        )
        setContentView(frame)
        if (finishes) handler.postDelayed({ signupName() }, 5_000)
    }

    /** A control that runs nearly the whole height of the screen. */
    private fun tall() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(TextView(this).apply { text = "Sketch pad"; textSize = 16f; setPadding(dp(16), dp(8), 0, 0) })
        frame.addView(TextView(this).apply { text = "Use one finger"; textSize = 14f; setPadding(dp(16), 0, 0, dp(8)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        frame.addView(
            Button(this).apply {
                text = "Draw here"
                isAllCaps = false
                background = rounded(Color.rgb(160, 165, 175), 16, Color.rgb(236, 238, 246))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(16), dp(40), dp(16), dp(40))
            },
        )
        setContentView(frame)
    }

    private fun card() {
        val (root, column) = page()
        column.title("Messages")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(205, 208, 213), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            contentDescription = "Message from Sam"
            addView(TextView(context).apply { text = "Message from Sam"; textSize = 18f; setTextColor(Color.BLACK) })
            addView(TextView(context).apply { text = "See you at lunch tomorrow?"; textSize = 16f })
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Reply", "Share").forEach { buttons.addView(Button(context).apply { text = it; isAllCaps = false }) }
            addView(buttons)
        }
        column.addView(card)
        setContentView(root)
    }
}
