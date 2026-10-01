package com.aaravsekhri.dot.courses

/**
 * Dot's courses: small, practical lessons in the apps people ask about most.
 *
 * What is authored here is what a lesson is *for* — its objective, a short
 * explanation for the learner, how the screen shows it is done, and the limits
 * it keeps. What is not authored is how to do it on any particular phone:
 * button names, positions and paths differ between versions, launchers,
 * languages and layouts, and the adaptive guide finds them on the screen in
 * front of the learner. Nothing here is a coordinate or a guessed label.
 *
 * Every lesson cites the official help pages its explanation and objective
 * were checked against, with the date they were reviewed. Package names and
 * publishers were checked against each app's Google Play listing.
 *
 * IDs are stable and never reused: saved progress is keyed by them. When a
 * lesson's meaning changes, its [Lesson.version] goes up (see
 * [CourseProgressStore] for what that does to saved progress).
 */
object CourseCatalog {

    /** When the sources below were last read. */
    const val REVIEWED = "2026-09-29"

    /** Rules every lesson keeps, on top of its own. */
    /** A public place with rich details, for practising search without anyone needing a destination. */
    const val MAPS_PLACE = "Smithsonian National Museum of Natural History, Washington, DC"

    /** A public starting point for practising directions without location access. */
    const val MAPS_ORIGIN = "Union Station, Washington, DC"

    val COMMON_RULES = listOf(
        "The learner chooses every person, photo, audience and personal word; never choose those for them. The lesson's own harmless practice text may be suggested exactly as given.",
        "Never send, post, call, buy, delete or accept terms for them: ask before the final step, then let them press it.",
    )

    val chatGpt = Course(
        id = "chatgpt",
        version = 1,
        title = "ChatGPT: Your everyday helper",
        summary = "Ask questions in plain words, and get clearer answers by saying what you need.",
        art = CourseArt.Helper,
        app = KnownApp(
            name = "ChatGPT",
            packageName = "com.openai.chatgpt",
            publisher = "OpenAI",
            lookalikes = listOf("Many apps use \"ChatGPT\" or \"AI chat\" in their names; the official one is published by OpenAI."),
        ),
        lessons = listOf(
            Lesson(
                id = "chatgpt.setup",
                title = "Find the app and get set up",
                learned = "You got ChatGPT ready!",
                purpose = "By the end of this lesson, you'll know how to open ChatGPT and get it ready.",
                result = "This is ChatGPT's chat screen. The message box is where you'll type your questions.",
                explanation = "ChatGPT is an app you can ask questions in your own words. First we make sure the official one is on your phone and ready.",
                objective = "Open the official ChatGPT app (published by OpenAI) and reach the screen where a new chat can be typed. Install it from the Play Store only if it is not on the phone; sign in or sign up only if the app asks.",
                doneWhen = "The ChatGPT app is open on its chat screen, with a message box ready to type in.",
                rules = listOf(
                    "Install only the app published by OpenAI.",
                    "Signing in or signing up is the learner's choice: ask whether they already have an account before guiding either.",
                ),
                sources = listOf(
                    Source("https://help.openai.com/en/articles/8167604-how-to-find-and-install-the-chatgpt-android-app-on-the-google-play-store", "Search Play for OpenAI ChatGPT; check it is published by OpenAI."),
                    Source("https://help.openai.com/en/articles/8142208-chatgpt-android-app-faq", "Android app basics."),
                    Source("https://help.openai.com/en/articles/7426629-why-cant-i-log-in-to-chatgpt", "Sign-in methods; keep the method used to sign up."),
                    Source("https://help.openai.com/en/articles/7947663-chatgpt-supported-countries", "Availability by country."),
                ),
            ),
            Lesson(
                id = "chatgpt.question",
                title = "Ask an everyday question",
                learned = "You asked ChatGPT your first question!",
                purpose = "By the end of this lesson, you'll know how to ask ChatGPT a question.",
                result = "ChatGPT's answer is under your question; long answers carry on further down. It can make mistakes, so check anything important.",
                explanation = "You can ask ChatGPT the way you'd ask a friend. Type a question about something you want to know, then read the answer.",
                objective = "In ChatGPT, type the practice question (or the learner's own everyday question), send it to ChatGPT, and read the answer together.",
                doneWhen = "ChatGPT's answer to the practice question (or the learner's own) is showing under it in the chat.",
                needsAccount = true,
                version = 3,
                fallback = "If ChatGPT says it is busy or a limit is reached, say so plainly and suggest trying again later.",
                practice = Practice(
                    intro = "Let's practice by asking which vegetables are easy to grow on a windowsill.",
                    example = "What are three easy vegetables to grow on a windowsill?",
                    exampleKey = "chatgpt.question",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "This message box is where your question goes. It goes to ChatGPT, not to a person.",
                        "Never type private details, like passwords or card numbers, into a chat.",
                    ),
                    ownChoice = "an everyday question of the learner's own",
                ),
                sources = listOf(
                    Source("https://help.openai.com/en/articles/12677804-what-is-chatgpt-faq", "What ChatGPT is and how chats work."),
                    Source("https://help.openai.com/en/articles/9275245-chatgpt-free-tier-faq", "Everyday chat on the free tier."),
                ),
            ),
            Lesson(
                id = "chatgpt.better-prompt",
                title = "Say what you need",
                learned = "You asked for a clearer answer!",
                purpose = "By the end of this lesson, you'll know how to ask ChatGPT for a clearer answer.",
                result = "The answer came back as a short list because you asked for one. Saying who it's for and how you'd like it works for any question.",
                explanation = "Answers get better when you add a little detail — who it's for, and how you'd like it: a short list, a few steps, or simple words.",
                objective = "In ChatGPT, send the practice question, which says who it is for and asks for a short list (or the learner's own question with those two things), and look at how the answer is laid out.",
                doneWhen = "ChatGPT's answer to the question with context and a requested format is showing, laid out as asked (for the practice, a short list).",
                needsAccount = true,
                version = 3,
                practice = Practice(
                    intro = "Let's practice by asking for the easiest windowsill vegetables, as a short list.",
                    example = "I'm a beginner with a sunny windowsill. Which three vegetables are easiest to grow? Answer as a short list.",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Saying who it is for, like \"I'm a beginner\", helps the answer fit you.",
                        "Asking for a shape, like \"a short list\", makes the answer easier to read.",
                    ),
                    ownChoice = "their own question that says who it is for and how the answer should look",
                ),
                sources = listOf(
                    Source("https://help.openai.com/en/articles/10032626-prompt-engineering-best-practices-for-chatgpt", "Be clear and specific; add context."),
                    Source("https://help.openai.com/en/articles/4936848-how-do-i-create-a-good-prompt-for-an-ai-model", "Refine step by step."),
                ),
            ),
            Lesson(
                id = "chatgpt.follow-up",
                title = "Ask a follow-up",
                learned = "You asked a follow-up!",
                purpose = "By the end of this lesson, you'll know how to ask ChatGPT to change its answer.",
                result = "ChatGPT wrote a new, shorter answer below the first one. You can keep asking follow-ups in the same chat.",
                explanation = "ChatGPT remembers what was said earlier in the same chat, so you can ask it to change or explain its answer without starting again.",
                objective = "In the same ChatGPT chat, send the practice follow-up asking to make the last answer shorter and simpler (or the learner's own follow-up), and see ChatGPT change it.",
                doneWhen = "A new answer below the earlier one in the same chat responds to the follow-up (for the practice, a shorter version).",
                needsAccount = true,
                version = 3,
                fallback = "If the earlier chat is gone, ask the practice question from the last lesson first, then follow up.",
                practice = Practice(
                    intro = "Let's practice by asking ChatGPT to make its last answer shorter and simpler.",
                    example = "Please make that shorter and simpler.",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Within one chat, ChatGPT remembers what was said earlier.",
                        "That's why a follow-up can be short: ChatGPT knows what \"that\" means.",
                    ),
                    ownChoice = "their own follow-up, such as asking it to explain one part",
                ),
                sources = listOf(
                    Source("https://help.openai.com/en/articles/12677804-what-is-chatgpt-faq", "ChatGPT remembers context within a chat."),
                ),
            ),
            Lesson(
                id = "chatgpt.image",
                title = "Create a picture",
                learned = "You asked for a picture!",
                purpose = "By the end of this lesson, you'll know how to ask ChatGPT to make a picture.",
                result = "ChatGPT made this picture from your words; it isn't a real photo. A different description makes a different picture.",
                explanation = "ChatGPT can make a picture from a description. How many you can make depends on your account, and it may not be offered everywhere.",
                objective = "In ChatGPT, ask for a picture with the practice description (or the learner's own) and wait for it to appear.",
                doneWhen = "A picture made by ChatGPT, matching the description, is showing in the chat.",
                needsAccount = true,
                rules = listOf(
                    "If image creation is not offered on this account, or a limit has been reached, say so plainly (blocked, unavailable) rather than suggesting workarounds or paid plans.",
                ),
                version = 3,
                fallback = "If pictures cannot be made here, say so plainly and move on; the rest of the course does not need it.",
                practice = Practice(
                    intro = "Let's practice by asking for a painting of a cup of tea on a sunny windowsill.",
                    example = "Please make a watercolour painting of a cup of tea on a sunny windowsill.",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "A good description says what should be in the picture and its style, like a painting or a drawing.",
                        "A picture can take a little while to appear.",
                    ),
                    ownChoice = "their own description of a scene or an object, not of a real person",
                ),
                sources = listOf(
                    Source("https://help.openai.com/en/articles/11084440-images-in-chatgpt", "Image creation; limits differ by plan."),
                    Source("https://help.openai.com/en/articles/9275245-chatgpt-free-tier-faq", "Free-tier limits."),
                ),
            ),
        ),
    )

    val whatsApp = Course(
        id = "whatsapp",
        version = 1,
        title = "WhatsApp: Stay close to family",
        summary = "Message the people you care about, share a photo, and call when you'd like to.",
        art = CourseArt.Chat,
        app = KnownApp(
            name = "WhatsApp",
            packageName = "com.whatsapp",
            publisher = "WhatsApp LLC",
            lookalikes = listOf(
                "WhatsApp Business is a separate official app for businesses.",
                "Modified copies such as \"GB WhatsApp\" are not official; WhatsApp warns against them.",
            ),
        ),
        lessons = listOf(
            Lesson(
                id = "whatsapp.setup",
                title = "Get set up",
                learned = "You got WhatsApp ready!",
                purpose = "By the end of this lesson, you'll know how to open WhatsApp and get it ready.",
                result = "This is your Chats list. Every conversation you have on WhatsApp appears here.",
                explanation = "WhatsApp uses your phone number. If it isn't set up yet, it sends a code to your phone to check the number is yours.",
                objective = "Open WhatsApp Messenger (published by WhatsApp LLC) and reach the Chats list. Install it only if it is not on the phone; register the phone number only if WhatsApp asks.",
                doneWhen = "WhatsApp is open on its Chats list.",
                rules = listOf(
                    "The verification code is private: the learner reads and enters it themselves.",
                    "If this phone cannot receive a text or call for the code, say so plainly (blocked).",
                ),
                sources = listOf(
                    Source("https://faq.whatsapp.com/684051319521343/?cms_platform=android", "Registering a phone number with a 6-digit code."),
                    Source("https://faq.whatsapp.com/1217634902127718", "Unofficial copies of WhatsApp."),
                ),
            ),
            Lesson(
                id = "whatsapp.find-contact",
                title = "Find someone to message",
                learned = "You opened a chat!",
                purpose = "By the end of this lesson, you'll know how to open a chat with someone in WhatsApp.",
                result = "The chat is open and ready. The name at the top shows who messages in this chat go to.",
                explanation = "Anyone in your contacts who uses WhatsApp can be messaged. You start a new chat and pick them from the list.",
                objective = "In WhatsApp, start a new chat and open a chat with the learner themselves (WhatsApp lists their own number as \"You\") for practice, or with a person the learner chooses.",
                doneWhen = "A chat is open with the learner themselves or with the person they chose, with the message box showing.",
                needsAccount = true,
                rules = listOf("Offer the learner's own chat for practice, or ask who they want to message; never pick anyone else for them."),
                version = 3,
                fallback = "If the learner's own chat is not listed on this phone, they can choose someone they are happy to message, or stop once they have seen the list.",
                practice = Practice(
                    intro = "Let's practice by opening your own chat, the one marked \"You\".",
                    checkpoints = listOf(
                        "This list shows the people in your contacts who use WhatsApp.",
                        "Search finds someone from the first few letters of their name.",
                        "Your own chat, marked \"You\", is a safe place to practise: messages there only reach you.",
                    ),
                    ownChoice = "a person the learner wants to message",
                ),
                sources = listOf(
                    Source("https://faq.whatsapp.com/1183494482518500/?cms_platform=android", "Starting a chat with a contact."),
                    Source("https://faq.whatsapp.com/5472030609512325/?cms_platform=android", "Finding contacts."),
                ),
            ),
            Lesson(
                id = "whatsapp.send-message",
                title = "Write and send a message",
                learned = "You wrote a WhatsApp message!",
                purpose = "By the end of this lesson, you'll know how to write a message in WhatsApp.",
                result = "A message with a tick beside it in the chat has been sent. Words still in the message box have not been sent.",
                explanation = "Type your message in the box at the bottom of the chat. It only goes when you press send.",
                objective = "In the open WhatsApp chat, type the practice message (or the learner's own words). Send it only if the learner says they are ready; otherwise leave it as an unsent draft.",
                doneWhen = "The practice message (or the learner's words) shows in the chat as sent, or sits typed and unsent in the message box and the learner says they are finished.",
                needsAccount = true,
                rules = listOf("Ask before sending; the learner presses send."),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by writing \"Hello! I'm learning to use WhatsApp.\"",
                    example = "Hello! I'm learning to use WhatsApp.",
                    exampleKey = "whatsapp.message",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Your words wait in this box as you type; nothing is sent yet.",
                        "A message goes only when the send button is pressed. In your own chat, it only reaches you.",
                    ),
                    ownChoice = "their own words",
                    stopAt = "the typed message, unsent, unless the learner chooses to send it",
                ),
                sources = listOf(
                    Source("https://faq.whatsapp.com/453914586839706/?cms_platform=android", "Sending messages and media in a chat."),
                ),
            ),
            Lesson(
                id = "whatsapp.share-photo",
                title = "Share a photo",
                learned = "You got a photo ready to share!",
                purpose = "By the end of this lesson, you'll know how to share a photo in WhatsApp.",
                result = "A photo is only shared once send is pressed. If it's still in the preview, nothing has been sent.",
                explanation = "You can send a photo from your phone in any chat. You choose the picture, and it only goes when you press send.",
                objective = "In a WhatsApp chat the learner chooses (their own chat is a safe place to practise), attach one photo the learner picks, such as a photo of an everyday object, and send it only when they are ready.",
                doneWhen = "The chosen photo shows in the chat as sent, or its preview is open ready to send and the learner decides to stop there.",
                needsAccount = true,
                rules = listOf("The learner picks the photo; never pick one for them.", "Ask before sending."),
                version = 3,
                fallback = "If there are no photos on the phone yet, suggest taking one of something harmless, like a cup or a plant, first; or skip this lesson.",
                practice = Practice(
                    intro = "Let's practice with a photo of something everyday, like a cup or a plant.",
                    checkpoints = listOf(
                        "These are the photos on your phone; you choose which one to share.",
                        "A caption is optional: you can add a few words or leave it blank.",
                        "Nothing goes until you press send.",
                    ),
                    ownChoice = "any photo the learner is happy to share, to the chat they choose",
                    stopAt = "the photo preview, before send, unless the learner chooses to send it",
                ),
                sources = listOf(
                    Source("https://faq.whatsapp.com/453914586839706/?cms_platform=android", "Attach, choose from the gallery, send."),
                ),
            ),
            Lesson(
                id = "whatsapp.call",
                title = "Make a voice or video call",
                learned = "You found the call buttons!",
                purpose = "By the end of this lesson, you'll know how to start a voice or video call in WhatsApp.",
                result = "The call buttons are at the top of every chat. A call only starts when you press one.",
                explanation = "WhatsApp can call over the internet, with or without video. A call only starts if you choose to press call.",
                objective = "Show the learner where to start a voice or video call with a person they choose in WhatsApp. Only guide them to press call if they say they want to call now; otherwise stop at showing where it is.",
                doneWhen = "The learner has found the call buttons for their chosen person and either started a call they wanted, or said they know where it is.",
                needsAccount = true,
                learnerConfirms = true,
                rules = listOf(
                    "Never start a call unless the learner says they want to call now.",
                    "Calls use internet data; mention it once if they are about to call.",
                ),
                version = 2,
                practice = Practice(
                    intro = "Let's find where calls start.",
                    checkpoints = listOf(
                        "In a chat, the phone-shaped button is a voice call and the camera-shaped one is a video call.",
                        "Pressing one starts ringing straight away, so only press it when you want to call.",
                        "The red button ends a call at any time.",
                    ),
                    ownChoice = "calling someone the learner wants to talk to now",
                    stopAt = "showing where the call buttons are; never press call unless the learner wants to call now",
                ),
                sources = listOf(
                    Source("https://faq.whatsapp.com/1153602608602452/?cms_platform=android", "Voice calls."),
                    Source("https://faq.whatsapp.com/1862285217468140/?cms_platform=android", "Video calls."),
                ),
            ),
        ),
    )

    val facebook = Course(
        id = "facebook",
        version = 1,
        title = "Facebook: Your first post",
        summary = "Find your way around your feed, choose who sees what you share, and post when you're ready.",
        art = CourseArt.Post,
        app = KnownApp(
            name = "Facebook",
            packageName = "com.facebook.katana",
            publisher = "Meta Platforms, Inc.",
            lookalikes = listOf("Facebook Lite and Messenger are separate apps from Meta; this course uses the Facebook app."),
        ),
        lessons = listOf(
            Lesson(
                id = "facebook.setup",
                title = "Get set up",
                learned = "You got Facebook ready!",
                purpose = "By the end of this lesson, you'll know how to open Facebook and get it ready.",
                result = "This is your home feed. Posts from friends, groups and pages you follow appear here.",
                explanation = "To use Facebook you log in to your account. If you don't have one yet, you can make one — only if you want to.",
                objective = "Open the Facebook app (published by Meta Platforms, Inc.) and reach the home feed. Install it only if it is not on the phone. Ask whether the learner already has a Facebook account before guiding sign-in or sign-up.",
                doneWhen = "Facebook is open on its home feed while signed in.",
                rules = listOf(
                    "Ask whether they already have an account; do not assume either way.",
                    "Creating an account is the learner's choice; they type their own details and read the terms themselves.",
                ),
                sources = listOf(
                    Source("https://www.facebook.com/help/android-app/794433138234667", "Getting the Facebook app for Android."),
                    Source("https://www.facebook.com/help/android-app/185285184853471", "Logging in."),
                    Source("https://www.facebook.com/help/android-app/188157731232424", "Creating an account."),
                ),
            ),
            Lesson(
                id = "facebook.feed",
                title = "Understand your feed",
                learned = "You looked around your feed!",
                purpose = "By the end of this lesson, you'll know how to look through your Facebook feed.",
                result = "Your feed changes as people share new posts. Looking through it never posts or likes anything.",
                explanation = "Your feed is the list of posts from friends, groups and pages you follow. You scroll it up and down, and nothing is posted by looking.",
                objective = "In Facebook's home feed, help the learner scroll a little and point out who a post is from and where its reactions and comments are. Nothing is liked, commented on or shared.",
                doneWhen = "The learner has looked through a few posts in the feed and says they are ready to move on.",
                needsAccount = true,
                learnerConfirms = true,
                rules = listOf("Do not like, react, comment or share anything in this lesson."),
                version = 2,
                practice = Practice(
                    intro = "Let's practice looking around your feed.",
                    checkpoints = listOf(
                        "Scrolling gently moves through the posts; looking changes nothing.",
                        "Each post begins with the name of who shared it.",
                        "Every post has its own reactions and comments; reading them is fine, pressing them is not needed.",
                    ),
                    stopAt = "looking only; nothing liked, commented on or shared",
                ),
                sources = listOf(
                    Source("https://www.facebook.com/help/android-app/753701661398957", "What Feed is."),
                    Source("https://www.facebook.com/help/android-app/1155510281178725", "Using Feed."),
                ),
            ),
            Lesson(
                id = "facebook.audience",
                title = "Choose who sees your post",
                learned = "You chose who sees your post!",
                purpose = "By the end of this lesson, you'll know how to choose who sees your Facebook posts.",
                result = "The audience you chose is shown on the new post. Nothing has been posted.",
                explanation = "Before you post, you can choose who sees it — for example everyone, only your friends, or only you. It's worth checking every time.",
                objective = "Start a new post in Facebook and open its audience setting; the learner chooses who would see it. Do not post.",
                doneWhen = "The new-post screen shows the audience the learner chose, with nothing posted.",
                needsAccount = true,
                rules = listOf("The learner chooses the audience; explain the choices without recommending one.", "Do not post in this lesson."),
                version = 2,
                practice = Practice(
                    intro = "Let's practice choosing who sees a post.",
                    checkpoints = listOf(
                        "The audience says who can see a post, such as Public, Friends, or Only me.",
                        "Public means anyone, even people you don't know; Only me means just you.",
                    ),
                    stopAt = "the new-post screen showing the chosen audience; never post",
                ),
                sources = listOf(
                    Source("https://www.facebook.com/help/android-app/120939471321735", "Choosing an audience for a post."),
                    Source("https://www.facebook.com/help/android-app/211513702214269", "Audience options."),
                ),
            ),
            Lesson(
                id = "facebook.prepare-post",
                title = "Write a post",
                learned = "You wrote your first draft!",
                purpose = "By the end of this lesson, you'll know how to write a post on Facebook.",
                result = "Your post is written and waiting. Nothing has been posted, so no one else can see it.",
                explanation = "A post can be a few words, a photo, or both. You can write it and look it over before anything is shared.",
                objective = "In a new Facebook post, type the practice words (or the learner's own) and, only if they want, add one photo they pick. Do not post yet.",
                doneWhen = "The new-post screen shows the practice words (or the learner's own), and any photo they chose, not yet posted.",
                needsAccount = true,
                rules = listOf("The learner may use the practice words or choose their own words, and chooses any photo.", "Do not post in this lesson."),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by writing \"Hello everyone! I'm learning to use Facebook.\"",
                    example = "Hello everyone! I'm learning to use Facebook.",
                    exampleKey = "facebook.post",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The writing box is where the post goes; nothing is shared while you type.",
                        "A photo is optional; a post can be words alone.",
                    ),
                    ownChoice = "their own words, and a photo they pick",
                    stopAt = "the written draft, not posted",
                ),
                sources = listOf(
                    Source("https://www.facebook.com/help/android-app/170116376402147", "Posting text."),
                    Source("https://www.facebook.com/help/android-app/174641285926169", "Adding photos to a post."),
                ),
            ),
            Lesson(
                id = "facebook.publish",
                title = "Check it and post",
                learned = "You checked your post!",
                purpose = "By the end of this lesson, you'll know how to check a Facebook post and decide whether to post it.",
                result = "If you posted, it's now on Facebook and can be edited later. If you didn't, nothing was shared.",
                explanation = "Read your post and check who it's shared with. Post only if you're happy — and you can edit it afterwards.",
                objective = "With the draft post open (the practice words or the learner's own), check the words and the audience together, then ask whether they want to post it. Only if they say yes, guide them to post.",
                doneWhen = "The learner's post appears in their feed or profile, or the learner decides not to post and says they are finished.",
                needsAccount = true,
                learnerConfirms = true,
                rules = listOf("Posting only when the learner explicitly chooses to. Deciding not to post is a fine ending."),
                version = 3,
                fallback = "If the draft was closed, write it again with the practice words before checking it.",
                practice = Practice(
                    intro = "Let's check the post before deciding.",
                    example = "Hello everyone! I'm learning to use Facebook.",
                    exampleKey = "facebook.post",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The audience shown with the post says who will see it once it's posted.",
                        "Posting is your choice; leaving without posting is fine too.",
                    ),
                    stopAt = "the checked draft, unless the learner explicitly chooses to post",
                ),
                sources = listOf(
                    Source("https://www.facebook.com/help/android-app/170116376402147", "Choose audience, then Post."),
                    Source("https://www.facebook.com/help/android-app/462476073850410", "Editing a post afterwards."),
                ),
            ),
        ),
    )

    val photos = Course(
        id = "photos",
        version = 1,
        title = "Google Photos: Find your memories",
        summary = "Find a photo, look closely, keep your favorites, and share one with someone.",
        art = CourseArt.Photo,
        app = KnownApp(name = "Google Photos", packageName = "com.google.android.apps.photos", publisher = "Google LLC", label = "Photos"),
        lessons = listOf(
            Lesson(
                id = "photos.find",
                title = "Find a photo",
                learned = "You found a photo!",
                purpose = "By the end of this lesson, you'll know how to find a photo in Google Photos.",
                result = "Your photo is open full screen. Going back returns you to all your photos.",
                explanation = "Photos shows the pictures on your phone. You can scroll through them by date, or search for words like \"beach\".",
                objective = "In Google Photos, open the most recent photo for practice (or one the learner wants to see, found by scrolling or searching).",
                doneWhen = "A photo is open full screen in Google Photos.",
                version = 3,
                fallback = "If there are no photos on the phone yet, say so plainly; the Camera course shows how to take one.",
                practice = Practice(
                    intro = "Let's practice by opening your most recent photo.",
                    checkpoints = listOf(
                        "Your photos are grouped by date, with the newest first.",
                        "Search can find words like \"beach\", but scrolling always works too.",
                    ),
                    ownChoice = "a photo the learner would like to see",
                ),
                sources = listOf(
                    Source("https://support.google.com/photos/answer/6220402?hl=en&co=GENIE.Platform%3DAndroid", "Browsing photos."),
                    Source("https://support.google.com/photos/answer/6306652?hl=en&co=GENIE.Platform%3DAndroid", "Searching photos."),
                    Source("https://support.google.com/photos/answer/6193313?hl=en&co=GENIE.Platform%3DAndroid", "Backup is optional; on-device photos show without it."),
                ),
            ),
            Lesson(
                id = "photos.zoom",
                title = "Look closer",
                learned = "You zoomed in and out!",
                purpose = "By the end of this lesson, you'll know how to zoom in and out on a photo.",
                result = "Zooming only changes your view; the photo stays the same. The same pinch works in many apps, like Maps and web pages.",
                explanation = "With a photo open, you can spread two fingers apart to zoom in, and pinch them together to zoom out.",
                objective = "With a photo open in Google Photos, show the learner how to zoom in by spreading two fingers and back out by pinching.",
                doneWhen = "The learner has zoomed in and out on the open photo and says they have the hang of it.",
                learnerConfirms = true,
                version = 3,
                practice = Practice(
                    intro = "Let's practice looking closer at a photo.",
                    checkpoints = listOf(
                        "Zooming in makes part of the photo bigger, so small details are easier to see.",
                        "While zoomed in, one finger moves the picture to show another part.",
                    ),
                    ownChoice = "any photo the learner likes",
                ),
                sources = listOf(
                    // No official article describes pinch-to-zoom in Photos;
                    // this lesson teaches the standard Android gesture and is
                    // confirmed by the learner rather than claimed from a page.
                    Source("https://support.google.com/photos/answer/6220402?hl=en&co=GENIE.Platform%3DAndroid", "Viewing a photo (no official zoom article; confirmed by the learner)."),
                ),
            ),
            Lesson(
                id = "photos.favorite",
                title = "Keep a favorite",
                learned = "You kept a favorite!",
                purpose = "By the end of this lesson, you'll know how to keep a photo as a favorite in Google Photos.",
                result = "This photo is marked as a favorite. Every favorite is kept together in Favorites, so it's easy to find again.",
                explanation = "Marking a photo as a favorite puts it in your Favorites, so it's easy to find again.",
                objective = "In Google Photos, mark the open photo (or one the learner chooses) as a favorite, then find it in Favorites.",
                doneWhen = "The photo shows as a favorite, or appears in the Favorites collection.",
                version = 2,
                fallback = "If Favorites is hard to find on this version, it is enough that the photo shows as a favorite.",
                practice = Practice(
                    intro = "Let's practice keeping a favorite.",
                    checkpoints = listOf(
                        "The favorite button, a star or a heart depending on the version, marks the photo.",
                        "Tapping it again takes the mark away, so nothing is lost.",
                        "Favorites gathers every marked photo in one place.",
                    ),
                    ownChoice = "a photo the learner likes",
                ),
                sources = listOf(
                    Source("https://support.google.com/photos/answer/9458709?hl=en&co=GENIE.Platform%3DAndroid", "The Favorites collection."),
                ),
            ),
            Lesson(
                id = "photos.share",
                title = "Share a photo",
                learned = "You learned how to share a photo!",
                purpose = "By the end of this lesson, you'll know how to share a photo from Google Photos.",
                result = "Sharing sends a copy; your photo stays in Google Photos. If you didn't press send, nothing was shared.",
                explanation = "Sharing sends a copy of a photo to someone, through an app you choose. It only goes when you press send.",
                objective = "In Google Photos, open a photo the learner chooses, use Share, and pick an app and a person they choose (themselves, for example by email to their own address, is a safe practice), sending only when they are ready.",
                doneWhen = "The share was sent to the learner's chosen person, or the learner decides to stop before sending and says they are finished.",
                learnerConfirms = true,
                rules = listOf("The learner chooses the photo, the app and the person.", "Ask before sending."),
                version = 2,
                practice = Practice(
                    intro = "Let's practice sharing a photo.",
                    checkpoints = listOf(
                        "Share shows the apps and people a photo can go to.",
                        "Sending it to yourself is a safe way to try it.",
                        "Nothing goes until the final send in the app you picked.",
                    ),
                    ownChoice = "a person and app of the learner's choosing",
                    stopAt = "the message in the chosen app, ready but unsent, unless the learner chooses to send it",
                ),
                sources = listOf(
                    Source("https://support.google.com/photos/answer/6131416?hl=en&co=GENIE.Platform%3DAndroid", "Sharing photos: in Photos, to another app, or a link."),
                ),
            ),
        ),
    )

    val maps = Course(
        id = "maps",
        version = 1,
        title = "Google Maps: Find your way",
        summary = "Look up a place, see its details, and preview how to get there.",
        art = CourseArt.Map,
        app = KnownApp(name = "Google Maps", packageName = "com.google.android.apps.maps", publisher = "Google LLC", label = "Maps"),
        lessons = listOf(
            Lesson(
                id = "maps.search",
                title = "Search for a place",
                learned = "You found your first place!",
                purpose = "By the end of this lesson, you'll know how to find a place in Google Maps.",
                result = "The red pin shows exactly where the museum is. You can search for any place this way.",
                explanation = "Type the name of a place — a shop, a park, an address — and Maps shows where it is.",
                objective = "In Google Maps, practise searching: type the practice place into the search box, search, and show that place on the map.",
                doneWhen = "Maps shows the practice place — the one with the practice name and city — on the map or in its place card.",
                rules = listOf(
                    "If Maps asks for location permission, explain the choice and let the learner decide; searching works without it.",
                    "Choose the result only when its name and city match the practice place.",
                ),
                version = 3,
                fallback = "If the practice place cannot be found, search for a well-known public landmark in the learner's own town instead.",
                practice = Practice(
                    intro = "Let's practice with the Smithsonian museum in Washington, DC.",
                    example = MAPS_PLACE,
                    exampleKey = "maps.place",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "This search box can find places and addresses.",
                        "The name and city show which result is the right one.",
                    ),
                    ownChoice = "a public place the learner would like to find instead",
                ),
                sources = listOf(
                    Source("https://support.google.com/maps/answer/3092445?hl=en&co=GENIE.Platform%3DAndroid", "Searching for places."),
                    Source("https://support.google.com/android/answer/6179507?hl=en", "Location permission choices."),
                ),
            ),
            Lesson(
                id = "maps.details",
                title = "Check the details",
                learned = "You read a place's details!",
                purpose = "By the end of this lesson, you'll know how to check a place's hours and address in Google Maps.",
                result = "This page has what Maps knows about the museum: hours, address, photos and reviews. Every place has a page like it.",
                explanation = "Each place has a page with its opening hours, address, photos and reviews.",
                objective = "In Google Maps, open the details of the practice place and point out its address and opening hours.",
                doneWhen = "The details page of the practice place is open.",
                version = 3,
                fallback = "If the place has no hours listed, point out its address and photos instead.",
                practice = Practice(
                    intro = "Let's see what Maps knows about the Smithsonian museum.",
                    example = MAPS_PLACE,
                    exampleKey = "maps.place",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "This section tells you when the museum opens and closes.",
                        "The address tells you exactly where it is.",
                    ),
                    ownChoice = "a public place the learner would like to look at instead",
                ),
                sources = listOf(
                    Source("https://support.google.com/maps/answer/3092445?hl=en&co=GENIE.Platform%3DAndroid", "More information about a place."),
                    Source("https://support.google.com/maps/answer/6230175?hl=en&co=GENIE.Platform%3DAndroid", "Place details."),
                ),
            ),
            Lesson(
                id = "maps.mode",
                title = "Choose how you'll travel",
                learned = "You compared ways to travel!",
                purpose = "By the end of this lesson, you'll know how to compare ways to travel in Google Maps.",
                result = "Maps shows how long the trip takes the way you picked. Nothing has started; this is just a plan.",
                explanation = "Directions can be for driving, public transport, walking or cycling. Not every choice is offered everywhere.",
                objective = "In Google Maps, open directions to the practice place from the practice starting point, then try the walking and transit choices. Do not start navigation.",
                doneWhen = "Directions to the practice place are showing, with a travel mode the learner picked.",
                rules = listOf(
                    "Tap Directions once; never press and hold it, which starts navigation straight away.",
                    "Do not press Start.",
                    "If Maps asks for a starting point, the practice starting point can be typed; the learner's own location is their choice, never required.",
                ),
                version = 3,
                fallback = "If a travel mode is not offered here, show the modes that are and explain that not every choice is available everywhere.",
                practice = Practice(
                    intro = "Let's see the ways to get from Union Station to the Smithsonian museum.",
                    example = MAPS_PLACE,
                    exampleKey = "maps.place",
                    typeText = MAPS_ORIGIN,
                    checkpoints = listOf(
                        "Directions need a start and an end. For practice, the start can be $MAPS_ORIGIN.",
                        "The little pictures at the top are the ways to travel: car, transit, walking, bike.",
                        "Each way shows how long it would take.",
                    ),
                    ownChoice = "a starting point the learner prefers, such as their own location",
                    stopAt = "the directions overview; never press Start",
                ),
                sources = listOf(
                    Source("https://support.google.com/maps/answer/144339?hl=en&co=GENIE.Platform%3DAndroid", "Getting directions and choosing a mode."),
                ),
            ),
            Lesson(
                id = "maps.preview",
                title = "Preview the route",
                learned = "You previewed a route!",
                purpose = "By the end of this lesson, you'll know how to preview a route in Google Maps before you go.",
                result = "These are the steps of the route, from start to finish. You can look through them any time before you set off.",
                explanation = "Before you set off, you can look through the route step by step without starting anything.",
                objective = "In Google Maps directions to the practice place, show the list of steps or the route preview. Do not start navigation.",
                doneWhen = "The steps or preview of the route to the practice place are showing, without navigation started.",
                rules = listOf("Do not press Start."),
                version = 3,
                fallback = "If there is no step list here, show the route on the map instead.",
                practice = Practice(
                    intro = "Let's look through the route from Union Station to the museum.",
                    example = MAPS_PLACE,
                    exampleKey = "maps.place",
                    typeText = MAPS_ORIGIN,
                    checkpoints = listOf(
                        "Each step is one turn or stretch of the trip, in order.",
                        "Looking through the steps changes nothing; only Start begins navigation.",
                    ),
                    stopAt = "the steps list or route preview; never press Start",
                ),
                sources = listOf(
                    Source("https://support.google.com/maps/answer/144339?hl=en&co=GENIE.Platform%3DAndroid", "Previewing a route; navigation starts only on Start."),
                ),
            ),
        ),
    )

    val gmail = Course(
        id = "gmail",
        version = 1,
        title = "Gmail: Send your first email",
        summary = "Open your inbox, write to someone, check it and send, then reply to a message.",
        art = CourseArt.Mail,
        app = KnownApp(name = "Gmail", packageName = "com.google.android.gm", publisher = "Google LLC"),
        lessons = listOf(
            Lesson(
                id = "gmail.inbox",
                title = "Open your inbox",
                learned = "You opened your inbox!",
                purpose = "By the end of this lesson, you'll know how to open your inbox in Gmail.",
                result = "This is your inbox. New emails appear at the top, and ones you haven't read are in bold.",
                explanation = "Gmail shows your email in the inbox, newest at the top. If no account is added yet, you add one first.",
                objective = "Open Gmail and reach the inbox. Add an email account only if Gmail asks for one, and only with the learner's own account.",
                doneWhen = "Gmail is open on an inbox.",
                needsAccount = true,
                rules = listOf("Passwords and codes are entered by the learner on private screens."),
                sources = listOf(
                    Source("https://support.google.com/mail/answer/8494?hl=en&co=GENIE.Platform%3DAndroid", "Adding an account to Gmail."),
                    Source("https://support.google.com/mail/answer/6078445?hl=en&co=GENIE.Platform%3DAndroid", "Using several accounts."),
                ),
            ),
            Lesson(
                id = "gmail.compose",
                title = "Write an email",
                learned = "You wrote your first email draft!",
                purpose = "By the end of this lesson, you'll know how to write an email in Gmail.",
                result = "Your email is written but not sent. Gmail keeps it as a draft until you choose to send it.",
                explanation = "A new email needs who it's to, a subject that says what it's about, and your message.",
                objective = "In Gmail, start a new email to the learner's own address for practice (or someone they choose), with the subject \"Practice email\" and the practice message. Do not send yet.",
                doneWhen = "A draft email shows a recipient the learner typed, a subject and a message, not yet sent.",
                needsAccount = true,
                rules = listOf(
                    "The learner chooses the recipient and types the address themselves; their own address is the suggested practice.",
                    "The learner may use the practice subject and message or their own words.",
                    "Do not send in this lesson.",
                ),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by writing a \"Practice email\" to your own address.",
                    example = "Practice email",
                    exampleKey = "gmail.subject",
                    typeText = "Hello! I'm learning to use Gmail.",
                    checkpoints = listOf(
                        "To is who it goes to. Your own address is a safe way to practise.",
                        "The subject is a few words saying what it's about; for practice, \"Practice email\".",
                        "The message is the main part; nothing is sent while you type.",
                    ),
                    ownChoice = "a recipient and words the learner chooses",
                    stopAt = "the unsent draft",
                ),
                sources = listOf(
                    Source("https://support.google.com/mail/answer/2819488?hl=en&co=GENIE.Platform%3DAndroid", "Writing an email."),
                ),
            ),
            Lesson(
                id = "gmail.send",
                title = "Check it and send",
                learned = "You checked your email!",
                purpose = "By the end of this lesson, you'll know how to check an email and decide whether to send it in Gmail.",
                result = "If you sent it, it's in your Sent folder. If you didn't, it's waiting in Drafts and nothing was sent.",
                explanation = "Read your email through and check the address before sending. Right after sending, Gmail briefly offers to undo.",
                objective = "With the practice draft (or the learner's own) open in Gmail, check the recipient and words together, then ask whether to send; only if they say yes, guide them to send.",
                doneWhen = "Gmail shows the email as sent, or the learner decides not to send and says they are finished.",
                needsAccount = true,
                learnerConfirms = true,
                rules = listOf("Send only when the learner explicitly chooses to."),
                version = 3,
                fallback = "If the draft was closed, Gmail keeps it in Drafts; open it from there.",
                practice = Practice(
                    intro = "Let's check the practice email before deciding.",
                    example = "Practice email",
                    exampleKey = "gmail.subject",
                    checkpoints = listOf(
                        "One wrong letter in the To address sends the email to someone else, so it's worth checking closely.",
                        "An email to your own address arrives in your own inbox.",
                        "Right after sending, Undo shows for a few seconds.",
                    ),
                    stopAt = "the checked draft, unless the learner explicitly chooses to send it",
                ),
                sources = listOf(
                    Source("https://support.google.com/mail/answer/2819488?hl=en&co=GENIE.Platform%3DAndroid", "Send, and the brief Undo."),
                ),
            ),
            Lesson(
                id = "gmail.reply",
                title = "Find and reply to a message",
                learned = "You wrote a reply!",
                purpose = "By the end of this lesson, you'll know how to find an email and reply to it in Gmail.",
                result = "If you sent your reply, it joins the same conversation. If you didn't, it waits as a draft and nothing was sent.",
                explanation = "You can search your email for a name or a word, open the message, and reply to it.",
                objective = "In Gmail, search for the practice email (or a message the learner wants to answer), open it, and write a reply with the practice words (or their own), sending only when they are ready.",
                doneWhen = "A reply to the chosen message is sent, or it is written and the learner decides not to send and says they are finished.",
                needsAccount = true,
                learnerConfirms = true,
                rules = listOf("The learner chooses which message and what to say.", "Ask before sending."),
                version = 3,
                fallback = "If the practice email was not sent, search for any message the learner is happy to answer, or stop once the reply is written.",
                practice = Practice(
                    intro = "Let's practice by finding your \"Practice email\" and replying to it.",
                    example = "Practice email",
                    exampleKey = "gmail.subject",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Search finds emails by a name or a word in them.",
                        "Reply writes back to whoever sent the email. For practice, you could write: \"Thank you, this is my practice reply.\"",
                        "Nothing goes until you press send.",
                    ),
                    ownChoice = "a message the learner wants to answer, and their own words",
                    stopAt = "the written reply, unsent, unless the learner chooses to send it",
                ),
                sources = listOf(
                    Source("https://support.google.com/mail/answer/6593?hl=en&co=GENIE.Platform%3DAndroid", "Searching email."),
                    Source("https://support.google.com/mail/answer/6585?hl=en&co=GENIE.Platform%3DAndroid", "Replying."),
                ),
            ),
        ),
    )

    val youTube = Course(
        id = "youtube",
        version = 1,
        title = "YouTube: Watch what interests you",
        summary = "Search for a topic, control the video, turn on captions, and save one for later.",
        art = CourseArt.Video,
        app = KnownApp(name = "YouTube", packageName = "com.google.android.youtube", publisher = "Google LLC"),
        lessons = listOf(
            Lesson(
                id = "youtube.search",
                title = "Search for a topic",
                learned = "You found a video!",
                purpose = "By the end of this lesson, you'll know how to find a video on YouTube.",
                result = "Your video is open. You can search for any topic this way, from recipes to music.",
                explanation = "Search for anything you're curious about — a recipe, a song, a place — and pick a video from the results.",
                objective = "In YouTube, search for the practice topic (or the learner's own) and open a video from the results that is not an ad.",
                doneWhen = "A video about the practice topic (or the learner's own) is open.",
                version = 3,
                practice = Practice(
                    intro = "Let's practice by finding a video on how to make a cup of tea.",
                    example = "how to make a cup of tea",
                    exampleKey = "youtube.topic",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Results marked Ad or Sponsored are adverts; you don't need to open them.",
                        "Each result shows the video's title, who made it, and how long it is.",
                    ),
                    ownChoice = "a topic the learner is curious about",
                ),
                sources = listOf(
                    Source("https://support.google.com/youtube/answer/2398242?hl=en&co=GENIE.Platform%3DAndroid", "Searching YouTube."),
                ),
            ),
            Lesson(
                id = "youtube.play-pause",
                title = "Play and pause",
                learned = "You paused and played a video!",
                purpose = "By the end of this lesson, you'll know how to pause and play a video on YouTube.",
                result = "Pausing keeps your place, so the video carries on from the same spot when you play it again.",
                explanation = "Tap the video once to show its controls, then tap pause or play.",
                objective = "With the practice video (or any video) open in YouTube, show the learner how to bring up the controls and pause, then play again.",
                doneWhen = "The learner has paused and played the video.",
                learnerConfirms = true,
                version = 3,
                practice = Practice(
                    intro = "Let's practice pausing and playing the video about making tea.",
                    example = "how to make a cup of tea",
                    exampleKey = "youtube.topic",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Tapping the video once shows its controls; they hide again after a moment.",
                        "The same button pauses and plays.",
                        "An ad may play first; many can be skipped after a few seconds.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/youtube/answer/9047376?hl=en&co=GENIE.Platform%3DAndroid", "Video player controls."),
                ),
            ),
            Lesson(
                id = "youtube.captions",
                title = "Turn on captions",
                learned = "You tried captions!",
                purpose = "By the end of this lesson, you'll know how to turn on captions on YouTube.",
                result = "When a video has captions, they show the spoken words along the video. The same button turns them off again.",
                explanation = "Captions show the words being spoken. Not every video has them.",
                objective = "With the practice video (or any video) open in YouTube, turn captions on using the video's captions control.",
                doneWhen = "Words are showing along the video as captions, or it is clear this video has no captions.",
                rules = listOf("If this video has no captions control, say captions aren't available for this video; do not invent another way."),
                version = 3,
                practice = Practice(
                    intro = "Let's practice turning on captions for the video about making tea.",
                    example = "how to make a cup of tea",
                    exampleKey = "youtube.topic",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The captions control, often marked CC, turns the words on and off.",
                        "Captions made automatically can have a few wrong words.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/youtube/answer/100078?hl=en&co=GENIE.Platform%3DAndroid", "Turning captions on; no button means none are available."),
                ),
            ),
            Lesson(
                id = "youtube.save",
                title = "Save a video for later",
                learned = "You saved a video for later!",
                purpose = "By the end of this lesson, you'll know how to save a YouTube video to watch later.",
                result = "The video is saved in your list, so you can find it again later without searching.",
                explanation = "Saving puts a video in a list, such as Watch later, so you can find it again. You need to be signed in.",
                objective = "In YouTube, save the practice video (or one the learner chooses) to Watch later or another list.",
                doneWhen = "YouTube confirms the video was saved, or it shows in Watch later.",
                needsAccount = true,
                rules = listOf(
                    "Never suggest signing out: on Android that signs the account out of other Google apps too.",
                    "If saving isn't offered for this video or account, say so plainly (blocked, unavailable).",
                ),
                version = 3,
                practice = Practice(
                    intro = "Let's practice saving the video about making tea for later.",
                    example = "how to make a cup of tea",
                    exampleKey = "youtube.topic",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Save puts the video in a list; Watch later is one that is ready-made.",
                        "A saved video waits in that list, and can be removed from it at any time.",
                    ),
                    ownChoice = "a video the learner likes",
                ),
                sources = listOf(
                    Source("https://support.google.com/youtube/answer/56101?hl=en&co=GENIE.Platform%3DAndroid", "Saving videos; some videos can't be saved."),
                    Source("https://support.google.com/youtube/answer/3802431?hl=en&co=GENIE.Platform%3DAndroid", "Playlists need a signed-in account."),
                ),
            ),
        ),
    )

    val calendar = Course(
        id = "calendar",
        version = 1,
        title = "Google Calendar: Remember an event",
        summary = "Find a date, add an event, get a reminder, and change it later.",
        art = CourseArt.Calendar,
        app = KnownApp(
            name = "Google Calendar",
            packageName = "com.google.android.calendar",
            publisher = "Google LLC",
            label = "Calendar",
            lookalikes = listOf("Some phones come with a different calendar app, such as Samsung Calendar; this course uses Google Calendar."),
        ),
        lessons = listOf(
            Lesson(
                id = "calendar.date",
                title = "Find a date",
                learned = "You found a date!",
                purpose = "By the end of this lesson, you'll know how to find a date in Google Calendar.",
                result = "The calendar is showing the date you chose. Any events on that day appear here.",
                explanation = "The calendar can show a day, a week or a month. You can move forward and back to find any date.",
                objective = "In Google Calendar, go to tomorrow for practice (or a date the learner chooses).",
                doneWhen = "Google Calendar is showing tomorrow's date (or the date the learner chose).",
                needsAccount = true,
                version = 3,
                practice = Practice(
                    intro = "Let's practice by finding tomorrow's date.",
                    checkpoints = listOf(
                        "Day, week and month views show the same calendar, closer or further away.",
                        "Swiping moves forward or back in time.",
                        "The Today button always brings you back to today.",
                    ),
                    ownChoice = "a date the learner would like to see",
                ),
                sources = listOf(
                    Source("https://support.google.com/calendar/answer/6110849?hl=en&co=GENIE.Platform%3DAndroid", "Moving between dates; jump to today."),
                    Source("https://support.google.com/calendar/answer/2465776?hl=en&co=GENIE.Platform%3DAndroid", "Signing in to use Google Calendar."),
                ),
            ),
            Lesson(
                id = "calendar.create",
                title = "Add an event",
                learned = "You added your first event!",
                purpose = "By the end of this lesson, you'll know how to add an event to Google Calendar.",
                result = "Your event is on the calendar at the day and time you chose. No one else was invited or told.",
                explanation = "An event has a name, a day and a time. Save it and it appears on your calendar.",
                objective = "In Google Calendar, create the practice event for tomorrow at 3 pm (or with a title, day and time the learner chooses) and save it. Do not add guests.",
                doneWhen = "The practice event (or the learner's own) shows on the calendar at the day and time chosen.",
                needsAccount = true,
                rules = listOf(
                    "The learner may use the practice title and time, or choose their own title, date and time.",
                    "Never add guests or send invitations.",
                ),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by adding \"Tea with a friend\" for tomorrow at 3 pm.",
                    example = "Practice: Tea with a friend",
                    exampleKey = "calendar.event",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The title says what the event is; \"Practice\" in it shows it's only for learning.",
                        "The day and time say when it happens; for practice, tomorrow at 3 pm.",
                        "Guests and a place are optional; without guests, the event is only on your calendar.",
                    ),
                    ownChoice = "their own title, day and time",
                    stopAt = "the saved event; never add guests",
                ),
                sources = listOf(
                    Source("https://support.google.com/calendar/answer/72143?hl=en&co=GENIE.Platform%3DAndroid", "Creating an event."),
                ),
            ),
            Lesson(
                id = "calendar.notify",
                title = "Get a reminder",
                learned = "You set a reminder!",
                purpose = "By the end of this lesson, you'll know how to get a reminder before an event in Google Calendar.",
                result = "Your phone will show a reminder before the event starts, at the time you chose.",
                explanation = "A notification on an event makes your phone remind you before it starts. Calendar calls these notifications.",
                objective = "In Google Calendar, open the practice event (or the learner's own), add a notification 1 hour before (or a time the learner chooses), and save.",
                doneWhen = "The event shows a notification at the time chosen (for practice, 1 hour before).",
                needsAccount = true,
                rules = listOf("If the phone has Calendar notifications turned off, mention that alerts won't appear until they're allowed; let the learner decide."),
                version = 3,
                fallback = "If the practice event can't be found, create it first as in the last lesson.",
                practice = Practice(
                    intro = "Let's practice adding a reminder to \"Tea with a friend\".",
                    example = "Practice: Tea with a friend",
                    exampleKey = "calendar.event",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "A notification is a reminder your phone shows before the event.",
                        "You choose how long before: minutes, hours or days.",
                        "An event may already have one; you can add another or change it.",
                    ),
                    ownChoice = "a different reminder time",
                ),
                sources = listOf(
                    Source("https://support.google.com/calendar/answer/37242?hl=en&co=GENIE.Platform%3DAndroid", "Adding event notifications."),
                ),
            ),
            Lesson(
                id = "calendar.edit",
                title = "Find and change an event",
                learned = "You changed an event!",
                purpose = "By the end of this lesson, you'll know how to find and change an event in Google Calendar.",
                result = "The event now shows your change. You can change an event like this whenever plans move.",
                explanation = "You can search your calendar for an event and change its details if plans move.",
                objective = "In Google Calendar, find the practice event (or the learner's own) by searching for its title or going to its date, open it, change its time to 4 pm (or another detail the learner chooses), and save.",
                doneWhen = "The event shows the new time (or the change the learner chose).",
                needsAccount = true,
                rules = listOf("Never add guests or send invitations."),
                version = 3,
                fallback = "If search is not offered, go to the event's date to find it.",
                practice = Practice(
                    intro = "Let's practice by moving \"Tea with a friend\" to 4 pm.",
                    example = "Practice: Tea with a friend",
                    exampleKey = "calendar.event",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Search finds an event by a word in its title.",
                        "A change only counts once it's saved; leaving without saving keeps the old details.",
                    ),
                    ownChoice = "another change the learner chooses",
                    stopAt = "the saved change; keeping or deleting the practice event afterwards is the learner's choice",
                ),
                sources = listOf(
                    Source("https://support.google.com/calendar/answer/37176?hl=en&co=GENIE.Platform%3DAndroid", "Searching for events."),
                    Source("https://support.google.com/calendar/answer/72143?hl=en&co=GENIE.Platform%3DAndroid", "Editing an event."),
                ),
            ),
        ),
    )

    val chrome = Course(
        id = "chrome",
        version = 1,
        title = "Chrome: Browse with confidence",
        summary = "Search the web, open a page, move between pages and tabs, and bookmark what you like.",
        art = CourseArt.Web,
        app = KnownApp(name = "Chrome", packageName = "com.android.chrome", publisher = "Google LLC"),
        lessons = listOf(
            Lesson(
                id = "chrome.search",
                title = "Search the web",
                learned = "You searched the web!",
                purpose = "By the end of this lesson, you'll know how to search the web in Chrome.",
                result = "These are web pages that match your search. You can search for anything this way.",
                explanation = "Type what you're looking for into the address bar at the top or bottom of Chrome, and it searches the web.",
                objective = "In Chrome, type the practice search (or the learner's own topic) into the address bar and search.",
                doneWhen = "Search results for the practice search (or the learner's topic) are showing in Chrome.",
                rules = listOf("If Chrome shows its first-run terms, the learner reads and accepts them themselves."),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by searching for today's weather.",
                    example = "weather today",
                    exampleKey = "chrome.search",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The address bar takes both web addresses and searches.",
                        "Suggestions appear as you type; they are guesses at what you're looking for.",
                    ),
                    ownChoice = "a topic the learner is curious about",
                ),
                sources = listOf(
                    Source("https://support.google.com/chrome/answer/95440?hl=en&co=GENIE.Platform%3DAndroid", "Searching from the address bar."),
                    Source("https://support.google.com/chrome/answer/14181646?hl=en&co=GENIE.Platform%3DAndroid", "The address bar can be at the top or bottom."),
                ),
            ),
            Lesson(
                id = "chrome.open-result",
                title = "Open a result",
                learned = "You opened a web page!",
                purpose = "By the end of this lesson, you'll know how to open a web page from your search results in Chrome.",
                result = "You're on a web page now. The address at the top shows which website it comes from.",
                explanation = "Tap a result to open the page. Ads are marked as sponsored; you don't have to open them.",
                objective = "In Chrome's results for the practice search (or the learner's), open a result the learner picks that is not marked Sponsored, unless they want it.",
                doneWhen = "A web page from the results is open in Chrome.",
                rules = listOf(
                    "If a page asks for personal details, to allow notifications, or to download something, don't; go back instead.",
                    "If a cookie or consent box appears, explain it and let the learner choose; declining is fine.",
                ),
                version = 3,
                fallback = "If the results are gone, search for the practice words again.",
                practice = Practice(
                    intro = "Let's practice by opening a page about today's weather.",
                    example = "weather today",
                    exampleKey = "chrome.search",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "Each result shows a title and the website it comes from.",
                        "Results marked Sponsored are ads; you don't need to open them.",
                    ),
                    ownChoice = "a result the learner would like to read",
                ),
                sources = listOf(
                    Source("https://support.google.com/chrome/answer/95440?hl=en&co=GENIE.Platform%3DAndroid", "Opening results."),
                ),
            ),
            Lesson(
                id = "chrome.back-tabs",
                title = "Go back, and use tabs",
                learned = "You moved between pages and tabs!",
                purpose = "By the end of this lesson, you'll know how to go back and switch between tabs in Chrome.",
                result = "Each tab is a page you have open. Keeping a few open is fine, and closing one loses nothing else.",
                explanation = "Back takes you to the page before. Tabs let you keep more than one page open and switch between them.",
                objective = "In Chrome, go back to the previous page, then open a new tab, switch between tabs, and close the extra one.",
                doneWhen = "The learner has gone back a page and switched between tabs.",
                learnerConfirms = true,
                version = 2,
                practice = Practice(
                    intro = "Let's practice moving between pages.",
                    checkpoints = listOf(
                        "Back returns to the page before, like turning back a page in a book.",
                        "The tabs button shows your open pages; each one is a tab.",
                        "Closing a tab only closes that page; nothing else is lost.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/android/answer/9079644?hl=en", "Going back on Android."),
                    Source("https://support.google.com/chrome/answer/2391819?hl=en&co=GENIE.Platform%3DAndroid", "Opening, switching and closing tabs."),
                ),
            ),
            Lesson(
                id = "chrome.bookmark",
                title = "Bookmark a page",
                learned = "You bookmarked a page!",
                purpose = "By the end of this lesson, you'll know how to bookmark a page in Chrome.",
                result = "The page is in your bookmarks now. You can open it from there any time, without searching again.",
                explanation = "A bookmark saves a page so you can come back to it in one tap.",
                objective = "In Chrome, bookmark the page that is open (or one the learner likes), then find it in Bookmarks.",
                doneWhen = "The page appears in Chrome's bookmarks list.",
                version = 2,
                practice = Practice(
                    intro = "Let's practice saving a page.",
                    checkpoints = listOf(
                        "The star saves the open page as a bookmark; a filled star means it's saved.",
                        "Bookmarks, in Chrome's menu, lists every saved page.",
                    ),
                    ownChoice = "a page the learner likes",
                ),
                sources = listOf(
                    Source("https://support.google.com/chrome/answer/188842?hl=en&co=GENIE.Platform%3DAndroid", "Adding and finding bookmarks."),
                ),
            ),
        ),
    )

    val messages = Course(
        id = "messages",
        version = 1,
        title = "Google Messages: Text someone",
        summary = "Start a conversation, write a text, check it, and send a photo.",
        art = CourseArt.Text,
        app = KnownApp(
            name = "Google Messages",
            packageName = "com.google.android.apps.messaging",
            publisher = "Google LLC",
            label = "Messages",
            lookalikes = listOf("Some phones use a different texting app; Google Messages needs to be the default texting app to send."),
        ),
        lessons = listOf(
            Lesson(
                id = "messages.start",
                title = "Start a conversation",
                learned = "You started a conversation!",
                purpose = "By the end of this lesson, you'll know how to start a conversation in Messages.",
                result = "Nothing has been sent. A conversation keeps all your texts with one person together.",
                explanation = "Pick someone from your contacts, or type a phone number, to start a conversation.",
                objective = "In Google Messages, open a conversation with a person the learner chooses, starting a new one if needed. If Messages asks to become the default texting app, explain and let the learner decide.",
                doneWhen = "A conversation with the chosen person is open, with the message box showing, or the new-conversation screen is showing and the learner has decided not to pick anyone yet.",
                rules = listOf("Ask who to text; never pick someone.", "Changing the default texting app is the learner's choice."),
                version = 2,
                fallback = "If the learner has no one they want to text yet, show where a new conversation starts and stop there.",
                practice = Practice(
                    intro = "Let's practice starting a conversation.",
                    checkpoints = listOf(
                        "Starting a chat shows your contacts; typing a few letters of a name finds someone.",
                        "Choose someone you'd be happy to text; nothing is sent yet.",
                    ),
                    ownChoice = "a person the learner wants to text",
                ),
                sources = listOf(
                    Source("https://support.google.com/messages/answer/6080324", "Starting a conversation."),
                    Source("https://support.google.com/messages/answer/6089066", "Messages as the default texting app."),
                ),
            ),
            Lesson(
                id = "messages.compose",
                title = "Write a text",
                learned = "You wrote a text!",
                purpose = "By the end of this lesson, you'll know how to write a text in Messages.",
                result = "Your text is written in the box and nothing has been sent. It waits there until you choose to send it.",
                explanation = "Type your text in the box at the bottom. Nothing is sent until you press send.",
                objective = "In the open Google Messages conversation, type the practice text (or the learner's own words). Do not send yet.",
                doneWhen = "The practice text (or the learner's words) is in the message box, not yet sent.",
                rules = listOf("Do not send in this lesson."),
                version = 3,
                practice = Practice(
                    intro = "Let's practice by writing \"Hello! I'm learning to send text messages.\"",
                    example = "Hello! I'm learning to send text messages.",
                    exampleKey = "messages.text",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The message box is where the text is written.",
                        "Nothing is sent while you type; you can delete and try again.",
                    ),
                    ownChoice = "their own words",
                    stopAt = "the typed text, unsent",
                ),
                sources = listOf(
                    Source("https://support.google.com/messages/answer/6080324", "Writing a message."),
                ),
            ),
            Lesson(
                id = "messages.send",
                title = "Check it and send",
                learned = "You checked your text!",
                purpose = "By the end of this lesson, you'll know how to check a text and decide whether to send it.",
                result = "If you sent it, your text shows in the conversation. If you didn't, it's still in the box and nothing was sent.",
                explanation = "Read it through, then send when you're ready. The conversation shows it once it's gone.",
                objective = "With the practice text (or the learner's own) typed, check it together and ask whether to send; only if they say yes, guide them to send.",
                doneWhen = "The text appears in the conversation as sent, or the learner decides not to send and says they are finished.",
                learnerConfirms = true,
                rules = listOf(
                    "Send only when the learner explicitly chooses to.",
                    "Texts can cost money on some phone plans; mention it once if they are about to send.",
                ),
                version = 3,
                fallback = "If the typed text is gone, type the practice text again before checking it.",
                practice = Practice(
                    intro = "Let's check the text before deciding.",
                    example = "Hello! I'm learning to send text messages.",
                    exampleKey = "messages.text",
                    typeText = "{example}",
                    checkpoints = listOf(
                        "The name at the top shows who the text will go to.",
                        "Sending is your choice; leaving it unsent is fine too.",
                    ),
                    stopAt = "the checked text, unless the learner explicitly chooses to send it",
                ),
                sources = listOf(
                    Source("https://support.google.com/messages/answer/6080324", "Sending."),
                    Source("https://support.google.com/messages/answer/7189714", "Chat features and plain texts."),
                ),
            ),
            Lesson(
                id = "messages.photo",
                title = "Send a photo",
                learned = "You added a photo to a text!",
                purpose = "By the end of this lesson, you'll know how to add a photo to a text in Messages.",
                result = "If you sent it, the photo shows in the conversation. If you didn't, it's waiting in the message box and nothing was sent.",
                explanation = "You can add a photo from your phone to a text. You choose it, and it goes only when you press send.",
                objective = "In a Google Messages conversation the learner chooses, attach one photo they pick, such as a photo of an everyday object, and send it only when they are ready.",
                doneWhen = "The photo appears in the conversation as sent, or the learner decides not to send and says they are finished.",
                learnerConfirms = true,
                rules = listOf("The learner picks the photo.", "Ask before sending."),
                version = 3,
                fallback = "If there are no photos on the phone yet, suggest taking one of something harmless, like a cup or a plant, first; or skip this lesson.",
                practice = Practice(
                    intro = "Let's practice with a photo of something everyday, like a cup or a plant.",
                    checkpoints = listOf(
                        "These are the photos on your phone; the one you choose is the one that's added.",
                        "The photo waits in the message box until you press send.",
                    ),
                    ownChoice = "any photo the learner is happy to share",
                    stopAt = "the photo attached but unsent, unless the learner chooses to send it",
                ),
                sources = listOf(
                    Source("https://support.google.com/messages/answer/6159880", "Attaching photos."),
                ),
            ),
        ),
    )

    /**
     * Every course: the first ten in the order they were first published,
     * then the ones added after, each group appended so no earlier id moves. Learn groups them by category; the first
     * is where Dot suggests starting.
     */
    val courses: List<Course> by lazy {
        listOf(
            chatGpt.copy(category = CourseCategory.AiTools),
            whatsApp.copy(category = CourseCategory.StayConnected),
            facebook.copy(category = CourseCategory.StayConnected),
            photos.copy(category = CourseCategory.PhotosAndCreativity),
            maps.copy(category = CourseCategory.EverydayEssentials),
            gmail.copy(category = CourseCategory.StayConnected),
            youTube.copy(category = CourseCategory.EverydayEssentials),
            calendar.copy(category = CourseCategory.EverydayEssentials),
            chrome.copy(category = CourseCategory.EverydayEssentials),
            messages.copy(category = CourseCategory.StayConnected),
        ) + MoreCourses.all + AiCourses.all + CreativeCourses.all
    }

    fun course(id: String): Course? = courses.firstOrNull { it.id == id }

    fun lesson(courseId: String, lessonId: String): Lesson? = course(courseId)?.lessons?.firstOrNull { it.id == lessonId }

    /** Every app a course is about that can be looked up, once each. */
    val apps: List<KnownApp> get() = courses.map { it.app }.filter { it.packageName.isNotBlank() }.distinctBy { it.packageName }
}

data class Course(
    val id: String,
    val version: Int,
    val title: String,
    val summary: String,
    val art: CourseArt,
    val app: KnownApp,
    val lessons: List<Lesson>,
    val category: CourseCategory = CourseCategory.StayConnected,
) {
    /** The lessons counted in "N of M completed": optional extensions are extra. */
    val coreLessons: List<Lesson> get() = lessons.filterNot { it.optional }
}

/** The simple headings Learn groups its courses under, in the order shown. */
/**
 * Learn's headings and filters, in the order they are shown. Held only in
 * code — never stored — so a heading can be renamed or regrouped freely.
 */
enum class CourseCategory { EverydayEssentials, StayConnected, PhotosAndCreativity, AiTools, MakeItYours }

/**
 * One lesson: what it is for, not how to do it on any particular phone.
 *
 * @property explanation shown to the learner before they start: one idea,
 *   in everyday words.
 * @property objective what the guide works towards, sent to the planner.
 * @property doneWhen what on screen shows it is done — the only way a lesson
 *   is ever marked complete, apart from the learner saying so where
 *   [learnerConfirms] (for an outcome no screen can show).
 * @property needsAccount true when the lesson cannot be done signed out, so
 *   signing in is a prerequisite only here — and only if it is not done.
 */
data class Lesson(
    val id: String,
    val title: String,
    val explanation: String,
    val objective: String,
    val doneWhen: String,
    val rules: List<String> = emptyList(),
    val needsAccount: Boolean = false,
    val learnerConfirms: Boolean = false,
    val version: Int = 1,
    /** Official help pages this was checked against, reviewed [CourseCatalog.REVIEWED]. */
    val sources: List<Source> = emptyList(),
    /**
     * What to do when the lesson cannot be done as written — the feature is
     * missing, a limit is reached, the phone works differently. Sent to the
     * guide so it explains and moves on rather than inventing steps.
     */
    val fallback: String = "",
    /** An extension beyond the course's core; not counted in its progress. */
    val optional: Boolean = false,
    /**
     * The id of an authored, step-by-step guide that teaches this lesson,
     * used instead of the adaptive guide. The one guide Dot ships, text size,
     * is taught this way rather than duplicated.
     */
    val guideId: String? = null,
    /**
     * How the lesson is practised, so a beginner never has to invent a place,
     * a note or a search just to learn. Null only for a lesson that needs
     * nothing made up — a setting to find, a guide Dot ships.
     */
    val practice: Practice? = null,
    /**
     * One short line, said when the lesson is completed, naming what the
     * learner did: "You found your first place!". Blank falls back to the title.
     */
    val learned: String = "",
    /**
     * The lesson's purpose, said on its first card before anything is done:
     * "By the end of this lesson, you'll know how to find a place in Google
     * Maps." One sentence. Blank: the lesson starts with its practice.
     */
    val purpose: String = "",
    /**
     * What the result means, said on a card once the screen shows the
     * lesson done and before it is celebrated: "The red pin shows exactly
     * where the museum is." One or two sentences. Blank: no result card.
     */
    val result: String = "",
)

/**
 * Guided practice for one lesson: what is being learned, a safe example to
 * practise with, and a few short points said at the moment they matter.
 *
 * Nothing here names a control's position or one app version's sequence of
 * screens; the adaptive guide still finds the real controls on the phone.
 * Nothing here is personal: examples are public places, harmless words and
 * drafts. The learner may use their own instead, but never has to.
 *
 * @property intro one short sentence to begin with: "Let's practice finding a place."
 * @property example what is practised on, when the exercise needs something:
 *   a public place, a harmless phrase. Shared across a course's lessons by
 *   [exampleKey], so the place found in one lesson is the one opened in the next.
 * @property exampleKey the name the example is kept under for the course.
 *   Lessons with the same key practise on the same thing.
 * @property typeText exactly what to type, when the practice types something.
 *   "{example}" stands for the example in use, the learner's own if they chose one.
 * @property checkpoints short teaching points, each said when the screen reaches it.
 * @property ownChoice how the learner may use something of their own instead.
 * @property stopAt where practice ends before anything real happens: a draft
 *   left unsent, a preview not posted.
 */
data class Practice(
    val intro: String,
    val example: String = "",
    val exampleKey: String = "",
    val typeText: String = "",
    val checkpoints: List<String> = emptyList(),
    val ownChoice: String = "",
    val stopAt: String = "",
) {
    /** What to type, with the example in use put in. */
    fun textFor(exampleInUse: String): String = typeText.replace("{example}", exampleInUse)
}

/**
 * An app a course is about, as its Google Play listing names it.
 *
 * @property label what the phone usually shows under its icon.
 * @property lookalikes notes for telling the official app from others.
 */
data class KnownApp(
    val name: String,
    val packageName: String,
    val publisher: String,
    val label: String = name,
    val lookalikes: List<String> = emptyList(),
)

/** Where a lesson's facts come from. Developer-facing; never shown as instructions. */
data class Source(val url: String, val note: String)

/** Which small Pebble illustration a course has. */
enum class CourseArt {
    Helper, Chat, Post, Photo, Map, Mail, Video, Calendar, Web, Text,
    Camera, Clock, Notes, Folder, VideoCall, Translate, Contact, Phone, Music, Lens, Cloud, Store, Bell, Wifi, Reading,
    Draft, PictureQuestion, Sources,
    PhotoEdit, Invitation, HomeScreen,
}
