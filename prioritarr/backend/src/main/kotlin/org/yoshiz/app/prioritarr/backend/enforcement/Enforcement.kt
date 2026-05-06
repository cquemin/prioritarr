package org.yoshiz.app.prioritarr.backend.enforcement

import org.yoshiz.app.prioritarr.backend.clients.SABClient

/** Map internal P1..P5 → SAB priority value. */
fun computeSabPriority(prioritarrLevel: Int): Int =
    SABClient.PRIORITY_MAP[prioritarrLevel] ?: 0
