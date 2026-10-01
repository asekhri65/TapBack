package com.aaravsekhri.dot.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.CourseProgressStore
import com.aaravsekhri.dot.courses.CourseSummary
import com.aaravsekhri.dot.courses.Lesson
import com.aaravsekhri.dot.courses.LessonStatus
import com.aaravsekhri.dot.ui.components.PreferredAction
import com.aaravsekhri.dot.ui.components.PreferredActionHeadroom
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.dotClickable
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * One course: what it is, how far along it is, its lessons, and one button.
 *
 * Any lesson can be opened — the order is a suggestion, not a gate. Choosing
 * one shows its short explanation and makes the button about it; the button
 * is the only recommended action on the page. A finished lesson can be
 * reviewed without being undone.
 */
@Composable
fun CourseDetailScreen(
    summary: CourseSummary,
    onBack: () -> Unit,
    onStartLesson: (lessonId: String) -> Unit,
    onSkipLesson: (lessonId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Practise this lesson again from its first stage. */
    onRestartLesson: (lessonId: String) -> Unit = {},
    /** Practise the whole course again from its first lesson. */
    onRestartCourse: () -> Unit = {},
    /** Part of Dot Plus, which this person does not have: said, and starting offers Plus. */
    locked: Boolean = false,
) {
    val course = summary.course
    var chosenId by rememberSaveable(course.id) { mutableStateOf<String?>(null) }
    val chosen: Lesson = course.lessons.firstOrNull { it.id == chosenId } ?: summary.current ?: course.lessons.first()
    val chosenStatus = summary.statuses[chosen.id] ?: LessonStatus.NotStarted

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DotDimens.Gutter),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.back_to_learn),
            style = DotType.TextAction,
            color = DotColors.Muted,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .heightIn(min = DotDimens.MinTouchTarget)
                .dotClickable(onClick = onBack, role = Role.Button, shape = RoundedCornerShape(10.dp))
                .padding(vertical = 12.dp, horizontal = 2.dp),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = course.title,
                style = DotType.ScreenTitle,
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            CourseIllustration(course.art, size = 64.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(text = course.summary, style = DotType.CardBody, color = DotColors.Muted)
        if (locked) {
            Spacer(Modifier.height(10.dp))
            PlusTag()
            Spacer(Modifier.height(6.dp))
            Text(text = stringResource(R.string.plus_locked_course_note), style = DotType.CardMeta, color = DotColors.Muted)
        }
        Spacer(Modifier.height(18.dp))
        LessonProgress(summary)

        Spacer(Modifier.height(24.dp))

        // What the button is about, in a line or two, just above it.
        val (fill, border) = courseColours(course.art)
        val shape = RoundedCornerShape(DotDimens.FeaturedRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(fill, shape)
                .border(1.dp, border, shape)
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(R.string.up_next).uppercase(),
                style = DotType.SectionLabel,
                color = DotColors.RustDeep,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = chosen.title, style = DotType.CardTitle, color = DotColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text(text = chosen.explanation, style = DotType.CardBody, color = DotColors.Muted)
            Spacer(Modifier.height(PreferredActionHeadroom))
            PreferredAction(modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = stringResource(
                        when (chosenStatus) {
                            LessonStatus.Completed -> R.string.review_lesson
                            LessonStatus.InProgress -> R.string.resume_lesson
                            else -> R.string.start_lesson
                        },
                    ),
                    onClick = { onStartLesson(chosen.id) },
                    height = DotDimens.LessonButtonHeight,
                    radius = DotDimens.LessonButtonRadius,
                    style = DotType.LessonButton,
                )
            }
            // Practising again is always the learner's to choose, and never
            // costs them what they have earned. Starting over a lesson under
            // way lets go of its place, and says so.
            if (chosenStatus == LessonStatus.InProgress || chosenStatus == LessonStatus.Completed) {
                Spacer(Modifier.height(6.dp))
                QuietAction(stringResource(R.string.restart_lesson), Modifier.align(Alignment.CenterHorizontally)) { onRestartLesson(chosen.id) }
                Text(
                    text = stringResource(
                        if (chosenStatus == LessonStatus.InProgress) R.string.restart_lesson_note_in_progress else R.string.restart_lesson_note_done,
                    ),
                    style = DotType.PathMeta,
                    color = DotColors.Muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            if (chosenStatus != LessonStatus.Completed && chosenStatus != LessonStatus.Skipped) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.skip_lesson),
                    style = DotType.TextAction,
                    color = DotColors.Muted,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .heightIn(min = DotDimens.MinTouchTarget)
                        .dotClickable(onClick = { onSkipLesson(chosen.id); chosenId = null }, role = Role.Button, shape = RoundedCornerShape(10.dp))
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.lessons_heading).uppercase(),
            style = DotType.SectionLabel,
            color = DotColors.Faint,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))

        course.lessons.forEachIndexed { index, lesson ->
            LessonRow(
                number = index + 1,
                lesson = lesson,
                status = summary.statuses[lesson.id] ?: LessonStatus.NotStarted,
                chosen = lesson.id == chosen.id,
                onClick = { chosenId = lesson.id },
            )
        }
        if (summary.started) {
            Spacer(Modifier.height(10.dp))
            QuietAction(stringResource(R.string.restart_course), Modifier.align(Alignment.CenterHorizontally), onRestartCourse)
            Text(
                text = stringResource(R.string.restart_course_note),
                style = DotType.PathMeta,
                color = DotColors.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A secondary action: plain underlined words, a full-size touch target. */
@Composable
private fun QuietAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = DotType.TextAction,
        color = DotColors.Muted,
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .heightIn(min = DotDimens.MinTouchTarget)
            .dotClickable(onClick = onClick, role = Role.Button, shape = RoundedCornerShape(10.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

@Composable
private fun LessonRow(number: Int, lesson: Lesson, status: LessonStatus, chosen: Boolean, onClick: () -> Unit) {
    val statusWords = when (status) {
        LessonStatus.Completed -> stringResource(R.string.lesson_status_done)
        LessonStatus.Unavailable -> stringResource(R.string.lesson_status_unavailable)
        LessonStatus.Skipped -> stringResource(R.string.lesson_status_skipped)
        LessonStatus.InProgress -> stringResource(R.string.lesson_status_in_progress)
        LessonStatus.NotStarted -> null
    }
    val shape = RoundedCornerShape(14.dp)
    val description = stringResource(R.string.lesson_row_description, number, lesson.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape)
            .then(if (chosen) Modifier.background(DotColors.NavPill, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                selected = chosen
                contentDescription = description
                statusWords?.let { stateDescription = it }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusMark(number, status)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = lesson.title, style = DotType.PathTitle, color = DotColors.Ink)
            if (statusWords != null) {
                Text(text = statusWords, style = DotType.PathMeta, color = DotColors.Muted)
            }
        }
    }
}

/**
 * The lesson's mark: its number while not started, a rust tick when done, a
 * rust ring while under way, a dash when it was skipped or not available.
 * Never colour alone — the row also says it in words.
 */
@Composable
private fun StatusMark(number: Int, status: LessonStatus) {
    val size = 30.dp
    when (status) {
        LessonStatus.NotStarted -> androidx.compose.foundation.layout.Box(
            Modifier
                .size(size)
                .border(1.5.dp, DotColors.Border, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "$number", style = DotType.PathMeta, color = DotColors.Muted, modifier = Modifier.clearAndSetSemantics { })
        }
        else -> Canvas(Modifier.size(size).clearAndSetSemantics { }) {
            val r = this.size.minDimension / 2
            val c = center
            when (status) {
                LessonStatus.Completed -> {
                    drawCircle(DotColors.Rust, r)
                    val u = r / 10f
                    drawLine(DotColors.Surface, Offset(c.x - 4.5f * u, c.y + 0.2f * u), Offset(c.x - 1.2f * u, c.y + 3.6f * u), 2.2f * u, StrokeCap.Round)
                    drawLine(DotColors.Surface, Offset(c.x - 1.2f * u, c.y + 3.6f * u), Offset(c.x + 5f * u, c.y - 3.4f * u), 2.2f * u, StrokeCap.Round)
                }
                LessonStatus.InProgress -> {
                    drawCircle(DotColors.Rust, r - 1.5.dp.toPx(), style = Stroke(2.dp.toPx()))
                    drawCircle(DotColors.Rust, r * 0.32f)
                }
                else -> {
                    drawCircle(DotColors.Border, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    drawLine(DotColors.Muted, Offset(c.x - r * 0.35f, c.y), Offset(c.x + r * 0.35f, c.y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Preview(name = "Course", showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 390, heightDp = 1100)
@Composable
private fun PreviewCourseDetail() {
    com.aaravsekhri.dot.ui.theme.DotTheme {
        CourseDetailScreen(
            summary = CourseProgressStore.summarize(CourseCatalog.chatGpt, null),
            onBack = {},
            onStartLesson = {},
            onSkipLesson = {},
        )
    }
}
