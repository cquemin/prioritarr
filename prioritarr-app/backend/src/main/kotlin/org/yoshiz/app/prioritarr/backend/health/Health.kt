package org.yoshiz.app.prioritarr.backend.health

import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.schemas.DependencyStatus
import org.yoshiz.app.prioritarr.backend.schemas.HealthOk
import org.yoshiz.app.prioritarr.backend.schemas.HealthUnhealthy
import org.yoshiz.app.prioritarr.backend.schemas.ReadyResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Return null when healthy, else the unhealthy body. Matches check_liveness in python. */
fun checkLiveness(db: Database, maxHeartbeatAgeSeconds: Long = 300): Pair<Boolean, Any> =
    try {
        val ts = db.getHeartbeat()
        if (ts == null) {
            false to HealthUnhealthy(reason = "no_heartbeat")
        } else {
            val last = try {
                OffsetDateTime.parse(ts).toInstant()
            } catch (e: Exception) {
                return false to HealthUnhealthy(reason = "invalid_heartbeat_ts")
            }
            val age = Duration.between(last, Instant.now()).seconds
            if (age > maxHeartbeatAgeSeconds) {
                false to HealthUnhealthy(reason = "heartbeat_stale: ${age}s > ${maxHeartbeatAgeSeconds}s")
            } else {
                true to HealthOk()
            }
        }
    } catch (e: Exception) {
        false to HealthUnhealthy(reason = "db_error: ${e.message}")
    }

fun checkReadiness(db: Database, dependencies: Map<String, DependencyStatus>): ReadyResponse {
    val allOk = dependencies.values.all { it == DependencyStatus.OK }
    return ReadyResponse(
        status = if (allOk) "ok" else "degraded",
        dependencies = dependencies,
        last_heartbeat = db.getHeartbeat(),
    )
}
