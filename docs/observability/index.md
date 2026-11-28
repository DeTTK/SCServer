---
title: "Native runtime diagnostics"
---

# Native runtime diagnostics

Dashboard: https://vividshark1612.grafana.net/d/bscp-native-runtime/bscpcraft-c2b7-native-runtime-diagnostics

Import `bscp-native-runtime.json` through Grafana → Dashboards → New → Import.
The supplied deployment uses Faro application `73` and Loki datasource UID
`grafanacloud-logs`; change these two values for another Grafana organization.
No private Grafana token belongs in the launcher. The public collector URL is
embedded in `launcher/telemetry-public.json` and can be overridden in the portable
profile's `config/telemetry.json`.

## Reading a failure

1. Select an environment and time range. In the exceptions panel, find the error
   and its `context_run_group`. Paste that value into the run filter. A local
   server and its client share the group; individual `context_run_id` values
   identify their separate process sessions.
2. Inspect FPS, recent maximum frame duration and p95 histogram upper bound,
   native/world TPS, skipped simulation slots, GC time and CPU usage before the
   event. CPU 100% means one logical CPU, so multithreaded processes can exceed it.
3. Compare JVM heap utilization with OS RSS/private committed memory and system
   available RAM. Java heap exhaustion does not imply physical RAM exhaustion.
4. Open the run summary for the source commit, branch, enabled mods/versions,
   configured heaps, exit status and last available measurement. Measurement
   errors are recorded separately rather than replaced with invented values.
5. For native JVM crashes, the fatal exception includes the hs_err header,
   problematic native frame and available native/Java stack frames. Full original
   hs_err and rolling JFR/thread captures remain in the local session for manual
   bug reports; automatic delivery deliberately excludes raw register/environment
   dumps and command lines from the native-crash excerpt.

## Timing and delivery

The supervisor samples every five seconds and sends performance batches roughly
every thirty seconds while the owned processes run. It retains a rolling minute
in each session's `performance-window.jsonl`. Full bounded session logs are queued
on exit; upload completion is asynchronous and can take longer than UI shutdown.
The launcher retries persisted events on its next start. An idle background host
is not required to keep the queue.

Faro/Loki indexes these records at receipt time. `context_sample_time` and the
payload timestamp retain the actual event time; `context_delivery_lag_ms` makes
offline delivery visible. The live charts exclude measurements delayed by more
than a minute so old samples cannot masquerade as current performance. Review
delayed runs in raw logs and run summaries. The chart's one-minute aggregation
preserves the sampled minimum FPS/TPS and maximum latency/memory.

Counters are differenced only between samples of the same role and PID. Recent
frame latency uses a ten-second window; its p95 is a histogram upper bound, not an
exact percentile. `diagnosticCaptureMs` reports the duration of the last JFR/thread
capture so profiling overhead can be distinguished from the original stall.
Automatic heavy JFR/thread captures are limited to one per five minutes; lightweight
measurements and incident summaries continue at their normal cadence. This avoids
repeated capture pauses feeding back into another heavy capture every thirty seconds.

The on-disk outbox is bounded to 256 events (64 KiB each). Crash/exit/summary
records take priority over verbose logs when it fills. Only acknowledged events
are removed. This is bounded diagnostic collection, not a guarantee that every
stdout byte or every sample survives an arbitrarily long offline session.
Launcher panics persist separately from the queue lock and are imported once on
the next launch. Tokens, invitations and configured secrets are redacted.

## Verification on 2026-11-24

An isolated copy of the world ran with a real native client and server: 249 NPCs,
1,296 movement/goal commands and 4,096 block edits. The ordinary workload did not
crash. Simulation TPS reached 8.83 during block pressure. Separate, explicitly
controlled QA injections produced 250 ms render stalls, an actual Java heap
OutOfMemoryError, and a native EXCEPTION_ACCESS_VIOLATION. The QA fault driver is
not part of the distributed bridge or game build.

Both exception stacks were observed in the authenticated Grafana UI. Process
RSS reached 5,546 MiB, sampled recent FPS fell to 3.4, and a recent frame reached
761 ms during controlled fault tests. These figures describe this machine and
test, not a player capacity claim. A separate real panic/HTTP-503/restart test
verified persistence, secret redaction and successful retry to Grafana. Final
native restart verified OS memory counters and empty delivery queues.

Private reproducible scripts and detailed evidence are retained locally under
`results/maintenance/grafana-stress-20261124`; test profiles and fault tools are
archived separately rather than shipped with the launcher.
