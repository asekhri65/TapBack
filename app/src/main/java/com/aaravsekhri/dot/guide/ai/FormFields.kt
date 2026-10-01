package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer

/**
 * What a form's box is for, told from the words the app gives it — its
 * placeholder, its label, its description — never from what is in it.
 *
 * Used to say exactly what a step asks ("I'm done typing my first name"),
 * and to tell a birthday from any other box. [Unknown] when the words do not
 * say; the wording is then generic rather than guessed.
 */
enum class FieldKind {
    FirstName, LastName, FullName, Email, Phone, EmailOrPhone, Username,
    Password, NewPassword, Birthday, Code, Address, Search, Message, Unknown,
}

/**
 * How ready a form's box is, as far as the phone can honestly tell.
 *
 * Android says whether a box has something in it, whether that is its
 * placeholder, and — when the app sets one — its error. It does not say
 * whether a value was put there by the app or chosen by the person, so that
 * is only ever known from what Dot itself watched: a value that changed
 * while Dot was looking, or that the person confirmed on Dot's card.
 */
enum class Readiness(val wire: String) {
    /** Nothing in it but, at most, its placeholder. */
    Empty("empty"),

    /** Something was in it when Dot first saw it — often the app's default — and nobody has confirmed it. */
    Prefilled("prefilled"),

    /** Changed while Dot watched, or confirmed by the person on Dot's card. */
    Confirmed("confirmed"),

    /** The app itself flags a problem with it. */
    Error("error"),

    /** Not a box, or nothing can be said. */
    Unknown(""),
}

object FormFields {

    /** What [control]'s own words say it is for. */
    fun kindOf(control: CandidateControl): FieldKind = kindOf(control.hint + " " + control.description + " " + if (control.editable) "" else control.label)

    /** What a box named [words] is for. */
    fun kindOf(words: String): FieldKind {
        val w = " " + TextNormalizer.normalize(words) + " "
        fun has(vararg phrases: String) = phrases.any { " $it " in w }
        return when {
            has("birthday", "date of birth", "birth date", "birthdate", "dob", "born") -> FieldKind.Birthday
            has("new password", "create password", "create a password", "choose a password", "confirm password") -> FieldKind.NewPassword
            has("password", "passcode") -> FieldKind.Password
            has("code", "verification code", "security code", "otp", "pin") -> FieldKind.Code
            has("first name", "given name", "forename") -> FieldKind.FirstName
            has("last name", "surname", "family name") -> FieldKind.LastName
            (has("email", "e mail") && has("phone", "mobile", "number")) -> FieldKind.EmailOrPhone
            has("email", "e mail", "email address") -> FieldKind.Email
            has("phone", "mobile", "mobile number", "phone number") -> FieldKind.Phone
            has("username", "user name") -> FieldKind.Username
            has("full name", "your name", "name") -> FieldKind.FullName
            has("address", "street", "postcode", "zip") -> FieldKind.Address
            has("search") -> FieldKind.Search
            has("message", "write a message", "type a message", "comment", "what s on your mind", "reply") -> FieldKind.Message
            else -> FieldKind.Unknown
        }
    }

    /**
     * The words on the button that says typing is finished, from what the
     * box is for. Never anything that was typed: only what kind of box it is.
     */
    fun doneTypingLabel(kind: FieldKind?): String = when (kind) {
        FieldKind.FirstName -> "I’m done typing my first name"
        FieldKind.LastName -> "I’m done typing my last name"
        FieldKind.FullName -> "I’m done typing my name"
        FieldKind.Email -> "I’m done typing my email"
        FieldKind.Phone -> "I’m done typing my phone number"
        FieldKind.EmailOrPhone -> "I’m done typing my email or number"
        FieldKind.Username -> "I’m done typing my username"
        FieldKind.Password -> "I’m done typing my password"
        FieldKind.NewPassword -> "I’m done creating my password"
        FieldKind.Birthday -> "I’ve set my birthday"
        FieldKind.Code -> "I’m done typing the code"
        FieldKind.Address -> "I’m done typing the address"
        FieldKind.Message -> "I’m done typing my message"
        FieldKind.Search, FieldKind.Unknown, null -> "I’m done typing"
    }

    /** A button that moves a form on: the thing that should not be pressed before the form is ready. */
    fun isAdvance(control: CandidateControl): Boolean {
        if (!control.clickable || control.editable) return false
        val n = TextNormalizer.normalize(control.naming)
        return n in ADVANCE
    }

    /** A box, or a picker's box, that holds a birthday. */
    fun isBirthday(control: CandidateControl): Boolean =
        (control.editable || control.role.contains("Picker")) && kindOf(control) == FieldKind.Birthday

    /**
     * A field's key for this session: which app, what kind of box, what the
     * app calls it. Enough to recognise the same box on a later reading;
     * carries no value.
     */
    fun key(packageName: String, control: CandidateControl, occurrence: Int): String =
        "$packageName|${control.role}|${TextNormalizer.normalize(control.hint.ifBlank { control.description })}|$occurrence"

    /**
     * The app telling the person their age from the date it holds — "(0 years
     * old)" — which a date nobody has set shows as nought. Evidence the date
     * shown is a default.
     */
    fun showsAgeZero(texts: List<String>): Boolean = texts.any { AGE_ZERO.containsMatchIn(it) }

    private val AGE_ZERO = Regex("(?i)\\b0\\s+years?\\s+old\\b")

    private val ADVANCE = setOf(
        "next", "continue", "submit", "done", "save", "confirm", "sign up", "join", "create account",
        "create new account", "agree", "i agree", "finish", "register", "send",
    )
}

/**
 * What Dot has watched of a form's boxes during one session: for each box, a
 * fingerprint of what was in it when first seen and now, and whether the
 * person confirmed it. Fingerprints only — a value is never kept, logged or
 * sent, and a password box is never read at all.
 */
class FormMemory {

    /** [first] and [last] are null for an empty box; [edited] once it has changed while watched. */
    private class Seen(val first: Int?, var last: Int?, var edited: Boolean = false, var confirmed: Boolean = false)

    private val fields = HashMap<String, Seen>()

    /**
     * How ready a box is, and remembers it for next time.
     *
     * @param fingerprint of what is in it, or null when it is empty or only
     *   shows its placeholder.
     * @param error the app's own error on it, if it set one.
     */
    fun readiness(key: String, fingerprint: Int?, error: Boolean, editedIsEnough: Boolean = true): Readiness {
        val seen = fields.getOrPut(key) { Seen(fingerprint, fingerprint) }
        if (seen.last != fingerprint) {
            seen.last = fingerprint
            seen.edited = true
            // A confirmation was for the value confirmed, not for whatever
            // the box holds next.
            seen.confirmed = false
        }
        return when {
            error -> Readiness.Error
            fingerprint == null -> Readiness.Empty
            seen.confirmed || (seen.edited && editedIsEnough) -> Readiness.Confirmed
            else -> Readiness.Prefilled
        }
    }

    fun isConfirmed(key: String): Boolean = fields[key]?.confirmed == true

    /** The person said what is in [key] is right. */
    fun confirm(key: String) {
        fields[key]?.confirmed = true
    }

    fun reset() = fields.clear()
}

/**
 * Whether a search result can be the address searched for, by its numbers
 * alone. Streets and towns are written many ways; a house number is not. A
 * result that shows numbers, none of them the one searched for, is not it.
 */
object AddressMatch {
    private val NUMBER = Regex("\\b\\d+[A-Za-z]?\\b")

    fun contradicts(result: String, searched: String): Boolean {
        val wantedNumber = NUMBER.find(searched)?.value?.lowercase() ?: return false
        val shown = NUMBER.findAll(result).map { it.value.lowercase() }.toList()
        return shown.isNotEmpty() && wantedNumber !in shown
    }
}
