package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.guide.ai.CandidateControl
import com.aaravsekhri.dot.guide.ai.InstallState
import com.aaravsekhri.dot.guide.ai.TargetApp
import com.aaravsekhri.dot.guide.apps.AppDirectory
import com.aaravsekhri.dot.guide.apps.InstalledApps
import com.aaravsekhri.dot.guide.apps.LauncherResults
import com.aaravsekhri.dot.guide.apps.LauncherResults.Kind
import com.aaravsekhri.dot.guide.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding an app: which one is meant, whether it is here, and which result is it. */
class AppDiscoveryTest {

    @Test
    fun `a declared app that is not found is not found`() {
        assertEquals(InstallState.NotInstalled, InstalledApps.stateOf("com.whatsapp", InstalledApps.Lookup.NotFound))
    }

    @Test
    fun `an app Android hides from Dot is unknown, never missing`() {
        // Not declared in <queries>: Android 11+ filters it, so not finding it
        // says nothing about whether it is installed.
        assertEquals(InstallState.Unknown, InstalledApps.stateOf("com.example.banking", InstalledApps.Lookup.NotFound))
    }

    @Test
    fun `found apps are installed, or turned off`() {
        assertEquals(InstallState.Installed, InstalledApps.stateOf("com.whatsapp", InstalledApps.Lookup.Found(true, "WhatsApp")))
        assertEquals(InstallState.Disabled, InstalledApps.stateOf("com.android.chrome", InstalledApps.Lookup.Found(false, "Chrome")))
    }

    @Test
    fun `every course app is declared, and nothing else but the Play Store`() {
        assertEquals(
            CourseCatalog.courses.map { it.app.packageName }.filter(String::isNotBlank).toSet() + "com.android.vending",
            InstalledApps.declared,
        )
        assertTrue("no blank package is ever looked up", "" !in InstalledApps.declared)
    }

    @Test
    fun `plain app names in a request are recognised`() {
        assertEquals("com.whatsapp", AppDirectory.resolve("Help me send a photo on WhatsApp")?.packageName)
        assertEquals("com.openai.chatgpt", AppDirectory.resolve("open chat gpt")?.packageName)
        assertEquals("com.google.android.gm", AppDirectory.resolve("Check my Gmail")?.packageName)
    }

    @Test
    fun `ambiguous or other apps are left for the guide to ask about`() {
        assertNull("could be any texting app", AppDirectory.resolve("send a text in messages"))
        assertNull("could be any calendar", AppDirectory.resolve("add to my calendar"))
        assertNull("two apps named", AppDirectory.resolve("share from Facebook to WhatsApp"))
        assertNull("a different app", AppDirectory.resolve("open Facebook Lite"))
        assertNull("a different app", AppDirectory.resolve("set up WhatsApp Business"))
    }

    private val chatGpt = TargetApp("ChatGPT", "com.openai.chatgpt", "OpenAI", InstallState.Installed)

    private fun result(label: String, description: String = "") = CandidateControl(
        id = label, label = label, description = description, role = "TextView",
        bounds = Bounds(0, 0, 100, 100), clickable = true, editable = false, enabled = true,
        scrollable = false, parentId = null,
    )

    @Test
    fun `search results are told apart`() {
        assertEquals(Kind.InstalledApp, LauncherResults.kindOf(result("ChatGPT"), chatGpt))
        assertEquals(Kind.SimilarApp, LauncherResults.kindOf(result("ChatGPT Assistant AI"), chatGpt))
        assertEquals(Kind.SimilarApp, LauncherResults.kindOf(result("Chat GPT AI Chatbot"), chatGpt))
        assertEquals(Kind.WebSearch, LauncherResults.kindOf(result("chatgpt.com"), chatGpt))
        assertEquals(Kind.WebSearch, LauncherResults.kindOf(result("ChatGPT", "Search the web"), chatGpt))
        assertEquals(Kind.Store, LauncherResults.kindOf(result("ChatGPT", "Install"), chatGpt))
        assertEquals(Kind.Store, LauncherResults.kindOf(result("AI Chat · Sponsored"), chatGpt))
        assertEquals(Kind.Store, LauncherResults.kindOf(result("ChatGPT 4.7 ★"), chatGpt))
        assertEquals(Kind.Other, LauncherResults.kindOf(result("Clock"), chatGpt))
    }

    @Test
    fun `ordinary words are not mistaken for adverts`() {
        assertEquals(Kind.Other, LauncherResults.kindOf(result("Read later"), chatGpt))
        assertEquals(Kind.Other, LauncherResults.kindOf(result("Downloads"), chatGpt))
    }

    @Test
    fun `the name the phone shows counts as the app's name`() {
        val photos = TargetApp("Google Photos", "com.google.android.apps.photos", "Google LLC", InstallState.Installed, label = "Photos")
        assertEquals(Kind.InstalledApp, LauncherResults.kindOf(result("Photos"), photos))
        assertEquals(Kind.InstalledApp, LauncherResults.kindOf(result("Google Photos"), photos))
    }
}
