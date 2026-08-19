# English SRT Ladder — guarantee every anime episode an `.en.srt`

Date: 2026-08-16
Status: Design — approved, ready for implementation planning

## Problem

Every anime episode should end up with an external English SRT sidecar.
SRT matters specifically because Plex cannot soft-serve ASS: an episode
whose only English subtitle is an embedded ASS track forces a full
transcode to burn the subs in. An external `.en.srt` direct-plays.

Today nothing guarantees this. Coverage is accidental — it depends on
which of three unconnected mechanisms happened to fire.

Trigger for this work: *Super Dragon Ball Heroes* S06E03 plays with
Polish subtitles. Its only embedded track is `language=pol`, title
"Grupa Mirai". No English exists anywhere for that episode, and nothing
in the stack was going to fix that.

## What already exists (audited 2026-08-16)

The ladder is roughly 70% built. This is not a from-scratch feature.

| Capability | State | Evidence |
|---|---|---|
| Extract embedded text track → `.srt` | **Works** | `SubtitleExtractor`; produced `.en.srt` for One Piece S23E19 on import |
| Bazarr provider search | **Works well** | 3,650 subs delivered: opensubtitlescom 3,187 · animetosho 440 · betaseries 23 · tvsubtitles 5 |
| Whisper JP audio → English SRT | **Capable, never used** | Verified by hand: `task=translate` on SDBH E03 audio returned coherent English |
| Bazarr prefers SRT over ASS | **Already configured** | `ignore_ass_subs: true` |

### The five real gaps

**G1 — Whisper has never run once.** Zero across 4,000 Bazarr history
rows; zero mentions in Bazarr's log. It is enabled, reachable
(`whisper:9000` answers 200 from the Bazarr container), and
demonstrably functional. It is simply never reached.

**G2 — Whisper is switched off at the master switch, and is
unreachable per-episode regardless.** `use_whisper_fallback` is the
master flag and it is `false`; `use_whisper_fallback_series: true` is
ANDed against it, so it never takes effect. All three Bazarr code paths
are closed:

| Path | Expression | Result |
|---|---|---|
| `subtitles/wanted/series.py:58` (scheduled sweep) | `fallback_allowed=settings.general.use_whisper_fallback` | false |
| `subtitles/mass_download/series.py:68` (whole-series) | `use_whisper_fallback and use_whisper_fallback_series` | false |
| `episode_download_specific_subtitles` (per-episode manual) | never passes it → `generate_subtitles(..., fallback_allowed=False)` | **false, hardcoded** |

The third row is decisive: **even with the master switch on, Bazarr's
per-episode manual endpoint can never invoke Whisper.** No setting
exposes it. Delegating the AI rung to Bazarr is therefore not viable
for a per-episode ladder, which is why R3 calls `whisper:9000`
directly.

**Adaptive searching is NOT implicated.** `is_search_active` is called
only from `subtitles/wanted/`, i.e. the scheduled sweep. The manual
per-episode path used by R2 has no adaptive check, so
`adaptive_searching` stays **enabled** and unchanged. (An earlier
reading of this blamed adaptive search: a manual PATCH returned 204 and
produced nothing. That was async behaviour — `episode_download_specific_subtitles`
lines 189–190 enqueue a job and return immediately — not a throttle.)

**G3 — `jimaku` delivers nothing.** Enabled, 0 results across 4,000
rows. The intended anime-fansub fast path is inert; animetosho carries
that load. Out of scope here, recorded so it is not mistaken for new
breakage.

**G4 — prioritarr → Bazarr is dead code.** `BazarrClient` exists with a
working `triggerEpisodeSearch`, but it is never instantiated in
`Main.kt` and nothing calls it. Phase 2c of the 2026-04-30 subtitle
orchestration spec (priority-ordered subtitle search) was never built.
There is no priority ordering and no on-demand trigger.

**G5 — two detection blind spots.** The filesystem and Bazarr disagree
about what is missing:

- `matchesLang` returns false whenever the language *tag* is absent
  (`SubtitleExtractor.kt:260`) and never consults the track title, so
  untagged-but-English tracks are invisible. SDBH S06E01/E02 are
  exactly this: `title=English`, no language tag.
- Bazarr treats an **embedded** English track as satisfying the
  requirement, so it never fetches an SRT — while Plex still burns the
  ASS. This is precisely the streaming-overhead case, and it is
  invisible to both tools.

## Measured work set

Of 6,676 anime video files:

| Measure | Count |
|---|---|
| Have exact `.en.srt` | 4,925 |
| Missing exact `.en.srt` | **1,751** |
| …hold `.en.hi.srt` or bare `.srt` (blocks R2/R3, R1 still allowed) | 724 |
| …hold only `.en.forced.srt` (does not count as satisfied) | 0 |
| …have no English subtitle of any kind | 1,027 |
| **Expensive work set (R2/R3)** | **1,027** |

The forced-only case measures zero today. The rule is still encoded
because a single forced sidecar would otherwise silently mark an episode
satisfied with signs-and-songs text and no dialogue.

## Goals

- Every anime episode ends with an external `.en.srt`, or an explicit
  recorded reason why it cannot
- Cheapest source first; Whisper is genuinely last
- Backfill never competes with live playback or with high-priority
  episode searches
- Backoff is prioritarr's, per-episode, and survives restarts

## Non-goals

- Two-step transcribe-then-translate via Lingarr. Whisper's one-pass
  `task=translate` is the chosen path. (Lingarr has never run — it
  crash-loops on `DB_CONNECTION is not set` — and is not needed.)
- French, or any language beyond English
- Movies / Radarr
- Fixing G3 (jimaku)
- Replacing Bazarr's provider configuration

## Architecture

A single `sub-ladder` scheduler job owning an explicit, ordered,
per-episode state machine.

```
sub-ladder job (LIGHT, singleton, every N min)
  │
  ├─ gate: Plex idle?        ── PlexClient.sessionCount() + idle-tick debounce
  ├─ gate: search congested? ── SearchQueueControl.isCongested()  (queue busy)
  │     └─ either gate closed → JobOutcome(noop), no state change
  │
  └─ take next N episodes, priority order (P1→P5), skipping those in backoff
        │
        └─ climb until satisfied:
             R0  exact .en.srt present?          → DONE
             R1  embedded EN text track / ASS    → extract or convert to .en.srt
             R2  Bazarr provider search          → poll for sidecar
             R3  priority ≤ whisperMaxPriority?  → Whisper task=translate
             R4  nothing worked                  → record outcome + backoff
```

Structure chosen over (a) extending `SubtitleExtractor` in place — it is
already a focused file-walker and would end up doing four jobs — and
(b) one scheduler job per rung, which would make the ordering constraint
implicit in cadences rather than explicit and testable.

The job is a singleton by construction: the scheduler's `running` guard
(added 2026-08-14) prevents a slow sweep being relaunched every tick.

## Rungs

### R0 — Satisfied check

Exact `<base>.en.srt` exists → done.

**Variant rule.** 724 files hold a usable English SRT under a variant
name. Sending those to Whisper would spend ~120 hours of CPU to produce
output worse than the subtitle already present. Therefore:

- `.en.hi.srt` or bare `.srt` present → **blocks R2 and R3, but not R1**.
  A clean `.en.srt` is still produced if it can be had for free, but no
  network or CPU is ever spent on it.
- `.en.forced.srt` **alone does not count**. Forced tracks carry signs
  and songs only, not dialogue.

### R1 — Extract embedded (local, free)

Reuses `SubtitleExtractor.extractForFile()`. Three changes:

1. **`matchesLang` title fallback.** When the language tag is absent,
   match the track *title* against the existing `LANG_ALIASES` values
   (`english`, `eng`). Recovers SDBH S06E01/E02 immediately. The tag
   still wins when present.
2. **`hasSidecar` split.** It currently accepts `.en.srt`,
   `.en.hi.srt`, `.en.forced.srt` and bare `.srt` as equivalent
   (lines 244–254). Replace with a classifier returning
   `SATISFIED | VARIANT_ONLY | NONE` per the variant rule.
3. **ASS sidecar conversion.** If `<base>.ass` / `<base>.en.ass` exists
   with no `.en.srt`, convert it. Free, and directly serves the
   SRT-over-ASS goal.

Bitmap codecs (PGS/VobSub) cannot become SRT — fall through to R2.
`TEXT_CODECS` already encodes this distinction.

### R2 — Bazarr provider search

Instantiate the dead `BazarrClient` in `Main.kt` and call:

```
PATCH /api/episodes/subtitles?seriesid=&episodeid=&language=en&forced=false&hi=false → 204
```

The 204 means *queued*, not *found* — the handler enqueues a job and
returns immediately. The rung triggers, then polls the episode's
subtitle list (or watches for the sidecar) with a bounded wait of ~3
minutes, and on timeout records "no result this attempt" rather than
blocking the sweep.

**No Bazarr config change is required.** This path
(`episode_download_specific_subtitles`) carries no adaptive-search
check, so `adaptive_searching` stays enabled and continues to protect
Bazarr's own scheduled sweep. The two backoff mechanisms are
complementary, not competing: Bazarr paces its sweep, prioritarr paces
its own triggers via `subtitle_ladder_state`.

It also **cannot** invoke Whisper — `fallback_allowed` defaults to
`False` and this path never sets it. That is a feature here: R2 stays
cheap, network-only, and predictable, with no risk of silently
consuming CPU.

### R3 — Whisper (expensive, gated)

Runs only when series priority ≤ `whisperMaxPriority` (default P2), or
when invoked on demand.

```
ffmpeg -vn -ac 1 -ar 16000 -f wav
POST multipart audio_file →
  http://whisper:9000/asr?task=translate&language=ja&output=srt
```

`language` derives from the audio stream tag (`jpn` → `ja`); omitted
when untagged so Whisper detects it. Output is written via tmp +
`atomicMove` using a **unique** tmp name — the current deterministic
`base.lang.srt.tmp` already races when two extractions touch one file.

Whisper is CPU-bound, so this rung is **globally serialised**: one
episode at a time, never concurrent. The gates keep it off the CPU
during playback and while Sonarr's search queue is congested.

### R4 — Exhausted

Record the outcome, increment attempts, set `next_retry_at` on
exponential backoff (1d → 3d → 1w → 4w cap), and surface the episode in
the UI as "no English source".

## State

```sql
CREATE TABLE IF NOT EXISTS subtitle_ladder_state (
    episode_id      INTEGER PRIMARY KEY,
    last_rung       TEXT NOT NULL,
    outcome         TEXT NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TEXT,
    next_retry_at   TEXT,
    updated_at      TEXT NOT NULL
);
```

This paces prioritarr's own R2/R3 triggers. It does not replace Bazarr's
adaptive search, which stays enabled and continues to govern Bazarr's
scheduled sweep on a separate code path.

## Gating

Both gates already exist and are reused rather than rebuilt:

- **Plex idle** — `PlexClient.sessionCount()` (`/status/sessions`) with
  the idle-tick debounce pattern from `decideTdarrPause`, which already
  handles Plex momentarily reporting 0 mid-playback.
- **Search congestion** — `SearchQueueControl.isCongested()`, already
  reading Sonarr's command queue to detect search-queue congestion.

**Gates fail closed.** This is a deliberate departure: `SearchQueueControl`
fails safe as *not congested*, and `decideTdarrPause` treats a probe
error as 0 sessions. For a CPU-bound rung that is backwards — a false
"idle" during a live stream is the costly mistake. A Plex probe error
therefore means **busy**, and the sweep stands down.

## Error handling

Every rung failure is non-fatal: record it, move to the next episode.
Outcomes are distinguished so that infrastructure problems never look
like "no subtitle exists":

| Outcome | Cause | Retry |
|---|---|---|
| `SATISFIED` | `.en.srt` present | — |
| `NO_SOURCE` | All rungs tried, nothing found | Exponential backoff, consumes an attempt |
| `UPSTREAM_DOWN` | Bazarr/Whisper unreachable or timed out | Short fixed retry, **does not** consume an attempt |
| `UNREADABLE` | ffmpeg/ffprobe failed (corrupt file) | Long backoff |
| `BLOCKED_VARIANT` | Variant present, expensive rungs skipped | Only re-evaluated if the file changes |

An existing `.en.srt` is never overwritten. Existence is re-checked
immediately before the atomic move, closing the window where Bazarr
lands a sidecar mid-Whisper-run.

## Settings

Live-editable via the existing YAML-plus-DB-override pattern:

```yaml
intervals:
  sub_ladder_interval_minutes: 30
  sub_ladder_max_per_sweep: 10
```

```
PRIORITARR_SUB_LADDER_ENABLED
PRIORITARR_SUB_LADDER_WHISPER_ENABLED
PRIORITARR_SUB_LADDER_WHISPER_MAX_PRIORITY   # default 2 (P1/P2)
```

New `JobId.SUB_LADDER = "sub-ladder"`.

## Testing

The decision core is pure and seam-injected, matching
`SearchQueueControl` and `decideTdarrPause`:

- `classifySidecars(...)` → all four sidecar forms, including the
  `.en.forced.srt`-alone case and bare `.srt`
- `nextRung(...)` → ordering, the priority gate on R3, and the variant
  rule blocking R2/R3 but not R1
- `backoffFor(attempts)` → schedule and the 4-week cap
- `decideLadderGate(...)` → both gates, and specifically that a Plex
  probe error yields *busy*
- `matchesLang` → tag wins over title; title fallback only when the tag
  is absent; SDBH S06E01/E02 as the regression case
- Outcome mapping → `UPSTREAM_DOWN` does not consume an attempt

Bazarr, Whisper and ffmpeg are fakes in tests. Filesystem behaviour
(extraction, atomic move, never-clobber) uses temp dirs, as
`SubtitleExtractorTest` already does. Whisper's *translation quality* is
explicitly not under test.

## Rollout

Sequenced so each step is independently verifiable:

1. Ship with `sub_ladder_enabled=false`.
2. Land the `matchesLang` and `hasSidecar` fixes alone — cheap, and
   immediately recovers untagged-English files like SDBH S06E01/E02.
3. Enable R1 + R2 with Whisper off. No Bazarr configuration changes.
   Watch the backlog drain.
4. Enable R3 for P1/P2 once R2's yield has plateaued.

Bazarr settings are left exactly as they are — `adaptive_searching`
stays on, and `use_whisper_fallback` stays off, since prioritarr owns
the Whisper rung directly and Bazarr's whisperai provider would only
duplicate it.

**Residual risk.** The manual per-episode path has no adaptive guard,
so for R2 the only pacing is prioritarr's own: the per-sweep cap, the
two gates, and `subtitle_ladder_state` backoff. A bug in that backoff
would mean provider hammering, with no Bazarr safety net on this
specific path. The cap is the primary guardrail and should be
conservative on first enable.

## References

- Prior spec (Phase 2c never implemented):
  `docs/specs/2026-04-30-subtitle-orchestration-and-health-banners-design.md`
- Scheduler singleton guarantee: commit `e3199b0`
- Reused: `orchestration/SearchQueueControl.kt`,
  `orchestration/TdarrPlexPause.kt`, `clients/Bazarr.kt` (currently dead),
  `reconcile/SubtitleExtractor.kt`
