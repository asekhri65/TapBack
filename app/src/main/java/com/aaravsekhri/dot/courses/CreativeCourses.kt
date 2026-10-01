package com.aaravsekhri.dot.courses

/**
 * Three courses on making things look the way you want: a photo edited in
 * Lightroom, an invitation made in Canva, and the phone's own home screen.
 *
 * Each practises on something safe and undoable — a photo the learner is
 * happy to edit, a made-up party, a wallpaper that can be changed back — and
 * stays on the free parts: nothing paid, no trial, no AI generation, and
 * nothing sent, shared or published. Where a phone, an account or an app
 * works differently, the lesson says so plainly and moves on or stops,
 * rather than inventing steps.
 *
 * App identity, account rules, free features and control names were checked
 * against the official Play listings and help pages on [CourseCatalog.REVIEWED].
 * Not assumed: that a feature is free for every account and country, or
 * that every phone's home screen has the same options.
 */
object CreativeCourses {

    private const val NO_PAID = "Never start a trial, subscribe or pay. If a tool shows a crown, a Premium or Pro badge, or asks to upgrade, say it's a paid extra and choose another way or stop."

    val lightroom = Course(
        id = "lightroom",
        version = 1,
        title = "Lightroom: Brighten and straighten a photo",
        summary = "Crop, straighten and brighten a photo, compare it with the original, and save a copy.",
        art = CourseArt.PhotoEdit,
        category = CourseCategory.PhotosAndCreativity,
        app = KnownApp(
            name = "Adobe Lightroom",
            packageName = "com.adobe.lrmobile",
            publisher = "Adobe",
            label = "Lightroom",
            lookalikes = listOf("Many photo editors have similar names; the official one is \"Lightroom Photo & Video Editor\", published by Adobe."),
        ),
        lessons = listOf(
            Lesson(
                id = "lightroom.open",
                title = "Open a photo in Lightroom",
                learned = "You opened a photo in Lightroom!",
                purpose = "By the end of this lesson, you'll know how to open one of your photos in Lightroom.",
                result = "Your photo is open for editing. The tools along the bottom change it; the photo in your gallery stays as it was.",
                explanation = "Lightroom edits photos already on your phone. It needs a free Adobe account, and your original photo is kept.",
                objective = "Open the official Lightroom app (\"Lightroom Photo & Video Editor\" by Adobe), go to its Gallery of the phone's photos, and open one photo the learner is comfortable editing, such as a landscape, a flower or a view. Install it from the Play Store only if it is not on the phone. Signing in with an Adobe account is the learner's own: guide them to that screen only if they want to, and never type their details.",
                doneWhen = "A photo is open full screen in Lightroom with its editing tools showing.",
                needsAccount = true,
                rules = listOf(
                    "Install only the app published by Adobe.",
                    NO_PAID,
                    "Choose a photo of a place or a thing, never a document, a card or anything private.",
                ),
                fallback = "If the learner has no Adobe account and does not want one, or Lightroom will only continue with a trial or payment, say so plainly and end the course; nothing else works without it.",
                practice = Practice(
                    intro = "Let's open a photo you're happy to practise on.",
                    example = "a photo of a landscape, a flower or a view",
                    exampleKey = "photo",
                    checkpoints = listOf(
                        "Lightroom's Gallery shows the photos already on your phone; they stay where they are.",
                        "Pick a photo of a place or a thing, not a document or anything private.",
                    ),
                    ownChoice = "any photo on the phone the learner is happy to edit",
                ),
                sources = listOf(
                    Source("https://play.google.com/store/apps/details?id=com.adobe.lrmobile", "Official listing: Lightroom Photo & Video Editor, Adobe."),
                    Source("https://helpx.adobe.com/lightroom/mobile/get-started/download-and-install-lightroom.html", "Free to download with core editing tools; sign in with an Adobe account."),
                    Source("https://helpx.adobe.com/lightroom/mobile/add-and-capture-photos/add-and-import-photos/access-photo-gallery.html", "Gallery shows device photos, which stay in their original places."),
                    Source("https://helpx.adobe.com/lightroom/mobile/get-started/adobe-lightroom-on-mobile-faq.html", "What is free and what is Premium."),
                ),
            ),
            Lesson(
                id = "lightroom.crop",
                title = "Crop and straighten",
                learned = "You cropped and straightened your photo!",
                purpose = "By the end of this lesson, you'll know how to crop a photo and straighten a tilted one.",
                result = "Your photo is framed the way you chose. Crop again any time to change it; nothing is lost.",
                explanation = "Cropping trims the edges. Straightening turns the photo a little so the horizon is level.",
                objective = "With the photo open in Lightroom, open Crop, drag a corner or edge to trim the photo, and use the rotation wheel (or Aspect > More > Straighten) to level it, then tap Done.",
                doneWhen = "The learner has cropped or straightened the photo and tapped Done.",
                rules = listOf(
                    "Use Crop only. Do not open Geometry, which is a Premium tool.",
                    NO_PAID,
                ),
                fallback = "If Crop is not where described, explain that Lightroom sometimes moves its tools, and look along the bottom toolbar for Crop.",
                practice = Practice(
                    intro = "Let's trim the edges and level the photo.",
                    checkpoints = listOf(
                        "Dragging a corner trims that side; the photo underneath is not lost.",
                        "The wheel below turns the photo a little at a time; keep the horizon level.",
                        "Done keeps the change; you can crop again later.",
                    ),
                ),
                sources = listOf(
                    Source("https://helpx.adobe.com/lightroom/mobile/crop-and-transform/crop-photos.html", "Crop, Aspect, the cropping wheel, Done."),
                    Source("https://helpx.adobe.com/lightroom/mobile/crop-and-transform/straighten-flip-and-rotate-photos.html", "Aspect > More > Straighten."),
                    Source("https://helpx.adobe.com/lightroom/mobile/crop-and-transform/fix-geometric-perspective-in-lightroom-on-mobile.html", "Geometry is Premium."),
                ),
            ),
            Lesson(
                id = "lightroom.light",
                title = "Make it brighter",
                learned = "You brightened your photo!",
                purpose = "By the end of this lesson, you'll know how to make a photo brighter or darker.",
                result = "Your photo is as bright as you chose. Double-tapping a slider puts it back where it started.",
                explanation = "The Light settings change how bright a photo is. Exposure brightens or darkens the whole picture.",
                objective = "With the photo open in Lightroom, open Edit, then Light, and move the Exposure slider until the learner likes the brightness. Contrast, Highlights and Shadows are optional.",
                doneWhen = "The learner has adjusted Exposure and says the brightness is right.",
                learnerConfirms = true,
                rules = listOf(
                    "Use the Light sliders only. Do not use Masking, Remove or presets marked Premium.",
                    NO_PAID,
                ),
                fallback = "If Edit or Light looks different on this version, look for the sun-shaped Light section among the editing tools.",
                practice = Practice(
                    intro = "Let's make the photo a little brighter.",
                    checkpoints = listOf(
                        "Sliding Exposure to the right brightens the photo; to the left darkens it.",
                        "Small moves are enough; watch the photo as you slide.",
                        "Double-tapping a slider's handle puts it back.",
                    ),
                ),
                sources = listOf(
                    Source("https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/adjust-light-in-photos.html", "Edit > Light: Exposure, Contrast, Highlights, Shadows; double-tap to reset."),
                    Source("https://helpx.adobe.com/lightroom/mobile/work-with-presets/apply-presets.html", "Which presets are Premium."),
                ),
            ),
            Lesson(
                id = "lightroom.compare",
                title = "See before and after",
                learned = "You compared before and after!",
                purpose = "By the end of this lesson, you'll know how to compare your edit with the original photo.",
                result = "Holding shows the original; letting go shows your edit. It's a quick way to check a change is better.",
                explanation = "Pressing and holding the photo shows it as it was. Letting go shows your changes again.",
                objective = "With the edited photo open in Lightroom, press and hold on the photo to see the original, then let go to see the edit.",
                doneWhen = "The learner has held the photo to see the original and let go, and says they saw the difference.",
                learnerConfirms = true,
                practice = Practice(
                    intro = "Let's compare your edit with the original.",
                    checkpoints = listOf(
                        "Keep your finger on the photo to see the original.",
                        "Lift your finger to see your edit again.",
                    ),
                ),
                sources = listOf(
                    Source("https://helpx.adobe.com/lightroom/mobile/get-started/gesture-controls-in-lightroom-for-mobile.html", "Select and hold the image to see before and after."),
                ),
            ),
            Lesson(
                id = "lightroom.save",
                title = "Save a copy",
                learned = "You saved a copy of your photo!",
                purpose = "By the end of this lesson, you'll know how to save your edited photo to your phone.",
                result = "A copy with your changes is saved on your phone. The original photo is still there, unchanged.",
                explanation = "Saving a copy keeps your original and adds the edited photo to your phone.",
                objective = "In Lightroom, open the options menu for the photo and choose Save copy to device (or Share > Export as > Done), keeping the default settings.",
                doneWhen = "Lightroom says the copy was saved, or the learner sees the edited copy saved and says so.",
                learnerConfirms = true,
                rules = listOf(
                    "Save to the phone only. Do not share to other apps or to the Lightroom community.",
                    "Leave Content Credentials and Watermark off; they are Premium.",
                    NO_PAID,
                ),
                fallback = "If Save copy to device is not in the menu, use Share > Export as; if neither appears, say so plainly.",
                practice = Practice(
                    intro = "Let's save your edited photo to the phone.",
                    checkpoints = listOf(
                        "Save copy adds a new photo; your original stays as it was.",
                        "The copy appears with your other photos.",
                    ),
                    stopAt = "saving the copy to the phone; nothing is shared",
                ),
                sources = listOf(
                    Source("https://helpx.adobe.com/lightroom/mobile/get-started/workspace-overview.html", "Options > Save copy to device."),
                    Source("https://helpx.adobe.com/lightroom/mobile/share-save-and-export/export-photos-in-multiple-formats.html", "Share > Export as; Content Credentials and Watermark are Premium."),
                    Source("https://helpx.adobe.com/lightroom/mobile/review-rate-and-manage-versions/create-versions-of-your-photo-edits.html", "The Original version is kept."),
                ),
            ),
        ),
    )

    val canva = Course(
        id = "canva",
        version = 1,
        title = "Canva: Make an invitation",
        summary = "Start from a free template, change the title and date, and save a copy to your phone.",
        art = CourseArt.Invitation,
        category = CourseCategory.PhotosAndCreativity,
        app = KnownApp(
            name = "Canva",
            packageName = "com.canva.editor",
            publisher = "Canva",
            label = "Canva",
            lookalikes = listOf("Other design apps look similar; the official one is \"Canva\", published by Canva."),
        ),
        lessons = listOf(
            Lesson(
                id = "canva.open",
                title = "Open Canva",
                learned = "You opened Canva!",
                purpose = "By the end of this lesson, you'll know how to open Canva and find its templates.",
                result = "This is Canva's home. Templates are ready-made designs you can change to make your own.",
                explanation = "Canva makes cards, invitations and posters from ready-made designs. It needs a free Canva account.",
                objective = "Open the official Canva app (published by Canva) and reach its home screen, where templates can be searched. Install it from the Play Store only if it is not on the phone. Signing up or in is the learner's own: guide them to that screen only if they want to, and never type their details.",
                doneWhen = "Canva is open on its home screen, with the search or Templates visible.",
                needsAccount = true,
                rules = listOf(
                    "Install only the app published by Canva.",
                    NO_PAID,
                ),
                fallback = "If the learner has no Canva account and does not want one, say so plainly and end the course; Canva does not work without one.",
                sources = listOf(
                    Source("https://play.google.com/store/apps/details?id=com.canva.editor", "Official listing: Canva, published by Canva."),
                    Source("https://www.canva.com/help/sign-up-log-in/", "Sign up with email, Google, Facebook or Apple to use Canva for free."),
                    Source("https://www.canva.com/policies/terms-of-use/", "Each person has their own account."),
                ),
            ),
            Lesson(
                id = "canva.template",
                title = "Choose a free invitation",
                learned = "You chose an invitation to make your own!",
                purpose = "By the end of this lesson, you'll know how to find a free template and start from it.",
                result = "The invitation is open for editing. It's your own copy; the template itself doesn't change.",
                explanation = "Templates marked with a small crown are paid. Plenty of invitations have no crown and are free.",
                objective = "In Canva, search templates for \"invitation\", choose one without a crown on it, and open it with Customize this template (or by tapping it).",
                doneWhen = "An invitation template without a crown is open in Canva's editor.",
                rules = listOf(
                    "Choose a template with no crown. Do not use Canva AI or Generate.",
                    NO_PAID,
                ),
                fallback = "If every template shown has a crown, scroll further or try another search word such as \"party invitation\".",
                practice = Practice(
                    intro = "Let's find a free invitation for a made-up garden party.",
                    example = "a garden party",
                    exampleKey = "party",
                    checkpoints = listOf(
                        "A small crown on a template means it's a paid one; skip those.",
                        "Opening a template makes your own copy to change.",
                    ),
                    ownChoice = "a different made-up event the learner likes",
                ),
                sources = listOf(
                    Source("https://www.canva.com/help/use-templates/", "Templates, search, Customize this template."),
                    Source("https://www.canva.com/licensing-explained/", "Pro content shows a crown icon."),
                    Source("https://www.canva.com/invitations/templates/", "Free invitation templates."),
                ),
            ),
            Lesson(
                id = "canva.edit",
                title = "Change the title and date",
                learned = "You made the invitation your own!",
                purpose = "By the end of this lesson, you'll know how to change the words on a design.",
                result = "The invitation now says what you typed. Canva saves your changes as you go.",
                explanation = "Double-tapping words on a design lets you type your own. Canva saves as you go.",
                objective = "In the open invitation, double-tap the main title and type the practice title, then do the same for the date line, and tap outside the text to finish.",
                doneWhen = "The invitation's title and date show the new words.",
                rules = listOf(
                    "Use made-up details only: no real addresses, phone numbers or names of people who haven't agreed.",
                ),
                fallback = "If the template has no separate date line, add the date to the title's text instead.",
                practice = Practice(
                    intro = "Let's change the title and the date.",
                    example = "Garden Tea Party",
                    exampleKey = "party-title",
                    typeText = "Garden Tea Party",
                    checkpoints = listOf(
                        "Double-tap the words to change them; the keyboard opens.",
                        "For the date, a made-up one is fine, like \"Saturday at 3 pm\".",
                        "Tap outside the words when you're done.",
                    ),
                    ownChoice = "the learner's own made-up title and date",
                ),
                sources = listOf(
                    Source("https://www.canva.com/help/add-and-edit-text/", "Double-tap text to edit it."),
                    Source("https://www.canva.com/help/save/", "Canva saves automatically."),
                ),
            ),
            Lesson(
                id = "canva.download",
                title = "Save a copy to your phone",
                learned = "You saved your invitation!",
                purpose = "By the end of this lesson, you'll know how to save a design to your phone.",
                result = "A copy of your invitation is saved on your phone. Nothing was sent or shared.",
                explanation = "Download saves a picture of your design on your phone. Sharing or sending is a separate choice.",
                objective = "In the open invitation, tap the Share icon, choose Download, keep the suggested file type, and tap Download. Stop there.",
                doneWhen = "Canva shows that the download finished, or the learner sees the saved invitation and says so.",
                learnerConfirms = true,
                rules = listOf(
                    "Download only. Do not copy a link, share to apps, send, print or publish.",
                    "If Canva asks to pay before downloading, do not pay: go back and choose a template without a crown.",
                    NO_PAID,
                ),
                fallback = "If Download is not under Share, look for a download arrow at the top of the editor; if it asks for payment, stop and explain.",
                practice = Practice(
                    intro = "Let's save the invitation to your phone.",
                    checkpoints = listOf(
                        "Download saves a copy on your phone; it doesn't send it to anyone.",
                        "The other options in Share send or publish it, so we'll leave those.",
                    ),
                    stopAt = "the download; nothing is sent, shared or published",
                ),
                sources = listOf(
                    Source("https://www.canva.com/help/download-or-purchase/", "Share > Download; Pro elements ask for payment."),
                    Source("https://www.canva.com/help/collaborate-with-anyone/", "Share links, to avoid."),
                    Source("https://www.canva.com/help/share-on-socials-and-messaging/", "Posting to social apps, to avoid."),
                ),
            ),
        ),
    )

    val homeScreen = Course(
        id = "homescreen",
        version = 1,
        title = "Personalize your home screen",
        summary = "Change your wallpaper, try new colours and icon styles, and change them back.",
        art = CourseArt.HomeScreen,
        category = CourseCategory.MakeItYours,
        app = KnownApp(
            name = "Home screen",
            // The home screen belongs to whichever launcher the phone's maker
            // ships, so there is no one package: the screen says which it is.
            packageName = "",
            publisher = "Your phone's maker",
            lookalikes = listOf("Home screens are arranged differently on different phones; the options may have other names or be missing."),
        ),
        lessons = listOf(
            Lesson(
                id = "homescreen.wallpaper",
                title = "Change your wallpaper",
                learned = "You changed your wallpaper!",
                purpose = "By the end of this lesson, you'll know how to change the picture behind your apps.",
                result = "Your home screen shows the wallpaper you chose. You can change it again any time.",
                explanation = "The wallpaper is the picture behind your apps. Your phone comes with some, or you can use a photo.",
                objective = "From the Home screen, touch and hold an empty space and choose Wallpaper & style (Wallpaper and style on Samsung), or open it in Settings; then choose a wallpaper that comes with the phone, or one of the learner's photos, and apply it to the home screen.",
                doneWhen = "The learner has applied a new wallpaper and says they like it.",
                learnerConfirms = true,
                rules = listOf(
                    "Use the phone's own wallpapers or the learner's photos. Do not use AI-generated wallpapers.",
                    "Never download a theme, a launcher or an icon pack.",
                ),
                fallback = "If touching and holding the Home screen shows no wallpaper option, open Settings and look for Wallpaper; if this phone names it differently, say so and follow what the screen shows.",
                practice = Practice(
                    intro = "Let's choose a new wallpaper.",
                    checkpoints = listOf(
                        "Touching and holding an empty part of the Home screen opens the options.",
                        "A preview shows how it will look before you apply it.",
                        "Your old wallpaper is still in the list if you want it back.",
                    ),
                    ownChoice = "one of the learner's own photos",
                ),
                sources = listOf(
                    Source("https://support.google.com/pixelphone/answer/7289143?hl=en", "Wallpaper & style (Pixel)."),
                    Source("https://www.samsung.com/us/support/answer/ANS10001632/", "Wallpaper and style (Samsung)."),
                ),
            ),
            Lesson(
                id = "homescreen.colors",
                title = "Try a colour theme",
                learned = "You tried a new colour theme!",
                purpose = "By the end of this lesson, you'll know how to change the colours your phone uses.",
                result = "Your phone's buttons and menus now use the colours you chose. The same place changes them back.",
                explanation = "Many phones can match their colours to your wallpaper, or use a colour you pick.",
                objective = "In Wallpaper & style, open Colors (Color palette on Samsung) and try a wallpaper colour or a basic colour, then apply the one the learner likes.",
                doneWhen = "The learner has applied a colour choice and says so.",
                learnerConfirms = true,
                rules = listOf(
                    "Use the built-in colour choices only. Do not download themes or theme packs.",
                ),
                fallback = "If this phone has no colour option (older Android versions and some phones don't), say it isn't available on this phone and move on to the next lesson.",
                practice = Practice(
                    intro = "Let's try a new colour theme.",
                    checkpoints = listOf(
                        "Wallpaper colours come from your wallpaper; basic colours are plain choices.",
                        "Nothing changes until you apply it.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/pixelphone/answer/7289143?hl=en", "Wallpaper & style > Colors (Pixel)."),
                    Source("https://support.google.com/android/answer/9730472?hl=en", "Wallpaper colors and Basic colors (Android 13 and up)."),
                    Source("https://www.samsung.com/us/support/answer/ANS10001905/", "Color palette (Samsung); themes may be paid."),
                ),
            ),
            Lesson(
                id = "homescreen.icons",
                title = "Try an icon style",
                learned = "You tried a new icon style!",
                purpose = "By the end of this lesson, you'll know how to change how your app icons look.",
                result = "Your app icons now match the style you chose. The same switch turns it off again.",
                explanation = "Some phones can colour app icons to match your theme. It's a switch, so it's easy to undo.",
                objective = "Turn on the phone's own icon style option: on Pixel, Themed icons (in Wallpaper & style, or Settings > Display & touch); on Samsung, Apply palette to app icons in Color palette. Let the learner look, then keep it or turn it off.",
                doneWhen = "The learner has tried the icon style and chosen whether to keep it, and says so.",
                learnerConfirms = true,
                rules = listOf(
                    "Use the phone's own setting only. Never install a launcher or an icon pack.",
                ),
                fallback = "If this phone has no icon style option, say plainly that it isn't available here, without suggesting another app, and move on.",
                practice = Practice(
                    intro = "Let's try a different look for your app icons.",
                    checkpoints = listOf(
                        "Only some apps change; others keep their usual look.",
                        "It's a switch: turning it off puts the icons back.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/pixelphone/answer/7289143?hl=en", "Icons in Wallpaper & style (Pixel)."),
                    Source("https://support.google.com/pixelphone/answer/6111557?hl=en", "Themed icons in Display & touch (Pixel)."),
                    Source("https://developer.android.com/about/versions/13/features", "Themed app icons, Android 13 and up, launcher-dependent."),
                    Source("https://www.samsung.com/us/support/answer/ANS10001905/", "Apply palette to app icons (Samsung)."),
                ),
            ),
            Lesson(
                id = "homescreen.undo",
                title = "Change it back",
                learned = "You know how to change it back!",
                purpose = "By the end of this lesson, you'll know how to put your home screen back the way it was.",
                result = "Your home screen looks the way you want. Every change here can be made again or undone.",
                explanation = "Nothing here is permanent. You can pick your old wallpaper again and switch styles off.",
                objective = "Help the learner undo any change they don't want to keep: choose the earlier or default wallpaper again, set colours back, and turn the icon style option off. Skip any they want to keep.",
                doneWhen = "The home screen looks the way the learner wants, and they say so.",
                learnerConfirms = true,
                rules = listOf("The learner decides what to keep."),
                fallback = "If a change was made with a downloaded theme, explain that it isn't undone here: applying the default theme puts it back.",
                practice = Practice(
                    intro = "Let's make sure you can always change it back.",
                    checkpoints = listOf(
                        "Your earlier wallpaper is in the same list as before.",
                        "Colours and icon styles switch back in the same places you changed them.",
                    ),
                ),
                sources = listOf(
                    Source("https://support.google.com/pixelphone/answer/7289143?hl=en", "Changing wallpaper and styles again (Pixel)."),
                    Source("https://www.samsung.com/us/support/answer/ANS10001905/", "Turning the Color palette off; applying the default theme (Samsung)."),
                ),
            ),
        ),
    )

    val all: List<Course> = listOf(lightroom, canva, homeScreen)
}
