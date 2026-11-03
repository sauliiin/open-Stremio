# Playback stalls: "it freezes and only comes back when I press forward"

Read this before changing anything about buffering, retries, the disk cache or the
stall watch in `:player`. This problem was "fixed" six times before anyone measured it.
Most of what those fixes assumed turned out to be false, and some of it was still
written in the comments as fact.

**State of play (v.1.1.35):** reproduced on demand in a lab, causes found, fixes measured.
**Not yet confirmed on the real TV with real Real-Debrid links**, because neither was
available to test against. See [What is still unproven](#what-is-still-unproven). If the
symptom comes back, the first thing to do is get `adb logcat -s Playback` from the TV and
read it with [Reading the log](#reading-the-log).

**After v.1.1.35:** live addons on the emulator showed two more problems, both in
debrid resolve links: every request for a film resolved it again (cause 16), and the
opening probes flooded the addon until it answered 429. See **Opening** under
[How it works now](#how-it-works-now).

## The symptom

On the TV, on some films and not others, the picture stops mid-film and stays stopped.
Pressing forward brings it back at once. It does not come back alone, or takes so long
that it might as well not.

## What actually causes it

Each row was measured in the lab or read in the Media3 1.11 source, as noted.

| # | Cause | How it was established | Fix |
|---|---|---|---|
| 1 | **Our retry policy retried bytes the demuxer rejects, forever.** `getRetryDelayMsFor` was overridden since `4b5d19d` and dropped Media3's rule that `ParserException` is not retriable. The same bytes come back on every retry and from every connection. Reopening lands on the same keyframe. Only a seek past the damage escapes, which is the viewer's forward button. | Lab `corrupt`: `ParserException: Invalid integer size: 120 {contentIsMalformed=true}` in a loop, frozen >134s. A simulated forward press recovered in under a second. | `skipDamage` skips ahead by itself. |
| 2 | **A connection delivering above 20% of real time was "healthy".** The stall rule asked for 2s of buffer per 10s. At 36% it never fired, and the film stopped for 28s, played 15s, and repeated. Forward works because it opens a new connection. | Lab `slow-conn`: 82s of 150s frozen, 0 repairs logged. | Healthy now means 60% of real time. |
| 3 | **A seek cannot get past a read on a silent socket.** `Loader` runs on one thread and cancels by interrupting it. A thread blocked in a socket read ignores interrupts, and the new load queues behind it until the read timeout, then 30s. So the old "soft reconnect" did nothing until the timeout fired. | `Loader.java` and `ProgressiveMediaPeriod.java`. Lab `blackhole`: soft reconnect logged at 06:42:35, the request reached the server at 06:42:40, exactly 30s after the fault. | `AbortableCallFactory`: cancelling the call closes the socket. |
| 4 | **Nothing watched the connection until the buffer was empty.** 15s of warning were wasted, so every bad connection became a frozen picture first. | Lab `blackhole`: `loading=true` and a static buffer edge for 15s before the first reaction. | The stall watch judges the connection while the film is still playing. |
| 5 | **An expired link froze for minutes.** 403/404 were retried 12 times with backoff, then reopened 6 times. | Lab `expired`: frozen >104s with a healthy second candidate in the queue. | Fatal at once, straight to the next candidate. |
| 6 | **Disk read-ahead never stored a byte.** A read-through `CacheDataSource` locks the whole hole it is filling, to the next cached span or the end of the file. The prefetcher could never get a lock anywhere ahead of the player, so it downloaded into the bypass and discarded it. After 3 chunks (48MB of wasted link) it gave up. The previous explanation, eviction, was wrong. | `SimpleCache.startReadWriteNonBlocking`, `CachedContent.lockRange`. Log in every run: `standing down: N re-fetched 3 times`. | The player reads the cache without writing. The prefetcher is the only writer. |
| 7 | **LRU eviction deleted the film that was coming up.** A span just played counts as recent, and one waiting to be played does not. | Lab: read-ahead fell from 481MB to 91MB once the cache filled. | `OldestWrittenCacheEvictor`. |
| 8 | **The cache was sized by a quota that is not a limit.** `getCacheQuotaBytes` gave 207MB on the emulator and 156MB on a TV, about what the heap already holds. Raising `MAX_BYTES` changed nothing. | Log: `disk cache 207MB`. | A quarter of free space, up to 4GB. |
| 9 | **A demuxer can consume a file and produce nothing, with no error.** Inside a long damaged stretch, garbage parsed as a huge element made `MatroskaExtractor` skip through the rest of the file: 1.8GB read, no sample, no error until an `EOFException` at the end. On a real link that is hours of freeze using the whole connection. Reconnecting and reopening restore the same parser state. | Lab `corrupt-60-65536`: frozen 103s, `sent=1839238300` in the server log. | `DemuxWedgeDetector`: bytes read, allocator flat. |
| 10 | **The stream order chose the worst file on purpose.** Sharpest, then biggest, with no notion of cached or not. A 70GB remux beat a 20GB one, and an uncached 4K link beat a cached 1080p one. | `rankForPlayback`. Explains part of "only some films", but was not measured with real links. | `SourceReadiness`, then a bitrate guard. |
| 11 | **Garbage can crash the extractor instead of failing to parse.** `MatroskaExtractor` throws `ArrayIndexOutOfBoundsException` or `IllegalStateException: No valid varint length mask found`, which `Loader` wraps as `UnexpectedLoaderException`. That was not recognised as damage, so cause 1 came back by another door, and reconnects and reopens landed on the same bytes. Forward still got out of it. | Lab `damage-hopeless`: frozen 99s. `damage-speckled`: stuck at 16s for 137s. | Counted as damage. |
| 12 | **In a film with no index, every seek goes back to zero.** An MKV without `Cues` has an unseekable `SeekMap`, and `ProgressiveMediaPeriod` answers any seek with position 0. Skipping past damage restarted the film, which replayed up to the same damage, for ever. Forward did the same to a viewer. | Lab `corrupt@nocues.mkv`: 120s in, position 19s, three loops. | No seeks there: damage goes to the cascade, forward does nothing. |
| 13 | **Windows laid end to end let a burst hide what came after it.** A connection delivering 10s of film in 1.5s and then dying passed that window as healthy, and was noticed a window later, after the film had run out. A backed-off window could also keep a dead socket unnoticed for up to 64s. | Lab `flaky-silent`: frozen 29s of 180s. `flaky-slow`: 20s of 180s. | `StallTracker` judges the trailing window at every poll, and silence is never backed off from. |
| 14 | **A host answering an error status was treated like a silent one.** It got the whole escalation, about 2 minutes frozen, with a healthy candidate waiting. An error page sent as a 200 fared little better: the load fails with `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`, which nothing recognised. | Lab `unavailable` (503): frozen >104s. `decoy`: 23s. | With somewhere else to go, the first reopen goes to the cascade instead. A missing position counts as a dead link. |
| 15 | **A link given up on during opening could still commit.** The third error before a first frame is fatal, but with samples already queued no error reaches the player. With nothing left to load, the engine goes READY, with a clock that cannot move, and READY is what commits a source. So the queue was never tried. | Lab `damage-start` with a healthy candidate: first frame at 11s on the damaged source, saved only by the `clock frozen` flush and a skip. | `onReady` sends such a link to the next candidate, if there is one. First frame at 3s. |
| 16 | **Every request for a debrid film went through the addon again.** A Torrentio link is a resolve URL that answers a 302 to Real-Debrid. OkHttp follows it inside each call and Media3 always asks the URL it was given, so the header, the index, every seek, every reconnect and every 16MB read-ahead chunk was resolved again: about a second each on Torrentio, two or more through AIOStreams, which redirects twice. Torrentio's resolve route sits behind an nginx `limit_req` per public IP, so a seek could meet a 429 and stop the film. | Live, on the emulator: `429 Too Many Requests` from `nginx/1.27.5` behind Cloudflare, on Torrentio's resolve route. Lab `resolver` (1s per resolve): 75 resolves in 150s. `resolver-limited` with a seek every 20s: a seek met a 429 and the picture stopped for 20s. The lab limiter's numbers are a model, not Torrentio's. | `ResolvedLinks` keeps where a resolve led, for every request after it. |

Why only some films: causes 1, 9, 10, 11, 12 and 15 depend on the file. Cause 16 depends on
the addon, and on what else in the house is using it. The rest depend on the
mirror serving it at that moment, and they hurt in proportion to the bitrate.

### What it was not

Not RAM. No run came near a heap problem, and a bigger buffer only delays causes 1 to 5.
See [RAM and disk](#ram-and-disk). Small damage is also harmless: 1KB and 64KB of garbage
played through, because the demuxer only fails when the container's structure breaks.

## Earlier attempts, and why they missed

Commit messages here are terse, so these come from the diffs and release notes.

| Version | Believed | Changed | Why it did not fix it |
|---|---|---|---|
| `6427c73` 08-10 | Buffer too small | `AdaptiveLoadControl`, `HeapBudget`, `MediaCache`; max 120s, 160MB | Bigger cushion, same causes |
| `4b5d19d` 08-11 | Mirrors pause, be patient | min/max 120s/600s, 320MB, custom retry policy (12 retries) | **Introduced cause 1** |
| `44da063` 08-12 | Heap reading stalls the loader | `MemoryPressure`, 448MB, removed the heap veto | Real bug, but not this one |
| `51de204` 08-18 | The heap cannot hold a deep buffer | `MediaPrefetcher`, 128/256MB floors, 2GB disk | **Never worked, see cause 6** |
| `c75449e` 08-19 | A quiet link never errors | First stall watch: reopen after 10s | Reacted only once the buffer was empty, with a re-prepare |
| `1be88c3` 08-27 | A seek opens a new socket | `softReconnect` before reopening, suspicion after pauses | **False on a silent socket, see cause 3** |
| v.1.1.25 | Prefetch competes for the link | 5s/10s thresholds, stop prefetch while loading | Prefetch stored nothing anyway |
| v.1.1.28 `a788bf6` | The link trickles | `StallTracker`, 2s per 10s, `rebufferStartMs`, unproductive-chunk guard | The bar was too low (cause 2). The guard hid cause 6, and it blamed eviction |
| v.1.1.34 `d078579` | Allocator deadlock, audio clock stall | Starvation floor, loader-idle kick, frozen-clock flush | Plausible and kept, but never reproduced. Its notes said the freeze was fixed |

The pattern: each fix came from reasoning about the code, was checked by watching a film
that happened to play, and shipped as solved. **Nothing could reproduce the failure, so
nothing could disprove a fix.** That is what `stall-lab/` exists to change.

The lab caught this round's own mistakes too, each one in the extended battery after the
first set of fixes looked finished:

| Mistake | How it showed |
|---|---|
| Damage recognised only as `ParserException` and bare `EOFException` | Cause 11 |
| Skip without checking the file can seek | Cause 12 |
| Damage counted as reached only under 1s buffered. The loader is stuck, so 1.3s left in front of it can never grow, and the player will not restart on so little | `damage-speckled` deadlocked after its first skip |
| Damage evidence that never expired. One bad answer from a host would have blinded the stall watch for the rest of the film | Found reading the code, not by a run |
| Skips that never happened counted anyway, because `seekTo` returns early during a reopen | `decoy`: eight skips logged at one position |
| A file given up on as damaged retried on the queue's second pass | `corrupt@nocues.mkv` replayed 49s just to fail again |
| The attempt watch stopped dropping damaged links, on the assumption that the fatal third error would reach `onPlayerError`. It does not when samples are queued | Cause 15, in the last battery, and only because 2s had become 11s |

## How it works now

**Data path** (`PlaybackDataPath`). The player reads through a `CacheDataSource` with no
write sink. `MediaPrefetcher` downloads from the loader's position forward and is the only
writer. When the loader reaches those spans, its periodic look at the cache moves it from
the socket to the disk, and from then on the prefetcher is the only thing on the network.
`LoaderTap` is how it knows where the loader is.

**Load control** (`AdaptiveLoadControl`). Refills in bursts: once the allocator is full it
waits until it has drained to 85%. Without this the loader wakes for every sample played
and the prefetcher never gets the link for long enough.

**Stall watch** (`startStallWatch`, `StallTracker`), each second, in this order:

1. Picture stopped with damage standing (see `DamageEvidence` below): `skipDamage`, at once
   under 1s buffered, after 3s otherwise. Steps 5s, doubling to 60s. After 8 skips without
   60s of quiet, or at once in a film with no index, the source goes to the cascade, which
   resumes from where the damage began and never reopens that source.
2. Buffering with the loader idle for 2s: flush with a seek of 10ms.
3. Playing with the clock frozen for 4s: the same flush.
4. Otherwise `StallTracker` judges the connection over the trailing window, at every poll,
   while the loader is active:
   - Under 20% of real time over 8s (5s with the picture stopped): `reconnect`, which aborts
     the HTTP call. The load resumes from the same byte, and nobody can see it. Never backed
     off from.
   - Under 60%: the same, and the window doubles, to 64s.
   - Under 20% with the picture stopped, after 2 reconnects: `reopenCommitted`, the only rung
     that resets the decoders. After 6, the cascade takes over. If the host is answering
     with an error status and there is another candidate, the first reopen goes to the
     cascade instead.

**Load errors** (`loadErrorPolicy`). Retried without a count, 250ms then a ramp to 5s. The
stall watch decides when to give up. Exceptions:

- **Damage** (`isDamagedContent`: `ParserException`, bare `EOFException`,
  `UnexpectedLoaderException`) is recorded in `DamageEvidence` once the same bytes have failed
  twice. It stops standing when the buffer's edge moves 2s past where it was recorded.
- **A dead link** (`isLinkGone`: 400/401/403/404/410/416, or nothing at the position asked
  for) is fatal at once.
- **Before a first frame**, the third error of any kind is fatal, so a dead mirror costs
  seconds. Three 15s read timeouts is the attempt's whole 45s cap, so a cold debrid link still
  gets all of its time. With samples queued the error never surfaces and the player goes READY
  instead, so `onReady` checks for it (cause 15).

**Opening** (`StreamsRepository.candidates`). Every addon is asked at once. Whatever has answered after
1.5s is ranked, and the best is handed to the player unprobed. The rest are probed with a
two-byte range request and handed over best-first, and a link that fails the probe moves to
the end of its batch. The player walks them in that order (`tryAdvance`).

A probe of a debrid link is the addon resolving it with the debrid service, so `ProbeGate`
allows two at a time per host, and eight per host per title. Past that the cascade reaching a
link is its test. Links the debrid service has not fetched are never probed, and a 429 from
the addon counts as no answer, not as a broken link.

`ResolvedLinks`, an interceptor on both the probe client and the playback client, keeps where
a resolve led: a successful answer from another origin, at least 8MB, for 6 hours, which is
Torrentio's own `max-age` on the redirect. Every later request goes to the CDN. A link the
probe resolved is opened by the player without asking the addon again. A kept link that
fails in any way is forgotten and the request goes through the addon once more, so an
expired one costs a request, never an error.

Nothing leaves the queue for its rank or for a failed probe. The cascade fails only once every
link has failed twice **and** 60s have passed since play (`PERSIST_MS`). A short queue, which
is what a little-known film often has, is walked again after a pause of 2s doubling to 15s, so
a single link refused for a while still plays. Notices and damaged files are never opened
twice. When every link was one the debrid service had not fetched, each opened as its
notice, and the error says so (`NotCachedYet`) instead of "none opened".

**Ranking** (`rankForPlayback`). Real-Debrid cached, other debrid cached, unknown, a torrent
from its swarm, not cached. Then resolution. Then files at or under 50Mbps before those above, within one
resolution only. Then size. Readiness is read from labels: `[RD+]`, `[RD download]`,
`[RD⚡]`, `[RD⏳]`, `[RD⬇️]`, `⚡️`, `⏳`, `Not Cached`, `Not Ready`, `Instant`, taken from the
sources of Torrentio, AIOStreams, Comet and MediaFusion. A new addon format lands in
`UNKNOWN`, which is safe but gets no preference.

**Torrents without debrid** (`:torrent`). A link that is only an `infoHash` used to be
dropped, so a viewer with no debrid service had almost nothing to watch.
`TorrentStreaming.url` now gives it a loopback address that `LocalTorrentServer` answers
like any file host, ranges included. Each read waits for the pieces it needs, and that wait,
`TorrentEngine.awaitPiece`, is what steers libtorrent: deadlines on the next 8 pieces,
sequential order for the rest, and after a jump nothing wanted behind it but the two ends of
the file. The player is not told it is a torrent, so the cascade, the stall watch, seeking
and resuming are the same code. What differs:

- Never probed: probing would start the download.
- Never read ahead into the disk cache: the torrent is on disk already.
- Ranked after every link a server holds and before an uncached debrid link. Among
  torrents, a file that fits in free space first, then a swarm of 10 seeders or more.
- One torrent at a time, removed with its data when the player opens another source or
  closes. A folder per process, and the others are deleted at start.
- Upload on, capped at 2 Mbps, nothing seeded after: a peer that never uploads is served
  last.
- POSIX disk I/O: libtorrent 2 maps files into memory by default, and a 20GB film does not
  fit in a 32-bit TV's address space.
- With no peer at all, the trackers and the DHT are asked again every 15s, at most.
  libtorrent's own minimum between announces is five minutes.

## Rules that must keep holding

Each of these was broken at some point, with the result given.

- **Do not give the player a cache write sink.** It kills read-ahead silently (cause 6).
- **A seek does not reconnect.** On a silent socket only `abortPlayerRead` does (cause 3).
- **Parser errors must keep reaching the stall watch.** A retry policy that swallows them
  brings cause 1 straight back.
- **Only a bare `EOFException` means damage.** Wrapped in an I/O error it is a connection
  closing while its headers were read, and treating that as damage skips film on a hiccup.
- **Do not skip as soon as damage is recorded.** The loader finds it long before the picture
  does, and skipping early throws away film that is fine.
- **Do not wait for the buffer to empty before skipping, either.** A stuck loader cannot refill
  it, and the player will not restart on what is left.
- **Damage evidence has to expire** (`DamageEvidence`). While it stands the tracker is off, and
  the next ordinary stall becomes a skip.
- **`errorCount` is not "failures in a row".** It restarts at 1 on progress, and the next error
  is already 2. Only 3 always means the same bytes failed twice.
- **Do not seek in a film with no index** (`isCurrentMediaItemSeekable`): it goes back to zero.
  The one exception is the last-resort flush in steps 2 and 3, because a film starting over
  beats a film frozen for good.
- **Do not judge a connection on windows laid end to end** (cause 13).
- **READY does not prove a link works, and a fatal load error does not prove the player will
  hear about it** (cause 15).
- **Tell a wedge from a bad interleave by the allocator, not by time.** Both read bytes with
  the buffer edge still. Only one of them keeps what it reads.
- **Do not reopen a link that is merely slow.** A reopen discards the buffer, and the next
  connection is just as slow.
- **Backoff has to apply with the picture stopped too.** Reconnecting every 5s on a link at
  56% of real time cost 8s of extra freeze in 150s, measured.
- **The tracker must outlive a rebuffer.** `onReady` fires on every READY, and restarting
  it there loses the backoff.
- **A timeout is not a refusal.** Counting timeouts against the prefetcher killed read-ahead
  for the rest of the film after one outage. Only HTTP answers count.
- **Do not use `LeastRecentlyUsedCacheEvictor` for read-ahead** (cause 7).
- **Never touch `player` from `getRetryDelayMsFor`.** It runs on the playback thread and
  crashes the app with "Player is accessed on the wrong thread". This happened while
  adding a log line.
- **Do not raise the probes per host to start faster.** With sixteen at once, Torrentio
  answered 429 to most of the next twenty and Real-Debrid answered some with Torrentio's
  `failed_unavailable` video, so working links went to the back. The player's own request,
  sent alongside, meets the same limit. The limit is per public IP, shared with every
  device in the house. Measured live.
- **Do not probe a link that is not cached.** Resolving it tells the debrid service to
  start downloading the torrent.
- **A 429 says nothing about the link.**
- **`ResolvedLinks` must stay on both clients** (cause 16). Never keep a redirect to the
  addon's own host, or to a file far smaller than a film: those are the addon's notices.
- **Do not fail a short queue in seconds.** Two passes over one link take 4s when its host
  answers 429 or 503, and a host doing that is usually back seconds later.
- **A read of either end of a torrent's file is not the playhead.** The player reads the
  header and the index there. Moving the focus there marked the whole film unwanted, the
  torrent counted as finished, dropped its seeds, and stalled a minute until libtorrent
  called them again. Measured.
- **A finished torrent keeps its seeds** (`close_redundant_connections` off), for the same
  minute after a seek back.
- **Every call on a torrent handle goes through `Torrent.use`.** A removed torrent's handle
  throws, and a reader can still be mid-piece when the film closes. In the lab that
  exception ended the app.
- **A torrent is added unpaused and not auto-managed.** libtorrent adds it paused for its
  queue to start; without the queue it never starts.
- **Do not reintroduce HTTP/2 for playback.** A new request must mean a new socket. The
  probe client must use the same protocols, or the warmed connection is never reused.

## Measurements

Emulator `Television_4K` (x86, 2GB RAM, Android 36), real `PlaybackController` over the real
`HttpClients.playback`. Film at 32Mbps with one AAC and two PCM 5.1 tracks, the second and
third unselected to stand in for remux audio. Fault at 60MB, about 15s in. Runs of 60s to 180s,
sampled each second, so "0" means under about a second. "Before" is v.1.1.34, and "after" is
the v.1.1.35 code. No run crashed or ran out of memory.

| Scenario | What it does | Before: longest / total frozen | After |
|---|---|---|---|
| `corrupt` | 4MB of garbage at 60MB | >134s / >134s, **never recovers** | 0 / 0, skips 4s (5 runs) |
| `expired` | Link starts answering 403, fallback in queue | >104s, **never recovers** | 1s / 1s (2 runs) |
| `slow-conn` | That connection falls to 36% of real time | 28s / 82s | 0 / 0 (2 runs) |
| `blackhole` | That connection stops sending | 15s / 15s | 0 / 0 (3 runs) |
| `trickle-conn` | That connection falls to 6% | 9s / 9s | 0 / 0 (2 runs) |
| `outage-90` | Whole host silent for 90s | 41s / 41s | 0 / 0 |
| `one-conn` | Host refuses a second connection | 0 / 0 | 0 / 0 |
| `healthy` | No fault | 1s / 1s, at startup | 0 / 0 |
| `slow-link` | **Every** connection at 56% of real time | 17s / 61s | 18s / 61s |

Everything else has no v.1.1.34 run to compare with:

| Group | Scenario | Result |
|---|---|---|
| Damage | `damage-start`, fallback in queue | First frame 3s on the fallback (3 runs) |
| | `damage-start`, alone | First frame 10s, skips 4s |
| | `damage-transient`, `corrupt-80-16384` | 0 / 0, skips 4s |
| | `corrupt-40-1`, `corrupt-40-64`, `corrupt-1900-1024` | 0 / 0, no skip |
| | `corrupt-60-65536` | 1s / 2s, skips 30s |
| | `damage-speckled` | 4s / 4s, skips 32s, plays on |
| | `damage-many`, `corrupt-120-512` | 0 / 0, but jumps 57s (see below) |
| | `damage-hopeless`, fallback in queue | 2s / 9s, resumes on the fallback |
| | `damage-hopeless`, alone | Error at 33s |
| Repeated failure | `flaky-silent`, `flaky-slow`, `flaky-reset` | 0 / 0 |
| Hosts | `dead-host`, fallback in queue | First frame 3s |
| | `cold-20`, `cold-40` | First frame 21s, 41s, no freeze after |
| | `cold-50`, fallback in queue | First frame 47s on the fallback |
| | `unavailable` (503), fallback in queue | 11s / 11s |
| | `unavailable`, alone | 103s frozen, then error at 120s |
| | `decoy`, fallback in queue | 1s / 1s |
| | `decoy`, alone | Error at 18s |
| | `truncated`, all four files | Reaches the fallback, 1s at most. `fast.mp4` loses 6s of film |
| | `no-range` | First frame 14s, 0 / 0 |
| Formats | `blackhole`, `corrupt`, `slow-conn` on `high.mkv` and `movie.mp4` | 1s / 1s at most. `corrupt` on `movie.mp4` skips 6s |
| | `corrupt@fast.mp4` | 0 / 0, skips 5s |
| | `blackhole@nocues.mkv` | 0 / 0 |
| | `corrupt@nocues.mkv`, fallback in queue | 1s / 1s |
| Viewer | `seek-storm`, every 7s | 1s / 5s |
| | `seek-storm-flaky`, every 11s | 1s / 2s |
| | `forward-on-dead-socket`, `reopen-screen` | 1s / 1s |
| | `forward-no-index`, `change-title`, `pause-on-dead-socket` | 0 / 0 |
| Device | `low-storage-corrupt`, `memory-pressure`, `heavy-app-outage`, `disk-wiped` | 0 / 0 |
| | `resume-midfilm` | 0 / 0 |
| | `full-film` | 0 / 0, reaches ENDED |

**How far these numbers go:**

- Cause 15's fix came after the last full run and acts only before a source commits, so only
  the opening scenarios were rerun on it: `damage-start`, `dead-host`, `cold-50`, `decoy`,
  `unavailable`, `corrupt`, `truncated`, `healthy`, `blackhole`. They all held. Any other
  change to the error policy still needs everything rerun.
- These ran only on the build before the sliding `StallTracker`: `rst`, `outage-30`, `low.mkv`,
  `interleaved.mkv`, `blackhole@fast.mp4`, `low-storage-healthy`, `low-storage-blackhole`,
  `heavy-app`, all 0 / 0 or 1s. `outage-180` froze 10s. `blackhole@interleaved.mkv` took 18s to
  a first frame, from a read timeout at startup.
- `pause-180` never ran. `pause-on-dead-socket`, a 120s pause, is the nearest.

`slow-link` is the physical limit: no player fix sustains a film above the link's speed.
The ranking guard reduces how often that happens. Falling back to a smaller file mid-film
would be the next step, and it conflicts with "a committed source is never swapped".

`corrupt-120-512` and `damage-many` deserve a look if they ever matter: garbage in the wrong
place makes `MatroskaExtractor` read a huge element length and jump about 57s with no error at
all. It does not freeze, but the film skips and we cannot see it.

### Debrid resolve links

The same rig, through a `/resolve/` path that answers a 302 to the file on another port after
1s, as Torrentio's links do. `resolver-limited` adds a limit of 10 at once, then 0.5 a
second, answering 429 above it. "Before" is v.1.1.35 and "after" adds `ResolvedLinks`.

| Scenario | Before | After |
|---|---|---|
| `resolver` | First frame 4.0s, 75 resolves in 150s | 2.0s, 1 resolve |
| `resolver-limited` | First frame 4.0s, 0 / 0. 70 resolves, and 52 more requests answered 429 | 2.0s, 0 / 0. 1 resolve, no 429 |
| `resolver-limited`, seek every 20s | 83 resolves, 22 answered 429. Each seek 1.2s, and one met a 429: **18s / 19s** | 1 resolve. Each seek 0.06s to 0.41s, 1s / 1s |
| `resolver-expired`, fallback in queue | - | 1s / 1s, as `expired` |
| `resolver-blackhole` | - | 0 / 0, as `blackhole` |
| `resolver-corrupt` | - | 0 / 0, skips 3.8s, as `corrupt` |

Live, on 2026-11-03: The Nun II (2023), with Torrentio (Real-Debrid) and AIOStreams
(ElfHosted). Only the source picker was opened, so nothing played. Two runs before, one after:

| | Before | After |
|---|---|---|
| Torrentio links probed | 35, 17 of them not cached | 8, all cached |
| Answered 429 by Torrentio | 24, 15 | 0 |
| A cached link answered with Torrentio's `failed` video | 2, 4 | 0 |
| AIOStreams links probed | 58, 59, with 15 over the 3.5s timeout each time | 8, 1 broken |

### Few sources

One candidate, nothing to fall back to: a little-known film. `busy-<status>-<seconds>` refuses
every request with that status for that long, then serves the file. "Before" is v.1.1.35.

| Scenario | Before | After |
|---|---|---|
| `busy-429-20` | Error at 4s | First frame 21s |
| `busy-503-45` | Error at 4s | First frame 54s |
| `dead-host` | Error at 4s | Error at 72s, the price of the rest of this table |
| Only a notice (`healthy`, `LAB_RUNTIME_MIN=100`, `LAB_READINESS=NOT_CACHED`) | Error at 1s, "none opened" | Error at 1s, `NotCachedYet` |

Unchanged, rerun on the same build: `healthy` 0 / 0; `dead-host`, `decoy` and `expired` with a
fallback, first frame 3s, 1s / 1s and 1s / 1s; `damage-hopeless` alone, error at 33s.

### Torrents without debrid

`torrent_seeder.py` on this machine: one seeder, its tracker, the same 32Mbps film (4MB/s).
`--rate` caps the swarm. The final build unless noted, libtorrent4j 2.1.0-35.

| Scenario | Result |
|---|---|
| Unlimited swarm, 6 runs | First frame 5s to 7s, 0 / 0, one run 1s / 1s |
| Swarm at 6MB/s, 1.5 times the film | First frame 8s, 0 / 0 |
| Swarm at 3MB/s, 0.75 times the film | 13s / 38s in 150s: the physical limit, as `slow-link` |
| Seek every 20s, unlimited | 1s / 2s |
| Seek every 20s, 6MB/s | 5s / 24s: each seek waits for its pieces |
| Resume at 4 min | First frame 6s, 0 / 0 |
| The whole film, twice | Reaches ENDED, 0 / 0, heap peak 224MB, torrent removed after |
| Swarm leaves at 40s, fallback in queue | 11s / 11s, then plays on the fallback |
| Swarm leaves at 40s, alone (build before the edge fix) | Waits for it: 91s frozen at 150s |
| Nobody seeding, fallback in queue (build before the edge fix) | First frame 39s on the fallback |
| A real swarm, twice: Big Buck Bunny (CC BY) from archive.org | Metadata 3.1s, the `.mp4` picked by name from 12 files, first frame 14s, 1s / 1s, about 200KB/s from one seed |

`skipped` in the seek runs is the test's own forward seeks: the HTTP seek runs show the same
1.5s to 3.3s.

What the lab caught on the way, each a bug in this code and not in the seeder:

| Mistake | How it showed |
|---|---|
| Added paused: libtorrent leaves that to its queue, which was turned off | No peer ever, no frame in 90s |
| The index read at the end taken for the playhead: everything else unwanted, torrent "finished", seeds dropped | A minute frozen right after the first frame, in 2 of 3 runs |
| A removed torrent's handle used by a reader still mid-piece | `invalid torrent handle used`: the app crashed |
| A swarm that left answered with silence, so the player kept reopening it | 93s frozen with a working fallback queued |
| The wedge detector read the server's "what has arrived, then wait" as cause 9 | 5s of film skipped after a reopen |

`--start-after 30`, a swarm that is empty when the film opens and arrives later, still fails
in the lab: the tracker is only asked again 90s later, whatever its `min interval` says.
Forcing it past that is how an IP gets banned from public trackers, so it is not done. The
DHT is asked every 15s while there is no peer, and on the real swarm above that is where
the peers came from.

## RAM and disk

The question was whether to double both. **Disk yes, RAM no.**

**RAM** was measured with a `LAB_BALLAST_MB` standing in for the app's own heap, on a 576MB heap
limit and 2GB of RAM. The instrumented process has no Compose or Coil, so without it the
figure reads better than it is.

| Budget | Stand-in | Result |
|---|---|---|
| 192MB (current) | 120MB | 5 min, no freeze. Heap peak 337MB, PSS 291MB, 460MB of RAM still available |
| 384MB (doubled) | 120MB | 5 min, no freeze, no crash. Heap peak **541MB of 576MB**, PSS 493MB, 204MB available |
| 192MB (current) | 200MB | 3 min, no freeze. Heap peak 417MB |
| 384MB (doubled) | 200MB | **`OutOfMemoryError` on the main thread 5s in, once the buffer filled. The app dies** |

Doubling gained nothing: `outage-90` had no freeze with either budget, because the disk now
carries it. It also moved a film from comfortable to one heavy screen away from a crash. The
target TV's `dalvik.vm.heapgrowthlimit` matches this emulator's (192m), but its `heapsize`, the
limit `largeHeap` actually gets, was never read, so these figures are not a promise about it.
The budget stays as it is.

**Disk** is what actually gives more buffer: free of heap, and an outage that froze for 41s now
does not freeze at all. Raising `MAX_BYTES` from 2GB to 4GB alone would have changed nothing,
because the quota bound first (cause 8). On the emulator the real budget went from 207MB to
641MB. Read-through stored every byte the player read, so disk writes per film grow by at most
one window.

## How others do it

Read from their sources on 2026-11-02.

| | This app | NuvioTV | Stremio |
|---|---|---|---|
| Protocol, read timeout | HTTP/1.1, 15s | HTTP/1.1, 15s | - |
| Resume after rebuffer | 10s (lower on tight budgets) | 3s | - |
| Stall reaction | Rate-based, while playing | Seek past buffer after 15s with no movement, only while buffering | - |
| Fatal HTTP codes | 400/401/403/404/410 | The same | - |
| Parser errors | Skip ahead | Default policy: fatal, error path | - |
| Disk read-ahead | One sequential writer | Parallel chunked connections | - |
| Cached links first | Yes, from labels | Autoplay takes addon order or a regex | No ordering found in `stremio-core` |

Stremio's Android TV player is not public. Only `stremio-shell` (mpv, 10-15s of cache),
`stremio-video` and `stremio-core` could be read, so that column is thin. NuvioTV is where the
HTTP/1.1 choice, the timeout and the fatal codes came from.

## Reading the log

`adb logcat -s Playback`

| Line | Meaning |
|---|---|
| `buffer budget XMB from heap ... disk cache YMB` | What this box was given. Small `YMB` means little free storage |
| `addon X answered in Yms with N links` | One per addon. Past 1.5s it missed the first ranking and its links come after |
| `probed N links in Xms: ...` | One per batch. `REFUSED` is the addon answering 429, `UNTESTED` is a link not probed |
| `queue of N walked K times by +Xms, again in Yms` | A short queue being retried. Many of them and then an error mean the links are really dead |
| `torrent X metadata after Yms: file n of m, ...` | The swarm answered. Missing means nobody is seeding it, or nobody was found |
| `torrent X: AKB/s down, BKB/s up, N peers (S seeds), P% of the file` | Every 10s while a torrent plays. A download under the film's bitrate is the swarm, not the app |
| `torrent X has no peers, asking the trackers and the DHT again` | An empty swarm being looked for |
| `torrent read gave up waiting for piece N` | That piece did not arrive in 20s. The player reconnects and asks again |
| `torrent X failed, file: ...` | Usually the disk is full. That torrent is not tried again in this process |
| `open #n/m ... CACHED_REAL_DEBRID` | Which candidate and how ready. `NOT_CACHED` on the first attempt means nothing better was offered |
| `reconnecting at P: buffer edge B, state S` | A poor connection was replaced. `state 3` means nobody saw it. Many in a row mean a slow link, not a bad socket |
| `rebuffer at P with Xms buffered, A/BMB allocated, loading=` | The picture stopped. `loading=false` here is a deadlock, not a slow link |
| `rebuffer ended after Xms` | The number the viewer feels |
| `load error #n: ...ParserException` | The file is damaged at that point. Should be followed by `damaged file at` |
| `damaged file at P — skipping Xms` | The recovery for cause 1. Repeated growing steps mean a badly damaged file |
| `damaged at P after N skips — giving up` | Too much damage to cross, or `after 0 skips`, a film with no index |
| `load error #n: ...UnexpectedLoaderException` | The extractor crashed on what it read (cause 11). Treated as damage |
| `load error #n: ...Response code: 503` | The host is refusing. Repeated with the picture stopped, it goes to the next candidate |
| `read-ahead XMB on disk past the loader` | The cushion. Debug level, one line per 64MB of change |
| `bytes arriving and no film coming out at P` | Cause 9, followed by `damaged file at` |
| `read-ahead standing down: refused` | The host refuses a second connection. Everything still works, without the disk |
| `read-ahead standing down: ... not retained` | **Should never appear.** Spans are being lost: look at the evictor and the budget |
| `drop #n reason=abandoned` | A link that failed three times while opening, with samples queued (cause 15) |
| `clock frozen` | The v.1.1.34 rescue. In the lab only at the very start of `damage-start` with no other source, where it is the way out. Anywhere else, worth studying |
| `buffering with the loader idle` | The other v.1.1.34 rescue. Never seen in the lab, so a real one is worth studying |

## Reproducing it

`player/stall-lab/` holds `fault_server.py` (a host that misbehaves on request, with the
failure in the URL) and `run.sh`. `StallLabDeviceTest` plays through the real controller and
is skipped unless given a `lab_url`, so normal test runs ignore it.

```bash
# Writes the fixtures: movie.mkv (32Mbps, 8 min, 1.9GB), high.mkv (73Mbps), low.mkv, movie.mp4,
# fast.mp4 (index in front), nocues.mkv (no index), interleaved.mkv.
player/stall-lab/make_fixtures.sh <dir>

emulator -avd Television_4K -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
python3 player/stall-lab/fault_server.py <dir>   # also :8766, where its /resolve/ links lead
./gradlew :player:assembleDebugAndroidTest
adb install -r -t player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk

# One line per scenario, plus the full log in <out>/<scenario>.log
LAB_SECONDS=150 player/stall-lab/run.sh <out> healthy blackhole corrupt slow-conn
# <scenario>@<file> plays another fixture. The runtime must match it, or the cascade drops it
# as a decoy.
LAB_SECONDS=120 LAB_RUNTIME_MIN=3 player/stall-lab/run.sh <out> corrupt@nocues.mkv
LAB_SECONDS=120 LAB_FALLBACK=1 player/stall-lab/run.sh <out> expired

# A torrent: a seeder and its tracker on this machine. It prints LAB_TORRENT, which replaces
# lab_url as the first candidate. --rate, --start-after, --die-after shape the swarm.
python3 player/stall-lab/torrent_seeder.py <dir>/movie.mkv --rate 6000000
LAB_TORRENT=<printed value> LAB_SECONDS=150 player/stall-lab/run.sh <out> healthy
```

Runtimes in minutes: `movie.mkv` 8, `movie.mp4`, `fast.mp4` and `low.mkv` 5, `interleaved.mkv`
4, `high.mkv` and `nocues.mkv` 3.

Every scenario is listed in `run.sh`, and every fault in `fault_server.py`'s docstring. Options:

| Variable | Effect |
|---|---|
| `LAB_SECONDS` | How long to watch |
| `LAB_RUNTIME_MIN` | The runtime the app is told to expect |
| `LAB_READINESS` | Labels every candidate, `NOT_CACHED` for example |
| `LAB_FALLBACK=1` | Queues a healthy second candidate |
| `LAB_FORWARD_AT_S` | Presses forward |
| `LAB_PAUSE_AT_S`, `LAB_PAUSE_FOR_S` | Pauses |
| `LAB_START_MS` | Resumes mid-film |
| `LAB_BALLAST_MB` | Holds heap, as the app's UI would |
| `LAB_SEEK_STORM_S` | Seeks every that many seconds |
| `LAB_NEXT_TITLE_AT_S`, `LAB_REOPEN_AT_S` | Changes title, or leaves and comes back |

The result line has `freezes`, `longest_freeze` and `total_frozen`, counted until an error
reaches the screen (`failed_at`, -1 if none). `skipped` is film jumped over, and
`longest_picture_freeze` is the clock moving with the picture stopped.

A new failure belongs in the server's query string, with a scenario in `run.sh`.

Lessons from building it:

- Run `healthy` first. If it does not play clean, nothing else means anything.
- A fault has to fire on a connection that **crosses** its mark. Firing on any position past
  it faults the read of the index at the end of the file, during startup, and looks like a
  different bug.
- Do not `pkill -f` a pattern that appears in your own command line. It kills your shell.
- Do not edit `run.sh` while it runs. Bash reads it as it goes.
- **Compare totals as well as the worst case.** A fix made `slow-link` worse while every other
  scenario improved, and only the totals showed it.
- **Vary the file as well as the fault.** Causes 11 and 12 only appeared once the same faults
  ran with different amounts of damage and on a file with no index.
- **Rerun everything after a change to the tracker or the error policy.** A partial run looks
  fine right up to the scenario that was left out.

To find a cause, read Media3's sources rather than assuming. They are published as
`media3-exoplayer-1.11.0-sources.jar` on Google's Maven. Causes 3 and 6 were both documented
behaviour that the comments here contradicted.

## What is still unproven

- **That causes 1 and 2 are the ones on the TV.** They reproduce the symptom exactly, and
  nothing else tried did, but that is not the same as a log from the TV.
- **Real hosts.** Real-Debrid may treat the extra connection or the 15s timeout differently
  from the lab server. `read-ahead standing down: refused` would show the first.
- **HTTP/2 was never measured.** It was removed on reasoning and on NuvioTV's example.
- **The bitrate guard's 50Mbps** is a judgement, not a measurement.
- **Hardware decoders.** The lab decodes in software on x86. A freeze with the clock moving
  and the picture stopped has no detection here: `StallLabDeviceTest` reports it as
  `longest_picture_freeze`, it was seen once, for 1s (`corrupt@fast.mp4`), and a detector with
  no reproduction risks flushing static scenes for nothing.
- **Label parsing against live addons** holds for Torrentio: The Nun II read as 18 cached on
  Real-Debrid and 18 not cached. AIOStreams (ElfHosted) read as 58 cached on another debrid
  service, though its links lead to Real-Debrid, so they get no Real-Debrid preference.
- **Torrentio's real limits** are unknown. Sixteen at once once got through and once did
  not, depending on what else was using the connection.
- **Torrents on the TV.** Only the x86 emulator ran the engine. The TV's `armeabi-v7a` build
  of libtorrent, its memory on 2GB of RAM, and a swarm over its Wi-Fi are unmeasured.
- **A disk that fills mid-film.** The ranking prefers files that fit, and libtorrent's file
  error is turned into a failed source, but no run filled a disk.
- **Torrents in the release build.** R8 keeps `org.libtorrent4j` intact (checked in the
  dex), but the lab runs a debug build, and the release app was never given a torrent: no
  addon on the test account returns one.
- **A real link was never played from the lab.** Playing sends a scrobble `start` at once, with
  no minimum, so a test on a signed-in account writes to its history. That is the reason, not
  an oversight: the first real numbers have to come from a film someone actually wants to watch.
