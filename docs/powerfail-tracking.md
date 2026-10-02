# T6 GTNHLib team powerfails

`PowerfailAdapter.create(registry)` requires exact GregTech 5.09.54.132 and GTNHLib
0.11.46 versions. `discoverTeams()` returns immutable UUID/name summaries, including
offline teams. T5 can call `registry.discoverTeam(summary.id(), summary.name())` and
configure that persistent selection. `PowerfailCollector(registry, adapter, intervalTicks)`
is ready for T7 scheduler registration. Call `adapter.clear()` before registry closure.
The adapter retains no team, player, world or tile references.

Sampling reads `TeamManager.getTeamById(UUID)` and existing `gt.powerfails` data.
It never calls player-based tracker getters, creates teams, loads dimensions/chunks,
marks data dirty or changes records. Two late-mixin accessors read the in-memory
dimension/coordinate maps. No compressed persistence decoding or mapped Minecraft
method access is needed. `powerfails.list` owns both hooks.

Every successful refresh replaces selected-team rows. Clear-all, clear-dimension and
individual record removal disappear on the next successful refresh even when the
machine remains unrepaired. Repeated failures update one coordinate row and its latest
timestamp. Counts count distinct records, not occurrences.
`mc_team_powerfail_last_occurrence_timestamp_seconds` uses milliseconds / 1000.0 with
team UUID, dimension, x/y/z and numeric MetaTileEntity ID labels. Time is a value.
`mc_powerfail_machine_info` supplies display names separately; unknown machine IDs keep
their numeric identity and upstream `<error>` name. The tracker has no shutdown reason.
`mc_team_powerfails_pending` includes zero for available selected teams with no records.

`mc_team_powerfails_available=0` distinguishes deleted teams, missing tracker data and
missing access hooks from an empty tracker. Those states omit count and record rows.
Disabled selections omit all metrics. If upstream notifications are disabled, existing
records remain readable; the exporter cannot observe failures upstream no longer records.
One failing team cannot prevent healthy teams from refreshing. HTTP collection reads
immutable snapshots only. Historic Prometheus queries can still show cleared records.

Selections stay attached to their native GTNHLib UUID. Rename changes only
`mc_powerfail_team_info`. Membership transfers follow GregTech's actual data transfers;
only selected teams are collected. Merge/deletion **does not migrate selections
automatically**. A consumed team's selection stays configured and unavailable; select
the surviving UUID explicitly. An already selected surviving team reports merged data.
No ServerUtilities mapping applies.

Tests cover immutable snapshots, offline/unloaded-dimension DTOs, repeat timestamp
updates, fractional seconds, type deduplication, clearing rows, rename, membership
transfer, deletion, disabled selections and per-team missing-data isolation using an
injectable backend. These tests do not execute a native GTNHLib merge or apply mixins.
Live beta-3 startup, accessor application, notification toggling, acknowledgement/removal,
offline-owner loading and membership/merge behavior remain required in T7's server smoke test.

Validation: the shared `./gradlew spotlessApply build --no-configuration-cache` run
passes all four powerfail tests, Checkstyle and the reobfuscated artifact (42 tests
total at the T3/T6 checkpoint). No live server fixture was exercised.
