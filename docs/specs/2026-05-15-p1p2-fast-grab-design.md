# P1/P2 Fast-Grab Design

**Date:** 2026-05-15
**Branch:** `feat/provider-health-banner` (or successor)
**Status:** Approved for implementation planning

---

## Problem

Today, when a series sits at P1 or P2 with aired episodes missing, prioritarr's existing backfill sweep does the right thing on paper — it sorts P1/P2 ahead of P3/P4 — but in practice the user-perceived latency from "aired" to "grabbed" can be long:

1. The backfill sweep runs once every 2h. A series can wait nearly two hours between attempts.
2. The sweep currently fires `SeriesSearch` (a full series-wide scan), which is coarse and wastes work on series with many monitored episodes.
3. On cold boot, `backfill-sweep` and `priorities-refresh` schedule on the same tick. Backfill can fire while the priority cache is empty (correctness is preserved by lazy compute, but the UI lags behind and there's no explicit "priorities first" guarantee).
4. Nothing capitalises on a successful indexer hit — if S04E06 just grabbed, S04E07 sits and waits for the next 2h tick instead of being searched while the indexer's release-list is still hot.

## Goal

P1 and P2 episodes go from "aired + missing" to "grabbed" as fast as the indexers and Sonarr permit, without flooding either, and the UI always reflects fresh priorities from the moment the container is healthy.

## Non-goals

- Changing P3/P4 behaviour. They keep the current series-wide search.
- Changing the P5 ratchet. It already has its own cooldown + escalation model.
- Changing Sonarr's own scheduled tasks (RSS sync, missing-episode search) — those run independently and aren't ours to sequence.
- Intercepting **user-initiated** searches from the Sonarr UI. Those go straight to Sonarr's command queue and bypass prioritarr by design.

---

## Architecture overview

Six discrete changes:

1. **Boot ordering** — `priorities-refresh` runs before `backfill-sweep` on cold start, enforced by an in-memory primed flag and a small scheduler fix.
2. **Backfill Pass A1** — for P1/P2 series, fire `EpisodeSearch` with up to 5 oldest missing episode IDs instead of `SeriesSearch`.
3. **Queue-skip** — episodes already in Sonarr's download queue are filtered out before search.
4. **Per-episode cooldown** — `p1p2_search_attempts` table prevents identical searches within 30 minutes.
5. **Dedicated P1/P2 budget** — Pass A1 gets `backfillP1P2MaxPerSweep` (default 20), independent of the P3/P4 budget.
6. **On-grab follow-up** — when a P1/P2 series receives a grab, immediately search the next 2 oldest missing episodes of the same series.

---

## Detailed design

### 1. Boot ordering: `prioritiesPrimed` flag + scheduler retry fix

#### State

```kotlin
// app/AppState.kt
class AppState(
    // …existing fields…
    val prioritiesPrimed: AtomicBoolean = AtomicBoolean(false),
)
```

#### Wiring

`Main.kt` sets the flag from the scheduler wrapper around `refreshAllPriorities`, in a `finally` block — so a partially failed refresh still unblocks backfill (rationale: a single Sonarr 500 shouldn't strand backfill forever).

```kotlin
add(JobDefinition(
    id = JobId.REFRESH_PRIORITIES,
    cadenceMinutes = { liveSettings(db, settings).intervals.refreshPrioritiesMinutes.toLong() },
    weight = JobWeight.HEAVY,
    run = {
        try {
            refreshAllPriorities(sonarr, priorityService)
            JobOutcome()
        } finally {
            state.prioritiesPrimed.set(true)
        }
    },
))

add(JobDefinition(
    id = JobId.BACKFILL_SWEEP,
    cadenceMinutes = { liveSettings(db, settings).intervals.backfillSweepHours.toLong() * 60L },
    prerequisites = { state.prioritiesPrimed.get() },
    weight = JobWeight.LIGHT,
    run = { /* runBackfillSweep(...) */ },
))
```

#### Scheduler prereq-retry fix

`Scheduler.kt` currently pushes a prereq-failed job forward by its full cadence. For `backfill-sweep` (2 h cadence) that means a single boot-time prereq miss delays the first sweep by 2 hours — unacceptable.

```kotlin
private const val PREREQ_RETRY_MINUTES = 1L

// in tick():
if (!ready) {
    val cadence = job.cadenceMinutes().coerceAtLeast(1L)
    val retry = minOf(cadence, PREREQ_RETRY_MINUTES)
    nextDue[job.id] = now.plus(Duration.ofMinutes(retry))
    continue
}
```

This is a fix that incidentally improves every job with a `prerequisites` lambda. Today `traktUnmonitor` (cadence 1 h) and `watchedArchiver` (cadence configurable) can wait up to a full cadence after their toggle flips on — under this change they react within ~1 minute.

#### Effect on cold-boot timing

```
T+0s     container starts
T+15s    scheduler first tick:
           backfill-sweep:   prereq false → reschedule +1min
           priorities-refresh: fires (HEAVY)
T+~30s   priorities-refresh sets prioritiesPrimed=true (finally block)
T+75s    next tick — backfill-sweep prereq now true, runs
```

### 2. Backfill sweep Pass A1: episode-search for P1/P2

`runBackfillSweep` in `sweep/Sweep.kt` is restructured to three passes:

| Pass | Tiers | Command | Budget |
|---|---|---|---|
| A1 *(new)* | P1, P2 | `triggerEpisodeSearch(up to 5 episodeIds)` | `backfillP1P2MaxPerSweep` (20) |
| A2 *(today's behaviour)* | P3, P4 | `triggerSeriesSearch(seriesId)` | `backfillMaxSearchesPerSweep` (10) |
| B *(unchanged)* | P5 | ratchet `Series/EpisodeSearch` | shares A2 leftover via `budgetRemaining` |

Pass A1 logic lives in a new file `sweep/P1P2EpisodeSearch.kt`:

```kotlin
internal data class P1P2Candidate(
    val seriesId: Long,
    val priority: Int,        // 1 or 2
    val oldestAirDate: String,
    val episodes: List<MissingEpisode>,  // already filtered & sorted
)

internal data class MissingEpisode(
    val episodeId: Long,
    val airDateUtc: String,
    val seasonNumber: Int,
)

internal fun buildP1P2Candidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int = 5,
): List<P1P2Candidate> {
    // group records by seriesId, keep priority ∈ {1,2},
    // sort each series' episodes by airDateUtc ASC,
    // filter out queued + in-cooldown,
    // take(perSeriesCap),
    // drop series whose candidate list is empty,
    // outer sort by (priority asc, oldestAirDate asc)
}

internal suspend fun runP1P2EpisodePass(
    candidates: List<P1P2Candidate>,
    sonarr: SonarrClient,
    db: Database,
    budget: Int,
    delaySeconds: Int,
    dryRun: Boolean,
): Int {
    var fired = 0
    for (c in candidates) {
        if (fired >= budget) break
        val ids = c.episodes.map { it.episodeId }
        if (dryRun) {
            logger.info("[backfill-p1p2] DRY RUN: would EpisodeSearch series {} eps {}", c.seriesId, ids)
        } else {
            try {
                sonarr.triggerEpisodeSearch(ids)
                ids.forEach { db.upsertP1P2Attempt(it, nowEpochSeconds()) }
                logger.info("[backfill-p1p2] triggered: series {} eps {} (P{})", c.seriesId, ids, c.priority)
            } catch (e: Exception) {
                logger.warn("[backfill-p1p2] EpisodeSearch failed for series {}: {}", c.seriesId, e.message)
                break  // don't cascade; cooldown not recorded → retried next sweep
            }
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    return fired
}
```

`Sweep.kt::runBackfillSweep` orchestrates:

```kotlin
val records = sonarr.getWantedMissing()
val queueIds = try { sonarr.getQueue().toEpisodeIdSet() } catch (_: Exception) { emptySet() }
val cooldownIds = db.listP1P2AttemptedSince(now - cooldownSeconds).toSet()
val priorityBySeriesId = buildSweepOrder(records, priorityService)
    .associate { it.seriesId to it.priority }

// Pass A1
val p1p2 = buildP1P2Candidates(records, priorityBySeriesId, queueIds, cooldownIds, perSeriesCap = 5)
fired += runP1P2EpisodePass(p1p2, sonarr, db, budget = p1p2Budget, delaySeconds, dryRun)

// Pass A2 — current code, but filter out priority ∈ {1,2,5}
val p3p4Order = order.filter { it.priority in 3..4 }
fired += runP3P4SeriesPass(p3p4Order, sonarr, budget = p3p4Budget, …)

// Pass B — unchanged P5 ratchet
…
```

`SonarrClient.triggerEpisodeSearch(List<Long>)` already exists (`clients/Sonarr.kt:81`). No new client surface.

### 3. Queue-skip

Sonarr's `/api/v3/queue` returns one row per in-flight download. Each row has either `episode.id` or `episodeId` depending on payload shape (P5 ratchet's collector at `Sweep.kt:124-134` handles both — reused here).

Extracted into a helper:

```kotlin
internal fun JsonArray.toEpisodeIdSet(): Set<Long> = mapNotNull {
    val o = it.jsonObject
    o["episode"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        ?: o["episodeId"]?.jsonPrimitive?.longOrNull
}.toSet()
```

If `getQueue()` throws, treat as empty set — same defensive default as P5 ratchet. Worst case: we re-search a downloading episode; Sonarr deduplicates internally.

### 4. Per-episode cooldown

#### Schema

```sql
-- Per-episode attempt log for the P1/P2 fast-grab pass. Mirrors
-- p5_sweep_attempts in spirit but keyed per episode (not per season)
-- since P1/P2 uses episode-search.
CREATE TABLE IF NOT EXISTS p1p2_search_attempts (
    episode_id INTEGER PRIMARY KEY,
    last_attempted_at INTEGER NOT NULL CHECK (last_attempted_at > 0),
    attempts_count INTEGER NOT NULL DEFAULT 1 CHECK (attempts_count >= 1)
);
```

#### Queries

```sql
upsertP1P2Attempt:
INSERT INTO p1p2_search_attempts (episode_id, last_attempted_at, attempts_count)
VALUES (?, ?, 1)
ON CONFLICT(episode_id) DO UPDATE SET
    last_attempted_at = excluded.last_attempted_at,
    attempts_count = attempts_count + 1;

listP1P2AttemptedSince:
SELECT episode_id FROM p1p2_search_attempts WHERE last_attempted_at >= ?;

clearP1P2Attempt:
DELETE FROM p1p2_search_attempts WHERE episode_id = ?;
```

#### Lifecycle

- **Set:** `runP1P2EpisodePass` upserts after successful `triggerEpisodeSearch`. Follow-up coroutine upserts after successful grab.
- **Clear:** `Module.kt`'s `Download` webhook branch (when Sonarr imports an episode) calls `clearP1P2Attempt(episodeId)` so a re-monitored re-aired episode (rare but real for anime) doesn't sit in stale cooldown.
- **Read:** sweep + follow-up read `listP1P2AttemptedSince(now - cooldownSeconds)`.
- **Janitor:** none. The table self-bounds at `~|P1∪P2 monitored episodes|` rows (small, low hundreds typical). No GC job needed.

### 5. Dedicated P1/P2 budget

Two independent budgets. The existing `backfillMaxSearchesPerSweep` (default 10) is reinterpreted as P3/P4-only.

```kotlin
// config/Settings.kt
data class Intervals(
    // …existing fields…
    val backfillP1P2MaxPerSweep: Int = 20,
    val backfillP1P2CooldownMinutes: Int = 30,
    val backfillP1P2FollowupEpisodes: Int = 2,
)
```

Rationale for 20: with episode-search + queue-skip + 30-min cooldown, normal-case P1/P2 work per sweep is small (most P1 series have all aired eps already imported). 20 is a runaway-guard ceiling, not an expected-case throttle. Worst case 20 × 30s `delaySeconds` = 10 min of sweep time — tolerable.

`backfillP1P2MaxPerSweep = 0` is a valid "disable Pass A1" setting; falls through to old behaviour where P1/P2 series get `SeriesSearch` via Pass A2's iteration. *(Edge case: Pass A2 today iterates `priority < 5`. We change it to `priority in 3..4` so a disabled Pass A1 means P1/P2 are silently skipped — which is the spelled-out semantics of "disabled.")*

### 6. On-grab follow-up search

In `app/Module.kt`'s `"Grab"` branch, after `handleOnGrab(...)` returns `processed=true`:

```kotlin
if (processed && priorityResult.priority in 1..2) {
    call.application.launch {
        try {
            val s = liveSettings(state.db, state.settings)
            val n = s.intervals.backfillP1P2FollowupEpisodes
            if (n <= 0) return@launch
            val cooldownSeconds = s.intervals.backfillP1P2CooldownMinutes * 60L

            val missing = state.sonarr.getWantedMissing()
            val queueIds = runCatching { state.sonarr.getQueue().toEpisodeIdSet() }
                .getOrDefault(emptySet())
            val cooldownIds = state.db
                .listP1P2AttemptedSince(nowEpochSeconds() - cooldownSeconds)
                .toSet()
            val grabbedIds = event.episodeIds.toSet()

            val candidates = missing.asSequence()
                .filter { it.jsonObject["seriesId"]?.jsonPrimitive?.longOrNull == event.seriesId }
                .mapNotNull { row ->
                    val o = row.jsonObject
                    val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    if (id in grabbedIds || id in queueIds || id in cooldownIds) return@mapNotNull null
                    id to (o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999")
                }
                .sortedBy { it.second }
                .take(n)
                .map { it.first }
                .toList()

            if (candidates.isNotEmpty()) {
                state.sonarr.triggerEpisodeSearch(candidates)
                candidates.forEach { state.db.upsertP1P2Attempt(it, nowEpochSeconds()) }
                state.db.appendAudit(
                    action = "ongrab_followup",
                    seriesId = event.seriesId,
                    client = null, clientId = null,
                    details = buildJsonObject {
                        put("episode_ids", buildJsonArray { candidates.forEach { add(it) } })
                        put("priority", priorityResult.priority)
                    },
                )
            }
        } catch (e: Exception) {
            logger.warn("[ongrab-followup] series {} failed: {}", event.seriesId, e.message)
        }
    }
}
```

Three protections against feedback loops:
1. **`grabbedIds` filter** — we never re-search the episode just grabbed.
2. **Cooldown** — second grab on the same series within 30 minutes finds the next 2 already in cooldown and silently no-ops.
3. **Cap of 2** — even with cold cooldown, a single grab can only fire 1 additional `EpisodeSearch` with at most 2 IDs.

The webhook handler returns `OnGrabProcessed` *before* the coroutine completes — Sonarr's webhook delivery isn't blocked on the follow-up.

---

## Settings & config plumbing

| Field | Default | Where |
|---|---|---|
| `backfillP1P2MaxPerSweep` | 20 | `Settings.intervals`, override, YAML, V2 patch |
| `backfillP1P2CooldownMinutes` | 30 | same |
| `backfillP1P2FollowupEpisodes` | 2 | same |

YAML keys (snake-case):

```yaml
intervals:
  backfill_p1_p2_max_per_sweep: 20
  backfill_p1_p2_cooldown_minutes: 30
  backfill_p1_p2_followup_episodes: 2
```

`scheduler.PREREQ_RETRY_MINUTES = 1L` stays a Kotlin constant — no operator value in surfacing it.

UI: Settings → Backfill panel gets three number-input rows, validated as positive integers (`MaxPerSweep` ≥ 0, the other two > 0).

---

## File-level changes

| File | Change |
|---|---|
| `backend/.../app/AppState.kt` | Add `prioritiesPrimed: AtomicBoolean` |
| `backend/.../scheduler/Scheduler.kt` | `PREREQ_RETRY_MINUTES`; use `min(cadence, retry)` on prereq fail |
| `backend/.../Main.kt` | Set primed flag in `priorities-refresh` `finally`; `prerequisites` on backfill; thread new settings into `runBackfillSweep` |
| `backend/.../sweep/Sweep.kt` | Split Pass A into A1 (P1/P2) + A2 (P3/P4); fetch queue + cooldown once; thread new settings |
| `backend/.../sweep/P1P2EpisodeSearch.kt` *(new)* | `buildP1P2Candidates`, `runP1P2EpisodePass`, `JsonArray.toEpisodeIdSet` |
| `backend/.../config/Settings.kt` | Three new `Intervals` fields + override + YAML parser |
| `backend/.../api/v2/V2Routes.kt` | Three fields in `IntervalsPatch` + `IntervalsView` |
| `backend/.../app/Module.kt` | `Grab` branch follow-up coroutine; `Download` branch clears cooldown |
| `backend/src/main/sqldelight/.../Schema.sq` | `p1p2_search_attempts` table + 3 queries |
| `frontend/src/pages/SettingsPage.tsx` | Three new inputs in backfill panel |
| `frontend/src/lib/api.ts` | Three new fields in Intervals DTOs |
| `README.md` | Three YAML keys in config reference |

---

## Testing

| Test file | Coverage |
|---|---|
| `SchedulerTest.kt` *(extend)* | Prereq-fail retry uses 1-minute cap, not full cadence; jobs with `prerequisites=true` keep current behaviour |
| `P1P2EpisodeSearchTest.kt` *(new)* | `buildP1P2Candidates`: groups by series, sorts by airDate, takes 5; queue-skip filters; cooldown filters; series with all candidates filtered → dropped from output; outer sort is (priority asc, oldestAirDate asc) |
| `P1P2EpisodeSearchTest.kt` *(new)* | `runP1P2EpisodePass`: respects budget; records cooldown on success; does NOT record on failure; respects dryRun |
| `SweepTest.kt` *(extend)* | End-to-end runBackfillSweep: Pass A1 fires EpisodeSearch, Pass A2 fires SeriesSearch, Pass B unchanged; budgets are independent |
| `OnGrabFollowupTest.kt` *(new)* | Follow-up fires for P1/P2 only; respects `grabbedIds`+queue+cooldown; caps at `backfillP1P2FollowupEpisodes`; failure in follow-up does not affect webhook response |
| `Settings`/YAML parser test | New keys load with defaults when absent |

---

## Failure modes & mitigations

| Failure | Mitigation |
|---|---|
| `priorities-refresh` partial failure on boot | Flag set in `finally` — backfill unblocks anyway |
| `getQueue()` throws | Empty set, sweep continues |
| `triggerEpisodeSearch` throws in Pass A1 | Break out of A1, A2/B still run, cooldown NOT recorded |
| Follow-up coroutine throws | Caught + warn-logged, webhook response already sent |
| `backfillP1P2MaxPerSweep = 0` | Pass A1 skipped; **P1/P2 are not picked up by Pass A2** (intentional — explicit disable) |
| Legacy YAML with old keys only | New fields take defaults |
| Legacy DB missing the new table | `CREATE TABLE IF NOT EXISTS` at boot |

## Edge cases

- P1 series with 100 missing episodes — Pass A1 takes 5/sweep; rest rotate in via cooldown expiry. Deep backfill remains P5 ratchet's job.
- Mid-sweep priority flip — snapshotted at top of sweep; effect deferred to next sweep.
- Episode imported (`Download` webhook) — `clearP1P2Attempt(episodeId)`.
- First boot, empty DB — no cooldown filters → first sweep is full-force. Correct.
- Operator disables `backfillP1P2MaxPerSweep` AND `backfillP1P2FollowupEpisodes` — system reverts to current 2h `SeriesSearch` for P1/P2. Safety hatch.

---

## Out of scope / future work

- Cooldown janitor — table is small enough to ignore for now. Revisit if it exceeds ~10k rows in long-running deployments.
- Adaptive cooldown — escalate on repeated empty attempts (P5 ratchet already does this; could port).
- Per-tier configurable per-series cap — today P1 and P2 share `perSeriesCap=5`. Splitting buys little.
- Surfacing follow-up + cooldown state in the series-detail drawer — useful but separate UI work.
