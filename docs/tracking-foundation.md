# T1 tracking foundation

## Runtime registration

Exporter startup registers the LSC, AE2 network/CPU and powerfail collectors when
their pinned integration versions are supported. Collectors remain registered with
no enabled selections, so enabling a target takes effect on the next refresh without
an exporter restart. Publication intervals are configured independently through
`lsc_interval_ticks`, `ae2_network_interval_ticks`, `ae2_cpu_interval_ticks` and
`powerfails_interval_ticks`, each defaulting to 20 ticks. Disabled targets emit no
measurements. Exporter stop detaches CPU hooks and clears LSC bindings before
stopping instrumentation; world unload also clears these runtime bindings.

## Selection storage

Selections live in `prometheus-exporter-targets.json` in the overworld save directory.
Schema version 1 stores immutable block/part anchors, names, enabled flags and
GTNHLib team UUIDs. LSC and AE2 allocation counters survive removal; IDs are never
reused. Discovery calls are idempotent and register new targets disabled. Availability
is runtime state and is not written into the configuration. Listing the registry
includes unavailable selections without consulting worlds or loading chunks.

Files are saved through a forced temporary file and an atomic replacement in the
same directory. There is no non-atomic fallback. A failed save leaves the registry
unchanged. Invalid or unsupported files are retained and logged, and target tracking
stays disabled for that server session. Repair the file and restart the server to
retry. Existing exporter collectors still run. Limits are 4096 selections, a 4 MiB
JSON file and 256 characters per target name.

`PrometheusExporterMod.targets()` and `instrumentation()` provide the shared APIs.
Both are null outside a world or when its selection file could not be loaded.
Registry reads and mutations and instrumentation operations require the owning
server thread. Commands use `discover`, `discoverTeam`, `configure` and `remove`.
Collectors copy these immutable DTOs into their published snapshots; HTTP handlers
must never call the registry or adapters. Concrete discovery and commands belong
to T2–T6, so this foundation alone adds no metrics or selection commands.

`IntegrationAdapter` defines loaded discovery and per-target resolution, including
disabled, unavailable, unsupported and error states. Implementations must guard
loaded chunks before tile lookup, catch errors per target, and construct optional
mod classes only when `ModCompat.isSupported()` passes. Block side convention is
`-1` for a tile, `0..5` for Forge directions, and `6` for the internal cable-bus part.
Names are configuration names; changing live display names belongs in info metrics.
Powerfail IDs are `powerfails-<GTNHLib UUID>` and never refer to ServerUtilities teams.

Instrumentation must explicitly bind an enabled target and stable local identity
to its current live object. Event additions require that same object identity.
BigInteger totals preserve EU precision; negative inputs reject the entire event.
There is a limit of 4096 instrumented identities per selected target. Use `unbind`
when a target/CPU unloads or is destroyed and `prune` when refreshing selections.
Changing settings, disable/enable, rebind, exporter restart and world stop reset
runtime accounting. Any server dimension unload conservatively clears all bindings.
Selections survive exporter restarts. Future adapters must call their `clear()`
method on exporter stop and world unload; persistence must never retain them.

Mixins are enabled through UniMixins. The early config is intentionally empty.
`LateMixinLoader` checks mod presence and the exact beta-3 version before returning
optional hooks. Each feature owns a separate manifest and Java package:

| Task | Manifest under `src/main/resources/mixins/prometheus_exporter/` | Package under the mod's `mixins` package |
| --- | --- | --- |
| T2 | `lsc.list` | `lsc` |
| T4 | `ae2.list` | `ae2` |
| T6 | `powerfails.list` | `powerfails` |

Use one relative class name per line, such as `lsc.TransferMixin`. Blank lines and
`#` comments are ignored. Keep optional hooks out of the early config. Powerfail
hooks require both the pinned GregTech and GTNHLib versions. Add a generated refmap
to the late config when introducing mapped Minecraft accesses; no refmap is needed
for this foundation's empty hook lists. Keep accessors inside their owning packages
and manifests, rather than widening shared access transformer entries. Add any
needed compile-only integration dependency using the release pins in the expansion
plan; existing GTNHLib/ServerUtilities dependency edits already match beta-3.

Validation: `./gradlew spotlessApply build --no-configuration-cache` passes,
including 25 tests (12 new foundation tests). Tests cover save/reload, worlds,
multipart identity, team UUIDs, invalid files, failed saves, thread guards,
enable/disable, unload/rebind, exporter restart, BigInteger accounting, bounded
instrumentation, missing optional mods and version policy. Live beta-3 startup,
integrated server lifecycle and missing-mod server smoke tests remain outstanding;
the unit tests do not substitute for those checks.
