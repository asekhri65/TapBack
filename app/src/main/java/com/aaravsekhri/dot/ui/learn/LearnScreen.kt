package com.aaravsekhri.dot.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import com.aaravsekhri.dot.courses.CourseSearch
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.components.UnderlinedTextAction
import com.aaravsekhri.dot.ui.design.StageIcon
import com.aaravsekhri.dot.ui.design.StageIcons
import com.aaravsekhri.dot.ui.pebble.StagePebble
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.courses.CourseAction
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.CourseCategory
import com.aaravsekhri.dot.courses.CourseProgressStore
import com.aaravsekhri.dot.courses.CourseSummary
import com.aaravsekhri.dot.ui.components.PreferredAction
import com.aaravsekhri.dot.ui.components.PreferredActionHeadroom
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SettingsAction
import com.aaravsekhri.dot.ui.components.dotClickable
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The Learn tab: find a course by typing or by category, one course to
 * start or continue, and the rest as a calm list.
 *
 * Only the featured card has a button, and it is the one recommended action
 * on the screen; it steps aside while someone is searching or filtering, so
 * what they asked for comes first. Every other course is a row that opens its
 * own page. Progress is counted in lessons, never in taps.
 *
 * The query, the category and the scroll position belong to the caller, so
 * opening a course and coming back finds Learn exactly as it was left.
 *
 * @param summaries every course with its progress, in catalogue order.
 * @param featured the course to feature: the one in progress, or where to start.
 */
@Composable
fun LearnScreen(
    summaries: List<CourseSummary>,
    featured: CourseSummary,
    onOpenSettings: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onStartLesson: (courseId: String, lessonId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether a course needs Dot Plus this person does not have: shown, never hidden. */
    locked: (String) -> Boolean = { false },
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    category: CourseCategory? = null,
    onCategoryChange: (CourseCategory?) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    /** Nothing matched: ask Pebble in the person's own words instead. */
    onAskInstead: ((String) -> Unit)? = null,
) {
    val searching = query.isNotBlank()
    val browsing = !searching && category == null
    val byId = remember(summaries) { summaries.associateBy { it.course.id } }
    val hits: List<CourseSearch.Hit> = remember(query, category, summaries) {
        val pool = summaries.map { it.course }.filter { category == null || it.category == category }
        if (searching) CourseSearch.search(pool, query) else pool.map { CourseSearch.Hit(it, 0.0, null) }
    }

    LazyColumn(modifier.fillMaxSize(), state = listState) {
        // The Settings control scrolls with the content here, so it never
        // floats over the cards on a small screen or at a large font scale.
        item(key = "settings") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsAction(onClick = onOpenSettings)
            }
        }
        item(key = "title") {
            Text(
                text = stringResource(R.string.tab_learn),
                style = DotType.ScreenTitle,
                color = DotColors.Ink,
                modifier = Modifier
                    .padding(horizontal = DotDimens.Gutter)
                    .semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
        }
        item(key = "search") {
            CourseSearchField(query, onQueryChange, Modifier.padding(horizontal = DotDimens.Gutter))
            Spacer(Modifier.height(14.dp))
        }
        item(key = "categories") {
            CategoryChips(category, onCategoryChange, Modifier.padding(horizontal = DotDimens.Gutter))
            Spacer(Modifier.height(20.dp))
        }

        if (browsing) {
            item(key = "featured") {
                FeaturedCourseCard(
                    summary = featured,
                    onStart = { lessonId -> onStartLesson(featured.course.id, lessonId) },
                    onOpen = { onOpenCourse(featured.course.id) },
                    modifier = Modifier.padding(horizontal = DotDimens.Gutter),
                )
            }
            // The rest, under a few plain headings.
            CourseCategory.entries.forEach { heading ->
                val inIt = summaries.filter { it.course.category == heading && it.course.id != featured.course.id }
                if (inIt.isEmpty()) return@forEach
                item(key = "heading-${heading.name}") {
                    Spacer(Modifier.height(26.dp))
                    SectionLabel(stringResource(heading.titleRes))
                    Spacer(Modifier.height(12.dp))
                }
                items(inIt, key = { it.course.id }) { summary ->
                    CourseRow(
                        summary = summary,
                        locked = locked(summary.course.id),
                        onClick = { onOpenCourse(summary.course.id) },
                        modifier = Modifier.padding(horizontal = DotDimens.Gutter, vertical = 6.dp),
                    )
                }
            }
        } else if (hits.isEmpty()) {
            item(key = "empty") {
                NothingFound(
                    query = query,
                    category = category,
                    onReset = {
                        onQueryChange("")
                        onCategoryChange(null)
                    },
                    onAskInstead = onAskInstead?.takeIf { searching }?.let { ask -> { ask(query.trim()) } },
                    modifier = Modifier.padding(horizontal = DotDimens.Gutter),
                )
            }
        } else {
            item(key = "count") {
                val count = pluralStringResource(R.plurals.learn_result_count, hits.size, hits.size)
                Text(
                    text = if (searching) stringResource(R.string.learn_results_for, count, query.trim()) else count,
                    style = DotType.CardMeta,
                    color = DotColors.Muted,
                    modifier = Modifier
                        .padding(horizontal = DotDimens.Gutter)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(10.dp))
            }
            items(hits, key = { it.course.id }) { hit ->
                val summary = byId.getValue(hit.course.id)
                CourseRow(
                    summary = summary,
                    locked = locked(summary.course.id),
                    onClick = { onOpenCourse(summary.course.id) },
                    matchedLesson = hit.lesson?.title,
                    modifier = Modifier.padding(horizontal = DotDimens.Gutter, vertical = 6.dp),
                )
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

/** "Find a course": typed words, matched on the phone as they are typed. */
@Composable
private fun CourseSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(14.dp)
    val label = stringResource(R.string.learn_search)
    BasicTextField(
        value = query,
        onValueChange = { onQueryChange(it.take(60)) },
        singleLine = true,
        textStyle = DotType.CardBody.copy(color = DotColors.Ink),
        cursorBrush = SolidColor(DotColors.Rust),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(DotColors.Surface, shape)
                    .border(1.dp, DotColors.Border, shape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageIcon(StageIcons.Search, DotColors.Muted, 22.dp, stroke = 2f)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) Text(label, style = DotType.CardBody, color = DotColors.Faint)
                    field()
                }
                if (query.isNotEmpty()) {
                    val clear = stringResource(R.string.learn_search_clear)
                    Box(
                        Modifier
                            .size(48.dp)
                            .dotClickable(onClick = { onQueryChange("") }, role = Role.Button, shape = CircleShape)
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) { StageIcon(StageIcons.Close, DotColors.Muted, 20.dp, stroke = 2f) }
                }
            }
        },
    )
}

/**
 * All, then each heading: they wrap onto a second line rather than scroll
 * sideways, so every choice is in sight. The chosen one is filled and ticked.
 */
@Composable
private fun CategoryChips(selected: CourseCategory?, onSelect: (CourseCategory?) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(stringResource(R.string.category_all), selected == null) { onSelect(null) }
        CourseCategory.entries.forEach { c ->
            Chip(stringResource(c.titleRes), selected == c) { onSelect(if (selected == c) null else c) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .background(if (selected) DotColors.Ink else DotColors.Surface, shape)
            .border(1.dp, if (selected) DotColors.Ink else DotColors.Border, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) StageIcon(StageIcons.Check, DotColors.Surface, 18.dp, stroke = 2.4f)
        Text(text, style = DotType.CardMeta, color = if (selected) DotColors.Surface else DotColors.Ink)
    }
}

/** Nothing matched: say so, offer the whole list back, and Ask for anything else. */
@Composable
private fun NothingFound(
    query: String,
    category: CourseCategory?,
    onReset: () -> Unit,
    onAskInstead: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Spacer(Modifier.height(12.dp))
        StagePebble(size = 64.dp, curious = true, bob = false, shadow = false)
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (query.isNotBlank()) stringResource(R.string.learn_empty_query, query.trim())
            else stringResource(R.string.learn_empty_category),
            style = DotType.CardTitle,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (category != null) R.string.learn_empty_hint_category else R.string.learn_empty_hint),
            style = DotType.CardBody,
            color = DotColors.Muted,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton(text = stringResource(R.string.learn_show_all), onClick = onReset)
        if (onAskInstead != null) {
            UnderlinedTextAction(text = stringResource(R.string.learn_ask_instead), onClick = onAskInstead)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = DotType.SectionLabel,
        color = DotColors.Faint,
        modifier = Modifier
            .padding(horizontal = DotDimens.Gutter)
            .semantics { heading() },
    )
}

/**
 * "Start here", or "Continue learning" while a course is under way: the one
 * card with a button, and the one recommended action on the tab. Compact —
 * title, one line of where things stand, the button — so the list is near.
 */
@Composable
private fun FeaturedCourseCard(
    summary: CourseSummary,
    onStart: (lessonId: String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DotDimens.FeaturedRadius)
    val continuing = summary.action == CourseAction.Resume
    val lesson = summary.current ?: summary.course.lessons.first()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DotColors.FeaturedFill, shape)
            .border(1.dp, DotColors.FeaturedBorder, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (continuing) R.string.continue_learning else R.string.start_here).uppercase(),
                    style = DotType.SectionLabel,
                    color = DotColors.RustDeep,
                )
                Spacer(Modifier.height(8.dp))
                // The title opens the course, for a look at every lesson first.
                Text(
                    text = summary.course.title,
                    style = DotType.PathTitle,
                    color = DotColors.Ink,
                    modifier = Modifier
                        .dotClickable(onClick = onOpen, role = Role.Button, shape = RoundedCornerShape(8.dp))
                        .semantics { heading() },
                )
            }
            Spacer(Modifier.width(10.dp))
            CourseIllustration(summary.course.art, size = 52.dp)
        }
        Spacer(Modifier.height(10.dp))
        if (summary.started) {
            Text(
                text = stringResource(R.string.next_lesson_meta, lesson.title),
                style = DotType.CardMeta,
                color = DotColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            LessonProgress(summary, compact = true)
        } else {
            Text(
                text = pluralStringResource(R.plurals.lesson_count, summary.total, summary.total),
                style = DotType.CardMeta,
                color = DotColors.Muted,
            )
        }
        Spacer(Modifier.height(PreferredActionHeadroom))
        PreferredAction(modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(if (continuing) R.string.resume_lesson else R.string.start_lesson),
                onClick = { onStart(lesson.id) },
                height = DotDimens.LessonButtonHeight,
                radius = DotDimens.LessonButtonRadius,
                style = DotType.LessonButton,
            )
        }
    }
}

/** One course in the list: title, its lessons or progress, its illustration, a chevron. */
@Composable
private fun CourseRow(
    summary: CourseSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    /** The lesson a search found, when the course's own words did not match. */
    matchedLesson: String? = null,
) {
    val shape = RoundedCornerShape(DotDimens.PathCardRadius)
    val (fill, border) = courseColours(summary.course.art)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape)
            .background(fill, shape)
            .border(1.dp, border, shape)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = summary.course.title, style = DotType.PathTitle, color = DotColors.Ink)
            if (matchedLesson != null) {
                Text(
                    text = stringResource(R.string.learn_matched_lesson, matchedLesson),
                    style = DotType.PathMeta,
                    color = DotColors.RustDeep,
                )
            }
            if (locked) PlusTag()
            if (summary.started) {
                LessonProgress(summary, compact = true)
            } else {
                Text(
                    text = pluralStringResource(R.plurals.lesson_count, summary.total, summary.total),
                    style = DotType.PathMeta,
                    color = DotColors.Muted,
                )
            }
        }
        CourseIllustration(summary.course.art, size = 60.dp)
        Chevron()
    }
}

/** A small, quiet mark on a course that is part of Dot Plus. */
@Composable
internal fun PlusTag() {
    Text(
        text = stringResource(R.string.plus_tag),
        style = DotType.CardMeta,
        color = DotColors.RustDeep,
        modifier = Modifier
            .background(DotColors.Surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .border(1.dp, DotColors.FeaturedBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The heading each category is listed under. */
internal val CourseCategory.titleRes: Int
    get() = when (this) {
        CourseCategory.EverydayEssentials -> R.string.category_everyday
        CourseCategory.StayConnected -> R.string.category_connected
        CourseCategory.PhotosAndCreativity -> R.string.category_photos
        CourseCategory.AiTools -> R.string.category_ai
        CourseCategory.MakeItYours -> R.string.category_yours
    }

/**
 * A thin bar and the same thing in words: "2 of 4 lessons completed". The
 * words are what is announced; the bar is there to glance at.
 */
@Composable
internal fun LessonProgress(summary: CourseSummary, compact: Boolean = false) {
    val words = pluralStringResource(R.plurals.lessons_completed, summary.total, summary.completed, summary.total)
    val out = if (summary.notAvailable > 0) {
        " · " + pluralStringResource(R.plurals.lessons_not_available, summary.notAvailable, summary.notAvailable)
    } else {
        ""
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = words + out
            progressBarRangeInfo = ProgressBarRangeInfo(summary.fraction, 0f..1f)
        },
    ) {
        ProgressBar(summary.fraction, Modifier.fillMaxWidth(if (compact) 0.8f else 1f))
        Text(
            text = words + out,
            style = if (compact) DotType.PathMeta else DotType.CardMeta,
            color = if (compact) DotColors.Muted else DotColors.Faint,
        )
    }
}

@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.height(6.dp).clearAndSetSemantics { }) {
        val r = size.height / 2
        drawRoundRect(DotColors.ProgressTrack, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        if (fraction > 0f) {
            drawRoundRect(
                DotColors.Rust,
                size = size.copy(width = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(size.height)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )
        }
    }
}

@Composable
internal fun Chevron(color: Color = DotColors.Chevron) {
    Canvas(Modifier.size(22.dp).clearAndSetSemantics { }) {
        val u = size.minDimension / 24f
        drawPath(
            Path().apply {
                moveTo(9f * u, 5f * u)
                lineTo(16f * u, 12f * u)
                lineTo(9f * u, 19f * u)
            },
            color,
            style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Preview(name = "Learn", showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 390, heightDp = 1400)
@Composable
private fun PreviewLearnScreen() {
    val summaries = CourseCatalog.courses.map { CourseProgressStore.summarize(it, null) }
    com.aaravsekhri.dot.ui.theme.DotTheme {
        LearnScreen(
            summaries = summaries,
            featured = summaries.first(),
            onOpenSettings = {},
            onOpenCourse = {},
            onStartLesson = { _, _ -> },
        )
    }
}
