package com.aaravsekhri.dot.courses

/**
 * Three short courses on AI helpers, grouped under "AI tools" with ChatGPT.
 *
 * Each practises something safe and reversible: a message drafted and never
 * sent, a picture of an ordinary object looked at, a source opened. None
 * needs posting, paying or a password typed for Dot, and each says plainly
 * when the app, an account or a feature is not available rather than
 * pretending it was done.
 *
 * App identity and account rules were checked against the official Play
 * listings and help pages on [CourseCatalog.REVIEWED]. What is not assumed:
 * that a feature is free on every account, or that the app is in every
 * country. Where either differs, the lesson says so and stops.
 */
object AiCourses {

    /** Said on every AI course, at the moment it matters. */
    private const val CHECK_ANSWERS = "AI answers can be wrong. Read them before you use them, and never type passwords, card numbers or private details."

    val claude = Course(
        id = "claude",
        version = 1,
        title = "Claude: Draft a message",
        summary = "Ask Claude to draft an everyday message, then shape it until it sounds like you.",
        art = CourseArt.Draft,
        app = KnownApp(
            name = "Claude",
            packageName = "com.anthropic.claude",
            publisher = "Anthropic PBC",
            label = "Claude",
            lookalikes = listOf("Other apps use \"Claude\" in their names; the official one is \"Claude by Anthropic\", published by Anthropic PBC."),
        ),
        category = CourseCategory.AiTools,
        lessons = listOf(
            Lesson(
                id = "claude.setup",
                title = "Open Claude and get set up",
                learned = "You got Claude ready!",
                purpose = "By the end of this lesson, you'll know how to open Claude and reach its chat.",
                result = "This is Claude's chat. The message box at the bottom is where you'll type.",
                explanation = "Claude is an AI helper you can ask in plain words. It needs an account, and it's for adults 18 and over.",
                objective = "Open the official Claude app (\"Claude by Anthropic\", published by Anthropic PBC) and reach the screen where a new chat can be typed. Install it from the Play Store only if it is not on the phone. Signing in is the learner's own: guide them to the sign-in screen only if they want to, and never type their details.",
                doneWhen = "The Claude app is open on its chat screen, with a message box ready to type in.",
                needsAccount = true,
                rules = listOf(
                    "Install only the app published by Anthropic PBC.",
                    "Claude needs an account and is for adults 18 and over. If the learner has no account and does not want one, or Claude is not available where they are, say so plainly (blocked, unavailable) and stop.",
                ),
                fallback = "If Claude is not available in this country, or the learner cannot sign in, say so and end the lesson; nothing else in the course works without it.",
                sources = listOf(
                    Source("https://play.google.com/store/apps/details?id=com.anthropic.claude", "Official listing: Claude by Anthropic, Anthropic PBC."),
                    Source("https://support.claude.com/en/articles/9612887-install-claude-for-android", "Install Claude for Android; Android 8.0 or later."),
                    Source("https://support.claude.com/en/articles/13189465-log-in-to-your-claude-account", "Sign-in is required."),
                    Source("https://support.claude.com/en/articles/13117299-minimum-age-requirement-access-restriction", "Minimum age 18."),
                    Source("https://support.claude.com/en/articles/8461763-where-can-i-access-claude", "Supported countries."),
                ),
            ),
            Lesson(
                id = "claude.draft",
                title = "Ask for a draft",
                learned = "Claude drafted your first message!",
                purpose = "By the end of this lesson, you'll know how to ask Claude to draft a message for you.",
                result = "That's a draft, not a sent message: nothing has gone to anyone. You decide whether to use it.",
                explanation = "Tell Claude who the message is for and what you want to say, and it writes a draft you can change.",
                objective = "In Claude, type the practice request (or the learner's own everyday message to draft), send it to Claude, and read Claude's draft together. Do not send the draft to anyone.",
                doneWhen = "Claude's draft of the thank-you message (or the learner's own) is showing under the request in the chat.",
                needsAccount = true,
                rules = listOf(
                    "The draft stays in Claude. Do not send, share or copy it into a messaging app, and do not tap any preview card that would open another app to send it.",
                    "Never put a real person's contact details or anything private into the request.",
                ),
                fallback = "If Claude says a limit is reached or it is busy, say so plainly and suggest trying again later.",
                practice = Practice(
                    intro = "Let's practice by drafting a thank-you to a neighbour for watering your plants.",
                    example = "Please write a short, friendly message thanking my neighbour for watering my plants while I was away.",
                    exampleKey = "claude.draft",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "This box is where you ask Claude. It goes to Claude, not to your neighbour.",
                        CHECK_ANSWERS,
                    ),
                    ownChoice = "an everyday message of the learner's own to draft, without anyone's private details",
                    stopAt = "Claude's draft is showing; it is never sent.",
                ),
                sources = listOf(
                    Source("https://support.claude.com/en/articles/11869629-use-claude-with-android-apps", "Drafting messages is supported on all plans; the person sends it themselves."),
                ),
            ),
            Lesson(
                id = "claude.improve",
                title = "Make it sound like you",
                learned = "You made the draft your own!",
                purpose = "By the end of this lesson, you'll know how to ask Claude to change a draft.",
                result = "Claude rewrote the draft underneath the first one. When you're happy with one, you can copy it into a message yourself.",
                explanation = "If a draft isn't quite right, just say what to change: shorter, warmer, simpler. Claude remembers the draft in the same chat.",
                objective = "In the same Claude chat, send the practice follow-up asking to make the last draft shorter and warmer (or the learner's own change), and see Claude rewrite it. Do not send the draft to anyone.",
                doneWhen = "A new draft below the earlier one in the same chat reflects the change (for the practice, shorter and warmer).",
                needsAccount = true,
                rules = listOf(
                    "The draft stays in Claude. Do not send, share or copy it into a messaging app for them.",
                ),
                fallback = "If the earlier chat is gone, ask for the draft from the last lesson first, then ask for the change.",
                practice = Practice(
                    intro = "Let's practice by asking Claude to make the draft shorter and warmer.",
                    example = "Please make that a little shorter and warmer.",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Claude remembers the draft in this chat, so \"that\" is enough.",
                        "It's your message: read it and change any word before you use it.",
                    ),
                    ownChoice = "their own change, such as simpler words or a different greeting",
                    stopAt = "The rewritten draft is showing; it is never sent.",
                ),
                sources = listOf(
                    Source("https://support.claude.com/en/articles/11869629-use-claude-with-android-apps", "Drafts are previewed; nothing is sent without the person."),
                ),
            ),
        ),
    )

    val gemini = Course(
        id = "gemini",
        version = 1,
        title = "Gemini: Ask about a picture",
        summary = "Show Gemini a photo of something ordinary and ask what it is.",
        art = CourseArt.PictureQuestion,
        app = KnownApp(
            name = "Google Gemini",
            packageName = "com.google.android.apps.bard",
            publisher = "Google LLC",
            label = "Gemini",
            lookalikes = listOf("Some apps copy the Gemini name; the official one is \"Google Gemini\", published by Google LLC."),
        ),
        category = CourseCategory.AiTools,
        lessons = listOf(
            Lesson(
                id = "gemini.setup",
                title = "Open Gemini and get set up",
                learned = "You got Gemini ready!",
                purpose = "By the end of this lesson, you'll know how to open Gemini and reach its chat.",
                result = "This is Gemini's chat. The box at the bottom is where you type, and the add button beside it adds a picture.",
                explanation = "Gemini is Google's AI helper. On Android it uses your Google Account.",
                objective = "Open the official Google Gemini app (published by Google LLC) and reach the screen where a new chat can be typed. Install it from the Play Store only if it is not on the phone. Signing in is with the learner's own Google Account; never type their details.",
                doneWhen = "The Gemini app is open on its chat screen, with a message box ready to type in.",
                needsAccount = true,
                rules = listOf(
                    "Install only the app published by Google LLC.",
                    "Gemini needs a Google Account on Android. A work or school account may need the organisation to allow it; if it is not available, say so plainly (blocked, unavailable) and stop.",
                ),
                fallback = "If Gemini is not available on this phone, account or country, say so and end the lesson.",
                sources = listOf(
                    Source("https://play.google.com/store/apps/details?id=com.google.android.apps.bard", "Official listing: Google Gemini, Google LLC."),
                    Source("https://support.google.com/gemini/answer/14554984?co=GENIE.Platform%3DAndroid", "Sign in to use the Gemini mobile app."),
                    Source("https://support.google.com/gemini/answer/14579026?co=GENIE.Platform%3DAndroid", "Where Gemini is available; work and school accounts."),
                ),
            ),
            Lesson(
                id = "gemini.picture",
                title = "Ask about a picture",
                learned = "You asked Gemini about a picture!",
                purpose = "By the end of this lesson, you'll know how to show Gemini a photo and ask about it.",
                result = "Gemini answered from the photo. It can be wrong about what it sees, so treat it as a first guess.",
                explanation = "Take a photo of something ordinary near you, like a mug, a plant or a book cover, and ask Gemini about it.",
                objective = "In Gemini, use the add button beside the message box to take a new photo of an ordinary object nearby (or choose a harmless photo of an object the learner already has), type the practice question, send it, and read Gemini's answer.",
                doneWhen = "Gemini's answer about the photo is showing in the chat under the question.",
                needsAccount = true,
                rules = listOf(
                    "Only a photo of an ordinary object: no people, faces, children, documents, cards, screens or anything private. The learner chooses the photo.",
                    "If adding a picture is not offered on this account, say so plainly (blocked, unavailable) rather than suggesting paid plans.",
                ),
                fallback = "If pictures cannot be added here, say so and move on; the next lesson does not need one.",
                practice = Practice(
                    intro = "Let's practice with a photo of something ordinary near you, like a mug or a plant.",
                    example = "What is this, and what's one interesting thing about it?",
                    exampleKey = "gemini.picture",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "This button adds a picture. Choose a photo of an object, never of a person or a document.",
                        CHECK_ANSWERS,
                    ),
                    ownChoice = "their own question about the object in the photo",
                ),
                sources = listOf(
                    Source("https://support.google.com/gemini/answer/14903178?co=GENIE.Platform%3DAndroid", "Add files: Photos, Camera or Files, then submit."),
                ),
            ),
            Lesson(
                id = "gemini.check",
                title = "Ask how sure it is",
                learned = "You asked Gemini to double-check!",
                purpose = "By the end of this lesson, you'll know how to ask Gemini what to double-check.",
                result = "Gemini said what it might have got wrong. Asking this is a good habit with any AI answer.",
                explanation = "An AI can sound sure and still be wrong. Asking what to double-check helps you decide what to trust.",
                objective = "In the same Gemini chat, send the practice follow-up (or the learner's own) and read Gemini's answer about what could be wrong or worth checking.",
                doneWhen = "Gemini's answer to the follow-up is showing below the earlier answer in the same chat.",
                needsAccount = true,
                fallback = "If the earlier chat is gone, ask any everyday question first, then ask what to double-check.",
                practice = Practice(
                    intro = "Let's practice by asking Gemini what it might have got wrong.",
                    example = "How sure are you about that? What should I double-check?",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Gemini remembers the photo in this chat, so the follow-up can be short.",
                        "If something matters, check it somewhere you trust as well.",
                    ),
                    ownChoice = "their own follow-up asking how Gemini knows, or what to check",
                ),
                sources = listOf(
                    Source("https://support.google.com/gemini/answer/13278668", "Gemini Apps: responses may be inaccurate; double-check important information."),
                ),
            ),
        ),
    )

    val perplexity = Course(
        id = "perplexity",
        version = 1,
        title = "Perplexity: Check the sources",
        summary = "Ask a question, then look at where the answer came from.",
        art = CourseArt.Sources,
        app = KnownApp(
            name = "Perplexity",
            packageName = "ai.perplexity.app.android",
            publisher = "PerplexityAI",
            label = "Perplexity",
            lookalikes = listOf("Some apps copy the Perplexity name; the official one is \"Perplexity - Ask Anything\", published by PerplexityAI."),
        ),
        category = CourseCategory.AiTools,
        lessons = listOf(
            Lesson(
                id = "perplexity.setup",
                title = "Open Perplexity",
                learned = "You got Perplexity ready!",
                purpose = "By the end of this lesson, you'll know how to open Perplexity and reach its question box.",
                result = "This is where you ask Perplexity a question. Its answers show the websites they came from.",
                explanation = "Perplexity answers questions and shows its sources. Basic questions may work without an account.",
                objective = "Open the official Perplexity app (\"Perplexity - Ask Anything\", published by PerplexityAI) and reach the screen where a question can be typed. Install it from the Play Store only if it is not on the phone. If it asks to sign in, the learner chooses: skip if that is offered, or stop if they do not want to sign in; never type their details.",
                doneWhen = "The Perplexity app is open with its question box ready to type in.",
                rules = listOf(
                    "Install only the app published by PerplexityAI.",
                    "If the app will not go on without an account and the learner does not want one, say so plainly (blocked, unavailable) and stop.",
                ),
                fallback = "If Perplexity cannot be used here without an account the learner wants, say so and end the lesson.",
                sources = listOf(
                    Source("https://play.google.com/store/apps/details?id=ai.perplexity.app.android", "Official listing: Perplexity - Ask Anything, PerplexityAI."),
                    Source("https://www.perplexity.ai/help-center/en/articles/10352998-account-management-and-security", "Can be used without signing in; sign-in for Pro Search and uploads."),
                ),
            ),
            Lesson(
                id = "perplexity.question",
                title = "Ask a question",
                learned = "You asked Perplexity a question!",
                purpose = "By the end of this lesson, you'll know how to ask Perplexity a question.",
                result = "The small numbers in the answer point to the websites each part came from. Next, you'll open one.",
                explanation = "Ask a question the way you'd ask a librarian, then read the answer and notice the small numbers in it.",
                objective = "In Perplexity, type the practice question (or the learner's own), send it, and read the answer together, noticing its numbered citations.",
                doneWhen = "Perplexity's answer to the question is showing, with its sources or numbered citations.",
                fallback = "If a limit is reached or it asks to sign in to continue, say so plainly and stop.",
                practice = Practice(
                    intro = "Let's practice by asking why leaves change colour in autumn.",
                    example = "Why do leaves change colour in autumn?",
                    exampleKey = "perplexity.question",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Your question goes to Perplexity, which searches the web for the answer.",
                        CHECK_ANSWERS,
                    ),
                    ownChoice = "an everyday question of the learner's own",
                ),
                sources = listOf(
                    Source("https://www.perplexity.ai/help-center/en/articles/10352895-how-does-perplexity-work", "Answers include numbered citations linking to sources."),
                ),
            ),
            Lesson(
                id = "perplexity.sources",
                title = "Open a source",
                learned = "You checked where an answer came from!",
                purpose = "By the end of this lesson, you'll know how to see where an answer came from.",
                result = "This is where that part of the answer came from. If a source doesn't say what the answer claims, don't rely on it.",
                explanation = "Every answer lists its sources. Opening one shows you who said it, so you can decide whether to trust it.",
                objective = "In Perplexity, on the answer from the last lesson (or any answer), open its list of sources or one numbered citation, then open one source to see the website it came from. Do not sign in to or buy anything on the website.",
                doneWhen = "A source's details, or its website, is showing, opened from Perplexity's answer.",
                rules = listOf(
                    "Only read the website: do not sign in, subscribe, accept offers or download anything on it.",
                ),
                fallback = "If there is no earlier answer, ask the practice question from the last lesson first.",
                practice = Practice(
                    intro = "Let's practice by opening one of the sources behind the answer.",
                    checkpoints = listOf(
                        "Each number or source shows where part of the answer came from.",
                        "Look at who wrote the source. A trusted organisation is a good sign.",
                    ),
                    ownChoice = "any source from their own answer",
                ),
                sources = listOf(
                    Source("https://www.perplexity.ai/help-center/en/articles/10352895-how-does-perplexity-work", "Numbered citations link to the original sources."),
                    Source("https://www.perplexity.ai/help-center/en/articles/20260806-understanding-source-labels", "Source details and labels."),
                ),
            ),
        ),
    )

    val all: List<Course> = listOf(claude, gemini, perplexity)
}
