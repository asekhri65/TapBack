package com.aaravsekhri.dot.experiment

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A dummy sign-in, for testing Dot's sign-in help on a device. Debug builds only.
 *
 * Not a real service: no account exists, nothing typed goes anywhere, and
 * every page accepts anything. Built from ordinary Android views so the
 * accessibility tree is what a real app's would be — an email box declared
 * as email, a password box declared as a password, a short numeric code box.
 *
 * Pages: email → password → code → signed in. `--es page protected` shows a
 * window marked secure whose box is hidden from accessibility, the way some
 * banking apps draw theirs.
 */
// A test screen in English only, like the rest of the debug fixtures.
@android.annotation.SuppressLint("SetTextI18n")
class FixtureSignInActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(intent.getStringExtra("page") ?: "email")
    }

    private fun show(page: String) {
        if (page == "protected") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 220, 64, 64)
            setBackgroundColor(Color.WHITE)
        }
        fun title(words: String) = column.addView(
            TextView(this).apply {
                text = words
                textSize = 26f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, 48)
            },
        )
        fun box(hint: String, type: Int, max: Int = 0) = EditText(this).apply {
            this.hint = hint
            inputType = type
            textSize = 20f
            if (max > 0) filters = arrayOf(InputFilter.LengthFilter(max))
        }.also { column.addView(it) }
        fun next(label: String, then: String) = column.addView(
            Button(this).apply {
                text = label
                setOnClickListener { show(then) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = 48
            },
        )

        when (page) {
            "email" -> {
                title("Sign in to Example")
                box("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                next("Next", "password")
            }
            "password" -> {
                title("Welcome back")
                box("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                next("Next", "code")
            }
            "code" -> {
                title("Enter the 6-digit code we sent to your phone")
                box("", InputType.TYPE_CLASS_NUMBER, max = 6)
                next("Verify", "done")
            }
            "protected" -> {
                title("Secure sign-in")
                // Drawn, but hidden from accessibility: Dot cannot see it.
                column.addView(
                    View(this).apply {
                        setBackgroundColor(Color.rgb(235, 235, 235))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160),
                )
                next("Continue", "done")
            }
            else -> {
                title("You're signed in to Example")
                column.addView(TextView(this).apply { text = "Your Example account is ready."; textSize = 18f })
            }
        }
        setContentView(column)
    }
}
