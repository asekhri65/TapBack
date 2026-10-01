package com.aaravsekhri.dot.guide.overlay

import androidx.annotation.StringRes
import com.aaravsekhri.dot.R

/**
 * The buttons a general session's card can offer.
 *
 * A small public vocabulary so the session controller can say what it wants
 * without reaching into the overlay's own internals. The wording lives in
 * resources, because these are words a person reads.
 */
enum class AiCardAction(@StringRes internal val labelRes: Int) {
    Continue(R.string.ai_continue),
    Retry(R.string.ai_try_again),
    Answer(R.string.ai_answer),
    Pause(R.string.guide_pause),
    Resume(R.string.guide_resume),
    Exit(R.string.guide_exit),
    Finish(R.string.ai_finish),
    DoneTyping(R.string.ai_done_typing),
    TryAnotherWay(R.string.ai_try_another_way),
    KeepWaiting(R.string.ai_keep_waiting),
    ChangeAnswer(R.string.ai_change_answer),
    TryLater(R.string.ai_try_later),
    KeepTrying(R.string.ai_keep_trying),
    NextLesson(R.string.lesson_next),
    OpenCourse(R.string.lesson_choose_another),
    CheckAgain(R.string.ai_check_again),
    FinishForNow(R.string.lesson_finish_for_now),
    Begin(R.string.teach_begin),
    ShowMeHow(R.string.teach_show_me_how),
    Next(R.string.teach_next),
    ReviewCourse(R.string.lesson_review_course);

    internal val internal: StatusAction
        get() = when (this) {
            Continue -> StatusAction.ContinueAfterHandoff
            Retry -> StatusAction.RetryPlanning
            Answer -> StatusAction.AnswerInApp
            Pause -> StatusAction.Pause
            Resume -> StatusAction.Resume
            Exit -> StatusAction.Exit
            Finish -> StatusAction.Finish
            DoneTyping -> StatusAction.DoneTyping
            TryAnotherWay -> StatusAction.TryAnotherWay
            KeepWaiting -> StatusAction.KeepWaiting
            ChangeAnswer -> StatusAction.ChangeAnswer
            TryLater -> StatusAction.TryLater
            KeepTrying -> StatusAction.KeepTrying
            NextLesson -> StatusAction.NextLesson
            OpenCourse -> StatusAction.OpenCourse
            CheckAgain -> StatusAction.CheckAgain
            FinishForNow -> StatusAction.Finish
            Begin, ShowMeHow, Next -> StatusAction.TeachingNext
            ReviewCourse -> StatusAction.OpenCourse
        }
}
