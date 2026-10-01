package com.aaravsekhri.dot.guide.apps

import android.content.Context
import android.content.pm.PackageManager
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.KnownApp
import com.aaravsekhri.dot.courses.AiCourses
import com.aaravsekhri.dot.courses.CreativeCourses
import com.aaravsekhri.dot.courses.MoreCourses
import com.aaravsekhri.dot.guide.ai.CandidateControl
import com.aaravsekhri.dot.guide.ai.InstallState
import com.aaravsekhri.dot.guide.ai.Observation
import com.aaravsekhri.dot.guide.ai.TargetApp
import com.aaravsekhri.dot.guide.matching.TextNormalizer

/**
 * Whether an app is on the phone, as far as Android lets Dot see.
 *
 * From Android 11, an app is shown only the other apps it declares in its
 * manifest's `<queries>`. Dot declares exactly the apps its courses are about
 * — not `QUERY_ALL_PACKAGES`. For those, a failed lookup means "not found";
 * for any other app, it means nothing at all: Android is hiding it, and the
 * screen has to be looked at instead.
 */
object InstalledApps {

    /** What one package-manager lookup came back as. */
    sealed interface Lookup {
        data class Found(val enabled: Boolean, val label: String) : Lookup
        data object NotFound : Lookup
    }

    /** Every package Dot declares it may look up. Mirrors the manifest's `<queries>`. */
    val declared: Set<String> by lazy { CourseCatalog.apps.map { it.packageName }.toSet() + PLAY_STORE }

    const val PLAY_STORE = "com.android.vending"

    /**
     * Pure: a lookup, and whether the package was declared, as an install
     * state. Not found is only "not found" when Android was allowed to say.
     */
    fun stateOf(packageName: String, lookup: Lookup): InstallState = when (lookup) {
        is Lookup.Found -> if (lookup.enabled) InstallState.Installed else InstallState.Disabled
        Lookup.NotFound -> if (packageName in declared) InstallState.NotInstalled else InstallState.Unknown
    }

    fun lookUp(context: Context, packageName: String): Lookup = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        Lookup.Found(info.enabled, pm.getApplicationLabel(info).toString())
    } catch (_: PackageManager.NameNotFoundException) {
        Lookup.NotFound
    } catch (_: Exception) {
        Lookup.NotFound
    }

    /** [app] resolved against this phone. */
    fun resolve(context: Context, app: KnownApp): TargetApp {
        val lookup = lookUp(context, app.packageName)
        return TargetApp(
            name = app.name,
            packageName = app.packageName,
            publisher = app.publisher,
            installState = stateOf(app.packageName, lookup),
            label = (lookup as? Lookup.Found)?.label?.takeIf(String::isNotBlank) ?: app.label,
        )
    }
}

/**
 * Which app a request typed in Ask is about, when that is plain.
 *
 * Only the apps Dot knows well, and only unambiguous names: "messages" could
 * be Google Messages or the phone's own texting app, and "calendar" could be
 * any of several, so those are left for the guide to ask about rather than
 * guessed.
 */
object AppDirectory {

    private data class Name(val words: String, val app: KnownApp)

    private val names: List<Name> by lazy {
        val c = CourseCatalog
        listOf(
            Name("chatgpt", c.chatGpt.app),
            Name("chat gpt", c.chatGpt.app),
            Name("whatsapp", c.whatsApp.app),
            Name("whats app", c.whatsApp.app),
            Name("facebook", c.facebook.app),
            Name("google photos", c.photos.app),
            Name("google maps", c.maps.app),
            Name("gmail", c.gmail.app),
            Name("youtube", c.youTube.app),
            Name("google calendar", c.calendar.app),
            Name("chrome", c.chrome.app),
            Name("google messages", c.messages.app),
            Name("google keep", MoreCourses.keep.app),
            Name("keep notes", MoreCourses.keep.app),
            Name("google translate", MoreCourses.translate.app),
            Name("google meet", MoreCourses.meet.app),
            Name("spotify", MoreCourses.spotify.app),
            Name("google drive", MoreCourses.drive.app),
            Name("files by google", MoreCourses.files.app),
            Name("google lens", MoreCourses.lens.app),
            Name("claude", AiCourses.claude.app),
            Name("gemini", AiCourses.gemini.app),
            Name("google gemini", AiCourses.gemini.app),
            Name("perplexity", AiCourses.perplexity.app),
            Name("lightroom", CreativeCourses.lightroom.app),
            Name("adobe lightroom", CreativeCourses.lightroom.app),
            Name("canva", CreativeCourses.canva.app),
        )
    }

    /** The one known app [goal] names, or null when it names none or several. */
    fun resolve(goal: String): KnownApp? {
        val text = " ${TextNormalizer.normalize(goal)} "
        val found = names.filter { " ${it.words} " in text }.map { it.app }.distinct()
        // "Facebook Lite" and "WhatsApp Business" are other apps: not guessed.
        if (" lite " in text || " business " in text) return null
        return found.singleOrNull()
    }
}

/**
 * Telling the installed app apart in a launcher's search results.
 *
 * Launcher search mixes the phone's own apps with web searches, Play Store
 * suggestions, adverts and apps with similar names. Only the installed app
 * itself — its exact name, as a plain tappable result — opens it. This is a
 * check on the planner's choice, never a replacement for reading the screen:
 * it only ever moves the highlight from something that is clearly not the
 * installed app to the one result that clearly is.
 */
object LauncherResults {

    enum class Kind { InstalledApp, SimilarApp, WebSearch, Store, Other }

    /** Whole words and phrases that mark a web search result. */
    private val WEB = listOf("search the web", "search google", "google search", "web results", "http", "https", "www", "com")

    /** Whole words and phrases that mark a store suggestion or an advert. */
    private val STORE = listOf("install", "play store", "google play", "get app", "download", "sponsored", "ad", "rated", "stars")

    /** What [control] is, as a result for [app]. */
    fun kindOf(control: CandidateControl, app: TargetApp): Kind {
        if (control.editable) return Kind.Other
        val words = " " + TextNormalizer.normalize(control.naming + " " + control.description) + " "
        fun says(phrase: String) = " $phrase " in words
        val name = TextNormalizer.normalize(control.naming)
        val names = setOf(TextNormalizer.normalize(app.label), TextNormalizer.normalize(app.name)).filter(String::isNotBlank)
        if (STORE.any(::says) || '★' in control.naming || '★' in control.description) return Kind.Store
        if (WEB.any(::says)) return Kind.WebSearch
        if (name in names) return Kind.InstalledApp
        val squashed = name.replace(" ", "")
        if (names.any { it in name || it.replace(" ", "") in squashed }) return Kind.SimilarApp
        return Kind.Other
    }

    /**
     * The installed app's result, when there is exactly one clear one.
     * Null when there is none, or more than one and so no telling which.
     */
    fun installedResult(observation: Observation, app: TargetApp): CandidateControl? =
        observation.candidates
            .filter { it.clickable && it.enabled && kindOf(it, app) == Kind.InstalledApp }
            .singleOrNull()

    /**
     * On a launcher, a highlight on a web search, a store suggestion or a
     * look-alike is moved to the installed app's own result — but only when
     * that result is unambiguous. Anything else is left as the planner chose.
     *
     * @return the candidate id to highlight instead, or null to keep the choice.
     */
    fun correct(chosenId: String, observation: Observation, app: TargetApp?): String? {
        if (app == null || !observation.homeScreen) return null
        val chosen = observation.candidate(chosenId) ?: return null
        val kind = kindOf(chosen, app)
        if (kind == Kind.InstalledApp || kind == Kind.Other) return null
        val installed = installedResult(observation, app) ?: return null
        return installed.id.takeIf { it != chosenId }
    }
}
