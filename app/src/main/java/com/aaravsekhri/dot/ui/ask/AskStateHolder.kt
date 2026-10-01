package com.aaravsekhri.dot.ui.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The Ask tab's one piece of state: the request being written.
 *
 * V1 is typing only, so there is a single editable draft rather than a chain
 * of screens. What used to be separate "typing" and "ready" states are the
 * same thing to the person — words in a box, and a button to start — and
 * treating the keyboard closing as "finished" is what made the button hard to
 * find. Nothing here reacts to the keyboard at all.
 *
 * Kept as a saveable holder rather than a ViewModel: the state is one string,
 * and [Saver] carries it through rotation and process death with no extra
 * dependency.
 */
class AskStateHolder(initialDraft: String = "") {

    var draft: String by mutableStateOf(initialDraft)
        private set

    /** True once the draft holds something worth starting a guide for. */
    val canSubmit: Boolean get() = draft.isMeaningfulRequest()

    private var lastSubmitAt = Long.MIN_VALUE / 2

    fun updateDraft(text: String) {
        draft = text
    }

    /**
     * "Show me how", or the keyboard's own Go key.
     *
     * Returns the request to start, or null: when there is nothing worth
     * asking about, or when this is a second tap arriving before the first
     * has had time to take the person anywhere. The draft is kept either way,
     * so coming back to Ask shows what was asked and it can be changed.
     */
    fun submit(nowMs: Long): String? {
        if (!canSubmit) return null
        if (nowMs - lastSubmitAt < DOUBLE_TAP_MS) return null
        lastSubmitAt = nowMs
        return draft.trim()
    }

    companion object {
        /** Taps closer together than this are one tap. */
        const val DOUBLE_TAP_MS = 1_500L

        /**
         * Saved as a tag and the draft.
         *
         * Also restores what earlier versions saved — a request heard by
         * voice, a typed request waiting to be sent, a draft half-typed — so
         * nobody loses their words to an update. Whatever the old state was,
         * it comes back as text in the box: there is no microphone to return
         * to any more.
         */
        val Saver: Saver<AskStateHolder, List<Any>> = Saver(
            save = { holder -> listOf(TAG_DRAFT, holder.draft) },
            restore = { saved ->
                val words = when (saved.firstOrNull()) {
                    TAG_DRAFT, TAG_OLD_TYPING, TAG_OLD_TYPED_READY, TAG_OLD_VOICE_READY ->
                        saved.getOrNull(1) as? String
                    else -> null
                }
                AskStateHolder(words.orEmpty())
            },
        )

        private const val TAG_DRAFT = "draft"
        private const val TAG_OLD_TYPING = "typing"
        private const val TAG_OLD_TYPED_READY = "typed_ready"
        private const val TAG_OLD_VOICE_READY = "voice_ready"
    }
}

/** True once the words are worth acting on: at least two characters that are not spaces. */
internal fun String.isMeaningfulRequest(): Boolean = trim().length >= 2

@Composable
fun rememberAskStateHolder(): AskStateHolder =
    rememberSaveable(saver = AskStateHolder.Saver) { AskStateHolder() }
