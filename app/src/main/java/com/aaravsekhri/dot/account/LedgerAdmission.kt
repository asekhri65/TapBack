package com.aaravsekhri.dot.account

import com.aaravsekhri.dot.guide.ai.AiSessionController.AdmitOutcome
import com.aaravsekhri.dot.guide.ai.AiSessionController.TaskAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A session's task, admitted, released and finished on Dot's service.
 *
 * Release and finish are sent and not waited for: a session ending never
 * waits on the network, and the service is the one that decides whether a
 * credit comes back.
 */
class LedgerAdmission(private val plus: PlusRepository) : TaskAdmission {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun admit(taskId: String, kind: String, courseId: String): AdmitOutcome =
        when (val r = plus.accounts.admit(taskId, kind, courseId)) {
            is AccountResult.Ok -> if (r.value.admitted) AdmitOutcome.Admitted else AdmitOutcome.Refused(r.value.reason)
            is AccountResult.Failed -> AdmitOutcome.Failed(r.kind)
        }

    override fun release(taskId: String) {
        scope.launch { plus.accounts.release(taskId) }
    }

    override fun finish(taskId: String, kind: String, summary: String) {
        scope.launch { plus.accounts.finish(taskId) }
        // The first guided task done: the one invitation to Plus may be shown, in TapBack, later.
        if (kind == "ask") plus.onTaskCompleted(summary)
    }
}
