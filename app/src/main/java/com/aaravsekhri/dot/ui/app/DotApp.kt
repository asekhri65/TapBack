package com.aaravsekhri.dot.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.ask.AskScreen
import com.aaravsekhri.dot.ui.ask.AskStateHolder
import com.aaravsekhri.dot.ui.ask.rememberAskStateHolder
import com.aaravsekhri.dot.ui.components.DotBottomNav
import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.ui.learn.LearnScreen
import com.aaravsekhri.dot.ui.learn.CourseDetailScreen
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.CourseProgress
import com.aaravsekhri.dot.courses.CourseProgressStore
import com.aaravsekhri.dot.ui.guide.ReplaceGuideScreen
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.guide.DisclosureScreen
import com.aaravsekhri.dot.ui.guide.EnableServiceScreen
import com.aaravsekhri.dot.ui.guide.GuideLaunchController
import com.aaravsekhri.dot.guide.service.GuideCoordinator
import com.aaravsekhri.dot.ui.guide.ClarificationScreen
import com.aaravsekhri.dot.ui.guide.CloudGuidanceDisclosureScreen
import com.aaravsekhri.dot.ui.guide.GuideLaunchState
import com.aaravsekhri.dot.ui.guide.ResumeSessionScreen
import com.aaravsekhri.dot.ui.guide.SimpleNoticeScreen
import com.aaravsekhri.dot.ui.guide.LimitedModeScreen
import com.aaravsekhri.dot.ui.guide.ResumeGuideScreen
import com.aaravsekhri.dot.ui.guide.UnsupportedRequestScreen
import com.aaravsekhri.dot.ui.guide.rememberGuideLaunchController
import com.aaravsekhri.dot.ui.plus.PlanSection

/**
 * Dot's shell: the background, the system-bar insets, which tab is showing and
 * whether the bottom navigation belongs on screen.
 *
 * The navigation is present on Learn, and on Ask except while the keyboard is
 * open. Then there is one thing to do — finish the request and press "Show me
 * how" — and the room goes to the box and the button rather than to tabs
 * squeezed between them and the keys. It comes back when the keyboard goes.
 *
 * Settings opens over the tabs from the small Settings action on each.
 * @param launchController takes "Show me how" from the Ask tab through consent
 *   and, if needed, Android's Accessibility settings before a guide can run.
 */
@Composable
fun DotApp(
    modifier: Modifier = Modifier,
    initialTab: DotTab = DotTab.Ask,
    askStateHolder: AskStateHolder = rememberAskStateHolder(),
    launchController: GuideLaunchController = rememberGuideLaunchController(),
) {
    // Coming back from Android's Accessibility settings is the one moment
    // access may have changed, so the check happens on every resume rather
    // than being remembered from before Dot left.
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current
    val plusRepository = if (androidx.compose.ui.platform.LocalInspectionMode.current) null else remember(appContext) {
        com.aaravsekhri.dot.account.PlusRepository.get(appContext)
    }
    DisposableEffect(lifecycleOwner, launchController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                launchController.onReturnToApp()
                // What may be started, and the store's word on Plus: fresh on
                // every return, bounded inside.
                plusRepository?.onForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Course progress, from the one store the guide service also writes to:
    // a lesson finished over another app shows here as soon as Dot is back.
    val context = LocalContext.current
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val progress = remember(context) { if (inPreview) null else CourseProgress.get(context) }
    val records by (progress?.records ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
    val summaries = CourseCatalog.courses.map { CourseProgressStore.summarize(it, records[it.id]) }

    // The first-run introduction, for someone new. Anyone who has already
    // used Dot — agreed to a disclosure, made progress, left a guide to come
    // back to — goes straight in; they can replay it from Settings.
    val onboardingStore = remember(context) { com.aaravsekhri.dot.onboarding.OnboardingStore(context) }
    var onboarding by rememberSaveable {
        mutableStateOf(!inPreview && onboardingStore.shouldShow(existingUserSignals(context, records.isNotEmpty())))
    }
    if (onboarding) {
        com.aaravsekhri.dot.ui.onboarding.OnboardingFlow(
            store = onboardingStore,
            onFinished = {
                onboarding = false
                selectedTab = DotTab.Ask
            },
        )
        return
    }
    var openCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    // Learn's search, category and place in the list, kept here so opening a
    // course and coming back finds Learn as it was left.
    var learnQuery by rememberSaveable { mutableStateOf("") }
    var learnCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val learnList = androidx.compose.foundation.lazy.rememberLazyListState()

    // "Choose another lesson" from a lesson's card opens that course here.
    val courseToShow by GuideCoordinator.openCourse.collectAsState()
    LaunchedEffect(courseToShow) {
        courseToShow?.let {
            selectedTab = DotTab.Learn
            openCourseId = it
            GuideCoordinator.courseShown()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selectedTab == DotTab.Learn && openCourseId != null) {
        openCourseId = null
    }

    // Whether there is an unfinished guide to offer is settled before the
    // keyboard may come up: otherwise it opened behind "Carry on where you
    // left off?", over the very buttons that answer it.
    var resumeChecked by remember { mutableStateOf(false) }

    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // The one invitation to Plus, after the first task TapBack helped
    // finish: "All done." straight after it, a small card on Ask on a later
    // visit — once. Dismissed is dismissed for good; never over a task.
    val plusState = plusRepository?.state?.collectAsState()
    var invitation by remember { mutableStateOf<com.aaravsekhri.dot.account.Invitation?>(null) }
    LaunchedEffect(plusState?.value?.allowance, launchController.state) {
        invitation = plusRepository?.invitation()?.takeIf { launchController.state == GuideLaunchState.Idle }
    }
    val inviting = invitation != null
    val invitePerWeek = plusState?.value?.allowance?.tasksPerPeriod ?: 10
    var legalDocument by rememberSaveable { mutableStateOf<com.aaravsekhri.dot.ui.legal.LegalDocument?>(null) }
    var plusFixture by rememberSaveable { mutableStateOf<com.aaravsekhri.dot.ui.plus.PlusFixture?>(null) }

    // A guide card kept over Dot's own screen — "go back to the app", "go to
    // your Home screen" — takes the top of it. Dot's screen moves down by
    // exactly that much, so the card covers none of Dot's controls.
    val cardOverDot by GuideCoordinator.cardOverDot.collectAsState()
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    val roomForCard = with(LocalDensity.current) { (cardOverDot - statusTop).coerceAtLeast(0).toDp() }
    val navigationVisible = selectedTab == DotTab.Learn || !keyboardOpen

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DotColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = roomForCard)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    DotTab.Ask -> AskScreen(
                        holder = askStateHolder,
                        onOpenSettings = { showSettings = true },
                        onShowMeHow = launchController::requestGuideFor,
                        // Never under a dialog: the keyboard would come up
                        // behind "Carry on where you left off?".
                        autoFocus = resumeChecked && launchController.state == GuideLaunchState.Idle && !inviting &&
                            !showSettings && legalDocument == null && plusFixture == null,
                    )

                    DotTab.Learn -> {
                        val open = summaries.firstOrNull { it.course.id == openCourseId }
                        if (open != null) {
                            CourseDetailScreen(
                                summary = open,
                                locked = launchController.isLocked(open.course.id),
                                onBack = { openCourseId = null },
                                onStartLesson = { lessonId -> launchController.requestLesson(open.course.id, lessonId) },
                                onSkipLesson = { lessonId ->
                                    CourseCatalog.lesson(open.course.id, lessonId)?.let {
                                        progress?.skip(open.course.id, it.id, it.version)
                                    }
                                },
                                onRestartLesson = { lessonId ->
                                    launchController.requestLesson(
                                        open.course.id, lessonId,
                                        com.aaravsekhri.dot.guide.service.GuideCoordinator.Restart.Lesson,
                                    )
                                },
                                onRestartCourse = {
                                    val first = open.course.coreLessons.ifEmpty { open.course.lessons }.first()
                                    launchController.requestLesson(
                                        open.course.id, first.id,
                                        com.aaravsekhri.dot.guide.service.GuideCoordinator.Restart.Course,
                                    )
                                },
                            )
                        } else {
                            // The course under way, or where Dot suggests starting.
                            val featured = summaries.filter { it.action == com.aaravsekhri.dot.courses.CourseAction.Resume }
                                .maxByOrNull { records[it.course.id]?.updatedAtMs ?: 0L }
                                // Where to start is a course they can start.
                                ?: summaries.firstOrNull { it.action == com.aaravsekhri.dot.courses.CourseAction.Start && !launchController.isLocked(it.course.id) }
                                ?: summaries.firstOrNull { it.action == com.aaravsekhri.dot.courses.CourseAction.Start }
                                ?: summaries.first()
                            LearnScreen(
                                summaries = summaries,
                                featured = featured,
                                onOpenSettings = { showSettings = true },
                                onOpenCourse = { openCourseId = it },
                                onStartLesson = { courseId, lessonId -> launchController.requestLesson(courseId, lessonId) },
                                locked = launchController::isLocked,
                                query = learnQuery,
                                onQueryChange = { learnQuery = it },
                                category = learnCategory?.let { name -> com.aaravsekhri.dot.courses.CourseCategory.entries.firstOrNull { it.name == name } },
                                onCategoryChange = { learnCategory = it?.name },
                                listState = learnList,
                                onAskInstead = { words ->
                                    askStateHolder.updateDraft(words)
                                    selectedTab = DotTab.Ask
                                },
                            )
                        }
                    }
                }
            }

            if (invitation == com.aaravsekhri.dot.account.Invitation.Later && selectedTab == DotTab.Ask && !keyboardOpen) {
                com.aaravsekhri.dot.ui.plus.PlusInvitation(
                    perWeek = invitePerWeek,
                    onSee = {
                        plusRepository?.dismissInvitation()
                        invitation = null
                        launchController.showPlus("browse")
                    },
                    onDismiss = {
                        plusRepository?.dismissInvitation()
                        invitation = null
                    },
                    modifier = Modifier.padding(horizontal = com.aaravsekhri.dot.ui.theme.DotDimens.Gutter, vertical = 8.dp),
                )
            }

            AnimatedVisibility(
                visible = navigationVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                DotBottomNav(
                    selected = selectedTab,
                    // The draft is kept: a look at Learn is not a reason to lose
                    // what was typed.
                    onSelect = { tab -> selectedTab = tab },
                )
            }
        }

        // A question the running session needs answered, and an unfinished
        // request from last time. Both are things the app must surface; the
        // overlay cannot take typing and a checkpoint should be offered, never
        // resumed behind somebody's back.
        val question by GuideCoordinator.pendingQuestion.collectAsState()
        LaunchedEffect(question) {
            question?.let { launchController.showQuestion(it.question, it.options) }
        }
        LaunchedEffect(Unit) {
            launchController.offerResumeIfAny()
            resumeChecked = true
        }

        // Dot's own decision screens are made in Dot: a guide's card steps
        // aside while one is up, rather than sit on its buttons.
        // Only while Dot is actually on screen: a decision state in a Dot that
        // is behind another app is not something in the way of anybody.
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
        val deciding = launchController.state != GuideLaunchState.Idle &&
            lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        LaunchedEffect(deciding) {
            com.aaravsekhri.dot.guide.service.DotAccessibilityService.setDotDecisionShowing(deciding)
        }

        if (showSettings) {
            com.aaravsekhri.dot.ui.settings.SettingsScreen(
                onBack = { showSettings = false },
                onReplayIntroduction = {
                    onboardingStore.replay()
                    showSettings = false
                    onboarding = true
                },
                plan = {
                    if (plusRepository != null) {
                        PlanSection(onSeePlus = { launchController.showPlus("browse") })
                    }
                },
                onOpenDocument = { legalDocument = it },
                onPreviewPlus = { plusFixture = it },
            )
        }

        legalDocument?.let {
            com.aaravsekhri.dot.ui.legal.LegalScreen(it) { legalDocument = null }
        }

        plusFixture?.let {
            androidx.activity.compose.BackHandler { plusFixture = null }
            com.aaravsekhri.dot.ui.plus.PlusFixtureScreen(it) { plusFixture = null }
        }

        (invitation as? com.aaravsekhri.dot.account.Invitation.Fresh)?.let { fresh ->
            if (!showSettings && launchController.state == GuideLaunchState.Idle) {
                com.aaravsekhri.dot.ui.plus.PlusInvitationScreen(
                    summary = fresh.summary,
                    perWeek = invitePerWeek,
                    onExplore = {
                        plusRepository?.dismissInvitation()
                        invitation = null
                        launchController.showPlus("browse")
                    },
                    onKeepFree = {
                        plusRepository?.dismissInvitation()
                        invitation = null
                    },
                )
            }
        }

        val activity = LocalContext.current as? android.app.Activity
        GuideLaunchOverlay(
            controller = launchController,
            // Answering a question is the end of Dot's part in that moment.
            // Stepping aside returns the person to the app they were using,
            // with the task still running, rather than leaving them in Dot
            // wondering how to get back.
            onAnswered = { activity?.moveTaskToBack(true) },
        )
    }
}

/** What an install already says about having been used, read before the introduction existed. */
private fun existingUserSignals(context: android.content.Context, hasCourseProgress: Boolean): com.aaravsekhri.dot.onboarding.OnboardingStore.ExistingUserSignals {
    val guide = com.aaravsekhri.dot.guide.service.GuidePreferences(context)
    val sessions = context.getSharedPreferences(com.aaravsekhri.dot.guide.ai.AiSessionStore.FILE, android.content.Context.MODE_PRIVATE)
    return com.aaravsekhri.dot.onboarding.OnboardingStore.ExistingUserSignals(
        acceptedDisclosure = guide.disclosureAccepted,
        acceptedCloudGuidance = guide.cloudGuidanceAccepted,
        hasCourseProgress = hasCourseProgress,
        hasSavedSession = sessions.all.keys.any { it.startsWith("checkpoint") },
    )
}

/**
 * The consent and set-up screens, shown over the tabs when a guide is being
 * started. They cover the app deliberately: this is a decision to make, not
 * something to skim past.
 */
@Composable
private fun GuideLaunchOverlay(controller: GuideLaunchController, onAnswered: () -> Unit = {}) {
    when (controller.state) {
        GuideLaunchState.Idle -> Unit

        is GuideLaunchState.Disclosure -> DisclosureScreen(
            onAllow = controller::acceptDisclosure,
            onDecline = controller::declineDisclosure,
        )

        is GuideLaunchState.NeedsService -> EnableServiceScreen(
            onOpenSettings = controller::openAccessibilitySettings,
            onNotNow = controller::dismiss,
        )

        is GuideLaunchState.ReadyToResume -> ResumeGuideScreen(
            goal = (controller.state as GuideLaunchState.ReadyToResume).goal,
            onContinue = controller::resumePendingGuide,
            onCancel = controller::dismiss,
        )

        is GuideLaunchState.Limited -> LimitedModeScreen(
            steps = listOf(
                stringResource(R.string.limited_step_1),
                stringResource(R.string.limited_step_2),
                stringResource(R.string.limited_step_3),
            ),
            onClose = controller::dismiss,
        )

        is GuideLaunchState.CloudDisclosure -> CloudGuidanceDisclosureScreen(
            goal = (controller.state as GuideLaunchState.CloudDisclosure).goal,
            onAllow = controller::acceptCloudDisclosure,
            onDecline = controller::declineCloudDisclosure,
        )

        GuideLaunchState.NoPlanningService -> SimpleNoticeScreen(
            title = stringResource(R.string.no_planning_service_title),
            body = stringResource(R.string.no_planning_service_body),
            onClose = controller::dismiss,
        )

        is GuideLaunchState.ResumeOffer -> ResumeSessionScreen(
            goal = (controller.state as GuideLaunchState.ResumeOffer).goal,
            // "Dot will look at your screen again": stepping aside shows the
            // screen they left, which is usually where the task was, and the
            // session reads whatever is actually there.
            onContinue = {
                controller.resumeSession()
                onAnswered()
            },
            onDiscard = controller::discardSession,
        )

        is GuideLaunchState.Question -> {
            val question = controller.state as GuideLaunchState.Question
            ClarificationScreen(
                question = question.question,
                options = question.options,
                onAnswer = { answer -> if (controller.answerQuestion(answer)) onAnswered() },
                onClose = controller::endGuide,
            )
        }

        GuideLaunchState.Unsupported -> UnsupportedRequestScreen(onClose = controller::dismiss)

        GuideLaunchState.Admitting -> com.aaravsekhri.dot.ui.guide.GuideLoadingScreen(
            message = stringResource(R.string.admitting),
            endLabel = stringResource(R.string.guide_exit),
            onEnd = controller::dismiss,
        )

        is GuideLaunchState.Plus -> com.aaravsekhri.dot.ui.plus.PlusScreen(
            reason = (controller.state as GuideLaunchState.Plus).reason,
            onClose = controller::closePlus,
        )

        is GuideLaunchState.AdmissionFailed -> {
            val (explanation, suggestion) = com.aaravsekhri.dot.guide.ai.AiSessionController.startFailureWords(
                (controller.state as GuideLaunchState.AdmissionFailed).kind,
            )
            com.aaravsekhri.dot.ui.guide.LaunchScreenScaffold(
                title = stringResource(R.string.admission_failed_title),
                body = { com.aaravsekhri.dot.ui.guide.BodyText("$explanation $suggestion".trim()) },
                actions = {
                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        com.aaravsekhri.dot.ui.components.PrimaryButton(
                            text = stringResource(R.string.plus_try_again),
                            onClick = controller::retryAdmission,
                        )
                        com.aaravsekhri.dot.ui.components.SecondaryButton(
                            text = stringResource(R.string.plus_not_now),
                            onClick = controller::dismiss,
                        )
                    }
                },
            )
        }

        is GuideLaunchState.ReplaceGuide -> {
            val replace = controller.state as GuideLaunchState.ReplaceGuide
            ReplaceGuideScreen(
                current = replace.current,
                next = replace.next,
                onStart = controller::confirmReplace,
                onKeep = controller::keepCurrent,
            )
        }
    }
}

@Preview(name = "Ask — empty", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewAskEmpty() {
    DotTheme { DotApp(askStateHolder = AskStateHolder()) }
}

@Preview(name = "Ask — a request", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewAskRequest() {
    DotTheme { DotApp(askStateHolder = AskStateHolder("Make the text bigger on my phone")) }
}

@Preview(name = "Learn", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewLearnTab() {
    DotTheme { DotApp(initialTab = DotTab.Learn) }
}
