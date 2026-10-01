package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.GuideLog

/**
 * What one session asked the planner for, and what it did not have to ask.
 *
 * Counts only, logged in debug builds: requests by reason, how many carried
 * a picture, local checks made on the phone instead of a request, and
 * requests avoided because nothing that matters had changed. Token counts
 * and prices are measured by the service, which sees them; this is the
 * phone's half.
 */
class PlanningCosts {
    var requests = 0
        private set
    var withImage = 0
        private set
    var localChecks = 0
        private set
    var avoided = 0
        private set
    private val byTrigger = linkedMapOf<String, Int>()
    private val avoidedBy = linkedMapOf<String, Int>()

    fun asked(trigger: String, image: Boolean) {
        requests++
        if (image) withImage++
        byTrigger[trigger] = (byTrigger[trigger] ?: 0) + 1
        GuideLog.d("cost") { "request #$requests trigger=$trigger image=$image" }
    }

    /** A check made on the phone where a request would otherwise have gone. */
    fun checkedLocally(what: String) {
        localChecks++
        GuideLog.d("cost") { "local check ($what) #$localChecks" }
    }

    fun avoided(why: String) {
        avoided++
        avoidedBy[why] = (avoidedBy[why] ?: 0) + 1
        GuideLog.d("cost") { "request avoided ($why) #$avoided" }
    }

    fun summary(): String =
        "requests=$requests image=$withImage localChecks=$localChecks avoided=$avoided " +
            "triggers=$byTrigger avoidedBy=$avoidedBy"
}
