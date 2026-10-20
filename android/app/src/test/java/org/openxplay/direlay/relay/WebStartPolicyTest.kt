// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19
package org.openxplay.direlay.relay

import java.net.BindException
import org.junit.Assert.*
import org.junit.Test

class WebStartPolicyTest {
    @Test fun retriesBackOffAndContinueAtFiveSeconds() {
        assertEquals(listOf(200L, 500L, 1000L, 2000L, 5000L, 5000L), (0..5).map(::webStartRetryDelay))
        assertEquals(5000L, webStartRetryDelay(Int.MAX_VALUE))
    }

    @Test fun bindFailureIsRecognizedAnywhereInCauseChain() {
        assertEquals(WebStartReason(WebStartFailure.PORT_BUSY, 8443),
            webStartReason(IllegalStateException(java.util.concurrent.ExecutionException(BindException())), 8443))
        assertEquals(WebStartReason(WebStartFailure.OTHER, 9443),
            webStartReason(IllegalStateException("unavailable"), 9443))
    }
}
