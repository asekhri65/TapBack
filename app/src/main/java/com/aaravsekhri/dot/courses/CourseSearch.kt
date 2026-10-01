package com.aaravsekhri.dot.courses

/**
 * "Find a course", on the phone: the words someone types, matched against
 * each course's title, app, summary, category and lessons — and the everyday
 * words people use for the same things, so "pictures" finds the photo
 * courses and "wallpaper" finds the home screen one.
 *
 * Every word typed has to match somewhere (a search narrows as it grows),
 * and a match in a title counts for more than one in a lesson's explanation.
 * Nothing leaves the phone.
 */
object CourseSearch {

    /** A course that matched, and the lesson that matched when the course's own words did not. */
    data class Hit(val course: Course, val score: Double, val lesson: Lesson?)

    fun search(courses: List<Course>, query: String): List<Hit> {
        val words = terms(query)
        if (words.isEmpty()) return emptyList()
        val all = courses.mapIndexedNotNull { index, course -> score(course, words)?.let { index to it } }
            .sortedWith(compareByDescending<Pair<Int, Hit>> { it.second.score }.thenBy { it.first })
            .map { it.second }
        // Where some courses match on their names, lessons or summaries, the
        // ones that only mention the word in passing are left out.
        val strong = all.filter { it.score >= words.size * STRONG }
        return strong.ifEmpty { all }
    }

    /** The words that count: lower case, without punctuation, plurals and filler. */
    internal fun terms(query: String): List<String> {
        val all = words(query)
        return all.filterNot { it in FILLER }.ifEmpty { all }.distinct()
    }

    private fun words(text: String): List<String> =
        text.lowercase()
            .replace('’', '\'')
            .replace("'s", "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .map(::stem)

    /** "photos" → "photo", "messages" → "message": enough to meet in the middle, nothing more. */
    internal fun stem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    private class Field(val words: Set<String>, val weight: Double)

    private fun score(course: Course, terms: List<String>): Hit? {
        val lessons = course.lessons.map { it to words(it.title + " " + it.learned).toSet() }
        val fields = listOf(
            Field(words(course.title).toSet(), 5.0),
            Field(words(course.app.name + " " + course.app.label).toSet(), 5.0),
            Field(words(course.summary).toSet(), 3.0),
            Field(words(CATEGORY_WORDS.getValue(course.category)).toSet(), 2.0),
            Field(lessons.flatMap { it.second }.toSet(), 2.0),
            Field(words(course.lessons.joinToString(" ") { it.explanation + " " + it.purpose }).toSet(), 1.0),
        )
        var total = 0.0
        for (term in terms) {
            val direct = fields.maxOf { f -> if (f.words.any { matches(it, term) }) f.weight else 0.0 }
            val related = (SYNONYMS[term] ?: emptyList()).flatMap(::words)
            // A related word in a course's name is as good as the word itself;
            // further down, it counts for a little less.
            val viaSynonym = related.maxOfOrNull { r ->
                fields.maxOf { f -> if (f.words.any { matches(it, r) }) (if (f.weight >= 5.0) f.weight else f.weight * 0.8) else 0.0 }
            } ?: 0.0
            val best = maxOf(direct, viaSynonym)
            if (best == 0.0) return null
            total += best
        }
        // The course's own words matched well: no need to point at a lesson.
        val strong = terms.all { t ->
            val alike = listOf(t) + (SYNONYMS[t] ?: emptyList()).flatMap(::words)
            fields.take(3).any { f -> f.words.any { w -> alike.any { matches(w, it) } } }
        }
        val lesson = if (strong) null else lessons.firstOrNull { (_, w) ->
            terms.any { t -> w.any { matches(it, t) } || (SYNONYMS[t] ?: emptyList()).flatMap(::words).any { r -> w.any { matches(it, r) } } }
        }?.first
        return Hit(course, total, lesson)
    }

    /** A whole word, or the start of one once three letters are typed: "pho" finds "photo". */
    private fun matches(word: String, term: String): Boolean =
        word == term || (term.length >= 3 && word.startsWith(term))

    /** Per word: at least a lesson's title, not only a passing mention. */
    private const val STRONG = 2.0

    private val FILLER = setOf(
        "a", "an", "the", "to", "how", "my", "me", "on", "in", "of", "for", "with", "and", "or", "i", "do", "use",
        "using", "learn", "course", "lesson", "app", "phone", "want", "can", "help", "about", "some", "new",
    )

    private val CATEGORY_WORDS = mapOf(
        CourseCategory.EverydayEssentials to "everyday essentials",
        CourseCategory.StayConnected to "stay connected family friends",
        CourseCategory.PhotosAndCreativity to "photos creativity create",
        CourseCategory.AiTools to "ai tools artificial intelligence",
        CourseCategory.MakeItYours to "make your phone yours settings personalise",
    )

    /** Everyday words, stemmed, for what the courses call something else. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "picture" to listOf("photo", "image", "camera"),
        "pic" to listOf("photo"),
        "image" to listOf("photo"),
        "snap" to listOf("photo", "camera"),
        "selfie" to listOf("camera", "photo"),
        "album" to listOf("photo"),
        "edit" to listOf("lightroom", "crop", "brightness"),
        "brighten" to listOf("brightness", "light"),
        "filter" to listOf("lightroom", "photo"),
        "video" to listOf("youtube", "meet", "camera"),
        "film" to listOf("youtube", "camera"),
        "movie" to listOf("youtube"),
        "watch" to listOf("youtube"),
        "music" to listOf("spotify"),
        "song" to listOf("spotify"),
        "podcast" to listOf("spotify"),
        "email" to listOf("gmail"),
        "mail" to listOf("gmail"),
        "sms" to listOf("message"),
        "text" to listOf("message"),
        "chat" to listOf("whatsapp", "message"),
        "call" to listOf("phone", "meet", "whatsapp"),
        "ring" to listOf("phone"),
        "dial" to listOf("phone"),
        "number" to listOf("contact", "phone"),
        "friend" to listOf("contact", "whatsapp", "facebook"),
        "family" to listOf("whatsapp", "facebook", "contact"),
        "direction" to listOf("map"),
        "navigate" to listOf("map"),
        "route" to listOf("map"),
        "place" to listOf("map"),
        "address" to listOf("map"),
        "internet" to listOf("chrome", "wi"),
        "web" to listOf("chrome"),
        "website" to listOf("chrome"),
        "browser" to listOf("chrome"),
        "ai" to listOf("chatgpt", "claude", "gemini", "perplexity"),
        "chatbot" to listOf("chatgpt", "claude", "gemini", "perplexity"),
        "assistant" to listOf("gemini", "chatgpt", "claude"),
        "question" to listOf("chatgpt", "claude", "gemini", "perplexity"),
        "alarm" to listOf("clock"),
        "timer" to listOf("clock"),
        "reminder" to listOf("calendar", "keep", "clock"),
        "appointment" to listOf("calendar"),
        "event" to listOf("calendar"),
        "schedule" to listOf("calendar"),
        "birthday" to listOf("calendar", "invitation"),
        "party" to listOf("invitation"),
        "note" to listOf("keep"),
        "list" to listOf("keep"),
        "document" to listOf("files", "drive"),
        "pdf" to listOf("files", "drive"),
        "storage" to listOf("files", "drive"),
        "space" to listOf("files"),
        "backup" to listOf("drive", "photo"),
        "cloud" to listOf("drive"),
        "install" to listOf("play", "store"),
        "download" to listOf("play", "store", "files"),
        "update" to listOf("play", "store"),
        "alert" to listOf("notification"),
        "wifi" to listOf("wi", "connect"),
        "bluetooth" to listOf("connect"),
        "headphone" to listOf("connect", "bluetooth"),
        "font" to listOf("text", "reading"),
        "bigger" to listOf("text", "reading"),
        "larger" to listOf("text", "reading"),
        "eye" to listOf("reading", "dark"),
        "wallpaper" to listOf("home", "screen"),
        "background" to listOf("wallpaper"),
        "theme" to listOf("wallpaper", "dark", "colour"),
        "icon" to listOf("home", "screen"),
        "color" to listOf("colour"),
        "homescreen" to listOf("home", "screen"),
        "personalize" to listOf("home", "screen", "personalise"),
        "customize" to listOf("home", "screen", "personalise"),
        "customise" to listOf("home", "screen", "personalise"),
        "invite" to listOf("invitation", "canva"),
        "card" to listOf("invitation", "canva"),
        "poster" to listOf("canva", "design"),
        "flyer" to listOf("canva", "design"),
        "graphic" to listOf("canva", "design"),
        "language" to listOf("translate"),
        "foreign" to listOf("translate"),
        "identify" to listOf("lens"),
        "plant" to listOf("lens"),
        "scan" to listOf("lens", "files"),
        "meeting" to listOf("meet"),
        "post" to listOf("facebook"),
        "social" to listOf("facebook"),
    ).mapKeys { stem(it.key) }
}
