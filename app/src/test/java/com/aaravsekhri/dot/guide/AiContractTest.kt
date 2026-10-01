package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.AiClient
import com.aaravsekhri.dot.guide.ai.AiSessionController
import com.aaravsekhri.dot.guide.ai.AiDecision
import com.aaravsekhri.dot.guide.ai.AiSessionStore
import com.aaravsekhri.dot.guide.ai.Assessment
import com.aaravsekhri.dot.guide.ai.Destination
import com.aaravsekhri.dot.guide.ai.LocalWording
import com.aaravsekhri.dot.guide.ai.NavigationStyle
import com.aaravsekhri.dot.guide.ai.PlanningFailure
import com.aaravsekhri.dot.guide.ai.PlanningOutcome
import com.aaravsekhri.dot.guide.ai.QuestionAnswer
import com.aaravsekhri.dot.guide.ai.Reveal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of the contract: what an answer is allowed to mean, and
 * the words the phone writes itself.
 *
 * The service validates too. These are the second gate, and they matter as
 * much: a phone that trusted whatever arrived would draw whatever arrived.
 */
class AiContractTest {

    private fun parse(json: String): AiDecision? = AiClient.parseDecision(JSONObject(json))

    @Test
    fun `every kind the service can send is understood`() {
        val o = "\"observation_id\":\"o\",\"assessment\":\"succeeded\",\"progress_note\":\"Settings open\""
        val decisions = listOf(
            """{"kind":"highlight",$o,"candidate_id":"n1","instruction":"Tap it.","expected_outcome":"x"}""",
            """{"kind":"type_text",$o,"candidate_id":"f1","instruction":"Tap the box.","text":"chatgpt","expected_outcome":"x"}""",
            """{"kind":"submit",$o,"via":"keyboard","instruction":"Press search.","expected_outcome":"x"}""",
            """{"kind":"scroll",$o,"reveal":"right","looking_for":"Alarm","expected_outcome":"x"}""",
            """{"kind":"navigate",$o,"to":"back","reason":"x","expected_outcome":"x"}""",
            """{"kind":"ask",$o,"question":"Which?","options":["A","B"]}""",
            """{"kind":"wait",$o,"reason":"Installing."}""",
            """{"kind":"manual",$o,"instruction":"Enter your password.","reason":"private"}""",
            """{"kind":"complete",$o,"summary":"Done.","evidence":"Switch is on"}""",
            """{"kind":"blocked",$o,"explanation":"No.","suggestion":"Try Settings."}""",
        ).map { requireNotNull(parse(it)) { it } }

        assertTrue(decisions[1] is AiDecision.TypeText && (decisions[1] as AiDecision.TypeText).text == "chatgpt")
        assertTrue((decisions[2] as AiDecision.Submit).viaKeyboard)
        assertEquals(Reveal.Right, (decisions[3] as AiDecision.Scroll).reveal)
        assertEquals(Destination.Back, (decisions[4] as AiDecision.Navigate).destination)
        assertEquals(listOf("A", "B"), (decisions[5] as AiDecision.Ask).options)
        assertTrue(decisions.all { it.meta.assessment == Assessment.Succeeded })
        assertEquals("Settings open", decisions[0].meta.progressNote)
    }

    @Test
    fun `a keyboard submit without words is kept, and worded by the phone`() {
        val submit = parse("""{"kind":"submit","observation_id":"o","via":"keyboard"}""") as AiDecision.Submit
        assertTrue(submit.viaKeyboard)
        assertEquals("", submit.instruction)
        assertTrue(LocalWording.submitWithKeyboard().contains("keyboard"))
        assertEquals(
            "Press the Search key on the keyboard: the magnifying glass.",
            LocalWording.submitWithKeyboard(com.aaravsekhri.dot.guide.ai.KeyboardAction.Search),
        )
    }

    @Test
    fun `words to type are shown whole, never cut short`() {
        val place = com.aaravsekhri.dot.courses.CourseCatalog.MAPS_PLACE
        assertTrue(LocalWording.typeInto("Search here", place).contains("“$place”"))
        assertTrue(LocalWording.typing("Tap the box.", place).endsWith("“$place”"))
    }

    @Test
    fun `anything invented or incomplete is refused`() {
        listOf(
            """{"kind":"tap_at","observation_id":"o","x":10,"y":20}""",
            """{"kind":"highlight","observation_id":"o","instruction":"Tap it."}""",
            """{"kind":"highlight","candidate_id":"n1","instruction":"Tap it."}""",
            """{"kind":"submit","observation_id":"o","via":"control","instruction":"Tap Search."}""",
            """{"kind":"scroll","observation_id":"o","reveal":"down"}""",
            """{"kind":"navigate","observation_id":"o","to":"recents","reason":"x"}""",
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun `a wait is bounded whatever the answer says`() {
        val wait = parse("""{"kind":"wait","observation_id":"o","reason":"x","recheck_after_ms":1,"max_rechecks":900}""")
            as AiDecision.Wait
        assertEquals(500L, wait.recheckAfterMs)
        assertEquals(10, wait.maxRechecks)
    }

    @Test
    fun `service failures become things a person can act on`() {
        fun kind(code: Int, category: String) = when (val o = AiClient.failureFor(code, category)) {
            is PlanningOutcome.Unreachable -> o.kind
            is PlanningOutcome.Refused -> o.kind
            else -> null
        }
        assertEquals(PlanningFailure.NotAllowed, kind(401, ""))
        assertEquals(PlanningFailure.LocalLimit, kind(429, "local_rate_limit"))
        assertEquals("a bare 429 is Dot's own limit", PlanningFailure.LocalLimit, kind(429, ""))
        assertEquals(PlanningFailure.Throttled, kind(503, "provider_throttled"))
        assertEquals(PlanningFailure.ProviderLimited, kind(503, "provider_limited"))
        assertEquals("the older service's word", PlanningFailure.ProviderLimited, kind(503, "rate_limited"))
        assertEquals(PlanningFailure.QuotaExhausted, kind(503, "quota_exhausted"))
        assertEquals(PlanningFailure.Capacity, kind(503, "provider_capacity"))
        assertEquals(PlanningFailure.SlowAnswer, kind(504, "timeout"))
        assertEquals(PlanningFailure.ServiceProblem, kind(502, "auth"))
        assertEquals(PlanningFailure.NotConfigured, kind(503, "not_configured"))
        assertEquals(PlanningFailure.Unknown, kind(500, ""))
        assertTrue((AiClient.failureFor(502, "unusable_answer") as PlanningOutcome.Refused).retryable)
        assertFalse((AiClient.failureFor(503, "quota_exhausted") as PlanningOutcome.Refused).retryable)
        // A request the service refused is refused again if repeated: not a
        // connection problem, never retried by itself.
        assertEquals(PlanningFailure.RequestRejected, kind(422, ""))
        assertEquals(PlanningFailure.RequestRejected, kind(422, "request_rejected"))
        assertEquals(PlanningFailure.RequestRejected, kind(413, "too_large"))
        assertEquals(PlanningFailure.RequestRejected, kind(502, "bad_request"))
        assertFalse((AiClient.failureFor(422, "") as PlanningOutcome.Refused).retryable)
        assertEquals("nothing there speaks Dot's protocol", PlanningFailure.ServiceProblem, kind(404, ""))
    }

    @Test
    fun `a failed connection says whose problem it is`() {
        val refused = java.net.ConnectException("Connection refused")
        assertEquals("online, service down", PlanningFailure.ServiceDown, AiClient.transportFailure(refused, sent = false, networkUp = true))
        assertEquals("the phone is offline", PlanningFailure.Offline, AiClient.transportFailure(refused, sent = false, networkUp = false))
        assertEquals(
            "the whole request went; the answer is slow",
            PlanningFailure.SlowAnswer,
            AiClient.transportFailure(java.net.SocketTimeoutException(), sent = true, networkUp = true),
        )
        assertEquals(
            "could not even connect in time",
            PlanningFailure.ServiceDown,
            AiClient.transportFailure(java.net.SocketTimeoutException(), sent = false, networkUp = true),
        )
        assertEquals(PlanningFailure.ServiceProblem, AiClient.transportFailure(javax.net.ssl.SSLHandshakeException("x"), sent = false, networkUp = true))
        assertEquals(PlanningFailure.Offline, AiClient.transportFailure(java.net.UnknownHostException(), sent = false, networkUp = null))
    }

    @Test
    fun `a build can ask a local service with the shared token, or an https service with no secret`() {
        assertTrue(AiClient.serviceConfigured("http://10.0.2.2:8000", "dev-token"))
        assertTrue("release: https and install identities", AiClient.serviceConfigured("https://api.example.test", ""))
        assertFalse("never cleartext without the developer token", AiClient.serviceConfigured("http://api.example.test", ""))
        assertFalse(AiClient.serviceConfigured("", "dev-token"))
    }

    @Test
    fun `a mistake inside TapBack is not called an outage`() {
        val (words, _) = AiSessionController.failureWords(PlanningFailure.InternalError)
        assertFalse(words.contains("answering"))
        assertFalse(PlanningFailure.InternalError.transient)
    }

    @Test
    fun `every failure is worded for what it is`() {
        val service = AiSessionController.failureWords(PlanningFailure.ServiceDown).first
        assertFalse("a service that is down is not the person's Wi-Fi", service.contains("Wi-Fi"))
        assertTrue(AiSessionController.failureWords(PlanningFailure.Offline).second.contains("Wi-Fi"))
        PlanningFailure.entries.forEach { kind ->
            assertTrue(kind.name, AiSessionController.failureWords(kind).first.isNotBlank())
        }
    }

    @Test
    fun `a service's retry delay is read from its body or header, within reason`() {
        assertEquals(4_500L, AiClient.retryAfterMs(org.json.JSONObject("""{"retry_after_s": 4.5}"""), null))
        assertEquals(7_000L, AiClient.retryAfterMs(null, "7"))
        assertEquals(null, AiClient.retryAfterMs(org.json.JSONObject("""{"retry_after_s": null}"""), null))
        assertEquals(null, AiClient.retryAfterMs(null, "soon"))
        assertEquals(AiClient.MAX_HONOURED_DELAY_MS, AiClient.retryAfterMs(null, "86400"))
    }

    // ------------------------------------------------------------ wording

    @Test
    fun `revealing content one way always moves the finger the other`() {
        val opposite = mapOf(Reveal.Below to "up", Reveal.Above to "down", Reveal.Right to "left", Reveal.Left to "right")
        Reveal.entries.forEach { reveal ->
            val words = LocalWording.scroll(reveal, "")
            val finger = LocalWording.fingerDirection(reveal)
            assertEquals(opposite.getValue(reveal), finger)
            val side = when (reveal) {
                Reveal.Below -> "below"
                Reveal.Above -> "above"
                Reveal.Right -> "on the right"
                Reveal.Left -> "on the left"
            }
            assertTrue(words, words.contains("finger") && words.contains(finger) && words.contains(side))
        }
    }

    @Test
    fun `home is described for the way this phone actually navigates`() {
        assertTrue(LocalWording.home(NavigationStyle.Gestures).contains("slide one finger up"))
        assertTrue(LocalWording.home(NavigationStyle.ThreeButton).contains("round Home button"))
        val unknown = LocalWording.home(NavigationStyle.Unknown)
        assertTrue("both ways, rather than a guess: $unknown", unknown.contains("button") && unknown.contains("slide"))
    }

    @Test
    fun `the words to type are shown once`() {
        assertEquals("Tap the search box. Type: “chatgpt”", LocalWording.typing("Tap the search box.", "chatgpt"))
        assertEquals("Type chatgpt in the box.", LocalWording.typing("Type chatgpt in the box.", "chatgpt"))
        assertEquals("Type your name.", LocalWording.typing("Type your name.", ""))
    }

    // --------------------------------------------------------- checkpoints

    @Test
    fun `a checkpoint keeps each answer with its question, and nothing positional`() {
        val checkpoint = AiSessionStore.Checkpoint(
            sessionId = "s", goal = "post a photo",
            answers = listOf(QuestionAnswer("Existing or new?", "Existing")),
            progress = listOf("Facebook is open"), subgoal = "New post",
            failedApproaches = listOf("Tapped Stories"),
            lastInstruction = "Tap Photo.", lastExpectedOutcome = "Gallery opens.", lastKind = "tap",
            status = "guiding", updatedAtMs = 5,
        )
        val json = AiSessionStore.encode(checkpoint)
        assertEquals(checkpoint, AiSessionStore.decode(json.toString()))
        val keys = json.keys().asSequence().toSet()
        assertTrue("no coordinates, nodes or captures: $keys", keys.none { it.contains("bound") || it.contains("node") || it.contains("screenshot") || it.contains("jpeg") })
    }

    @Test
    fun `an older checkpoint still loads`() {
        val old = """{"session_id":"s","goal":"make the text bigger","clarifications":["Text"],"progress":"Settings open","status":"guiding"}"""
        val decoded = AiSessionStore.decode(old)!!
        assertEquals(listOf("Text"), decoded.clarifications)
        assertEquals(listOf("Settings open"), decoded.progress)
    }
}
