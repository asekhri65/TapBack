package com.aaravsekhri.dot.experiment

import com.aaravsekhri.dot.courses.Course
import com.aaravsekhri.dot.courses.CourseArt
import com.aaravsekhri.dot.courses.KnownApp
import com.aaravsekhri.dot.courses.Lesson

/**
 * A lesson over the test screens in [FixtureAppsActivity], for exercising the
 * course engine end to end without a real account. Debug builds only; never
 * in the catalogue. The "app" is a made-up package, so the phone cannot find
 * it and the guide has to read the screen, as it would for a real app Android
 * hides.
 */
object FixtureCourse {
    val course = Course(
        id = "fixture-social",
        version = 1,
        title = "Example Social (test)",
        summary = "A test course over Dot's own fixture screens.",
        art = CourseArt.Post,
        app = KnownApp(name = "Example Social", packageName = "com.example.social", publisher = "Example Inc."),
        lessons = listOf(
            Lesson(
                id = "fixture-social.setup",
                title = "Get set up",
                explanation = "Install Example Social and get to its sign-in or welcome screen.",
                objective = "Install Example Social from the store page, open it, and reach its sign-in screen. Ask whether the learner already has an account before guiding sign-in or sign-up.",
                doneWhen = "Example Social is open on its sign-in screen or its welcome screen.",
                rules = listOf("This is a test app: no real account exists."),
            ),
        ),
    )

    fun lesson(id: String): Pair<Course, Lesson> = course to (course.lessons.firstOrNull { it.id == id } ?: course.lessons.first())
}
