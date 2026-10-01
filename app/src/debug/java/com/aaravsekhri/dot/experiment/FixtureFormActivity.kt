package com.aaravsekhri.dot.experiment

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Calendar

/**
 * Dummy screens that reproduce what TapBack's guide met in real apps, with
 * made-up data and nothing sent anywhere. Debug builds only.
 *
 * Pages (`--es page …`):
 * - `signin`: a Sign in button that turns the same window into "Checking
 *   info…" and then a signed-in page, as Google's sign-in does.
 * - `welcome`: a large picture with Get started and Skip pinned to the bottom
 *   edge, as a store's first-run pages have them.
 * - `birthday`: a birthday box showing today's date, a spinner date picker with
 *   Set and Cancel, an under-13 date refused with an error, then a done page.
 * - `permission`: a button that asks Android for location, raising the real
 *   system permission dialog. Nothing is done with any answer.
 * - `editor`: an "Add text" page with a Done button at the top and a large
 *   box to type a post into; nothing is posted.
 */
// A test screen in English only, like the rest of the debug fixtures.
@android.annotation.SuppressLint("SetTextI18n")
class FixtureFormActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var birthday: Calendar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window's title is how TapBack tells its own test screen from
        // itself, and what it calls the app: "Example Store".
        title = "Example Store (test)"
        show(intent.getStringExtra("page") ?: "welcome")
    }

    private fun column(top: Int = 180): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(56, top, 56, 56)
        setBackgroundColor(Color.WHITE)
    }

    private fun LinearLayout.title(words: String) = addView(
        TextView(context).apply {
            text = words
            textSize = 26f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 32)
        },
    )

    private fun LinearLayout.body(words: String, color: Int = Color.DKGRAY) = TextView(context).apply {
        text = words
        textSize = 17f
        setTextColor(color)
        setPadding(0, 0, 0, 24)
    }.also { addView(it) }

    private fun blueButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 80f; setColor(Color.rgb(8, 102, 255)) }
        setOnClickListener { onClick() }
    }

    private fun show(page: String) {
        when (page) {
            "signin" -> signIn()
            "welcome" -> welcome()
            "birthday" -> birthdayPage()
            "permission" -> permission()
            "editor" -> editor()
            else -> welcome()
        }
    }

    // ------------------------------------------------------------ sign in

    private fun signIn() {
        val c = column()
        c.title("Sign in to Example")
        c.body("Use your Example account. This is a test screen; nothing is sent.")
        c.addView(blueButton("Sign in") { checking() })
        c.addView(Button(this).apply { text = "Create account"; isAllCaps = false })
        setContentView(c)
    }

    /** The same window, a different page with nothing to tap: as a sign-in checks. */
    private fun checking() {
        val c = column(top = 240)
        c.body("Checking info…", Color.BLACK).textSize = 24f
        setContentView(c)
        main.postDelayed({
            val done = column()
            done.title("You're signed in")
            done.body("Welcome to Example.")
            done.addView(blueButton("Continue") { finish() })
            setContentView(done)
        }, 6_000)
    }

    // ------------------------------------------------------------ welcome

    private fun welcome(step: Int = 0) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val top = column(top = 200)
        top.addView(
            View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.rgb(250, 190, 40)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700)
            },
        )
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 60) })
        if (step == 0) {
            top.title("Welcome to Example Store")
            top.body("Find apps and games for your phone.")
        } else {
            top.title("What are you into?")
            top.body("Choose some categories, or skip this for now.")
        }
        root.addView(top)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 24, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        if (step == 0) {
            bar.addView(blueButton("Get started") { welcome(1) }, LinearLayout.LayoutParams(0, 150, 1f))
        } else {
            bar.addView(
                Button(this).apply { text = "Skip"; isAllCaps = false; setOnClickListener { welcomeDone() } },
                LinearLayout.LayoutParams(0, 150, 1f),
            )
            bar.addView(View(this), LinearLayout.LayoutParams(40, 1))
            bar.addView(blueButton("Next") { welcomeDone() }, LinearLayout.LayoutParams(0, 150, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun welcomeDone() {
        val c = column()
        c.title("Example Store")
        c.body("Top apps: Example Notes, Example Maps, Example Music.")
        setContentView(c)
    }

    // ------------------------------------------------------------ birthday

    private fun age(of: Calendar): Int {
        val now = Calendar.getInstance()
        var years = now.get(Calendar.YEAR) - of.get(Calendar.YEAR)
        if (now.get(Calendar.DAY_OF_YEAR) < of.get(Calendar.DAY_OF_YEAR)) years--
        return years.coerceAtLeast(0)
    }

    private fun birthdayPage(error: Boolean = false) {
        val shown = birthday ?: Calendar.getInstance()
        val c = column()
        c.title("What's your birthday?")
        c.body("Choose your date of birth. This is a test screen; nothing is kept.")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setStroke(4, if (error) Color.rgb(200, 30, 30) else Color.GRAY)
                setColor(Color.WHITE)
            }
            addView(TextView(context).apply { text = "Birthday (${age(shown)} years old)"; textSize = 14f })
            addView(TextView(context).apply { text = DateFormat.getDateInstance(DateFormat.LONG).format(shown.time); textSize = 20f; setTextColor(Color.BLACK) })
            setOnClickListener { openPicker() }
        }
        c.addView(box)
        if (error) {
            c.body("It looks like you entered the wrong info. Please be sure to use your real birthday.", Color.rgb(200, 30, 30))
        }
        c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        c.addView(
            blueButton("Next") {
                val chosen = birthday
                if (chosen == null || age(chosen) < 13) birthdayPage(error = true) else birthdayDone()
            },
        )
        setContentView(c)
    }

    private fun openPicker() {
        val start = birthday ?: Calendar.getInstance()
        DatePickerDialog(
            this,
            android.R.style.Theme_Holo_Light_Dialog,
            { _, y, m, d -> birthday = Calendar.getInstance().apply { set(y, m, d) }; birthdayPage() },
            start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH),
        ).apply {
            setTitle("Set date")
            datePicker.calendarViewShown = false
            datePicker.spinnersShown = true
        }.show()
    }

    private fun birthdayDone() {
        val c = column()
        c.title("Choose a password")
        c.body("The next page of the made-up sign-up.")
        setContentView(c)
    }

    // ------------------------------------------------------------ permission

    private fun permission() {
        val c = column()
        c.title("Find places near you")
        c.body("Example can show places near you if you share your location. You can say no.")
        c.addView(blueButton("Share location") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        })
        c.addView(Button(this).apply { text = "Continue"; isAllCaps = false; setOnClickListener { welcomeDone() } })
        setContentView(c)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val c = column()
        c.title("Thanks")
        c.body("Your choice was saved by Android. This test screen does nothing with it.")
        c.addView(blueButton("Continue") { welcomeDone() })
        setContentView(c)
    }

    // ------------------------------------------------------------ editor

    private fun editor() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = FrameLayout(this).apply { setPadding(32, 110, 32, 16) }
        bar.addView(TextView(this).apply { text = "Add text"; textSize = 20f; setTextColor(Color.BLACK) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(
            Button(this).apply {
                text = "Done"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@FixtureFormActivity).setMessage("Nothing was posted. This is a test screen.").setPositiveButton("OK", null).show()
                }
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL),
        )
        root.addView(bar)
        root.addView(
            EditText(this).apply {
                hint = "What's on your mind?"
                textSize = 22f
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                setPadding(48, 24, 48, 24)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        setContentView(root)
    }
}
