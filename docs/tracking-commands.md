# T5 tracking commands

Commands retain `/prometheus start|stop|restart`, the `/prom` alias and the configured
admin permission level. New targets are registered disabled. Selections are saved in
the world's `prometheus-exporter-targets.json`; disabling preserves IDs and anchors.

```
/prometheus lsc list
/prometheus lsc enable lsc-1
/prometheus lsc disable lsc-1
/prometheus lsc status
/prometheus lsc add 100 64 200

/prometheus ae2 list
/prometheus ae2 add 100 64 200 6
/prometheus ae2 enable ae2-1
/prometheus ae2 disable ae2-1
/prometheus ae2 status

/prometheus powerfails list
/prometheus powerfails enable <team-uuid>
/prometheus powerfails disable powerfails-<team-uuid>
/prometheus powerfails status
```

Block commands default to the player's current dimension. Console block commands
require a trailing `--dim <dimension>`, for example
`/prometheus ae2 list --dim -1` or
`/prometheus lsc add 100 64 200 --dim 0`. Enable/disable use globally unique target
IDs and require no dimension. Powerfails are dimension-independent and use native
GTNHLib team UUIDs, including offline teams; ServerUtilities teams are separate.

`list` discovers loaded controllers/grids once and persists new selections. It also
shows configured unloaded targets in that dimension. `status` reports existing
selections without discovery. Both show coordinates, multipart side, enabled state
and current availability; disabled targets can be available. Availability probes do
not enable tracking or bind/reset instrumentation. AE2 enabled anchors that resolve
to the same grid report `alias-of=<owner-id>`; the lowest numeric enabled ID owns it.
Separate storage-bus subnets retain separate selections. Discovery preserves chosen
anchors and selects new anchors deterministically, without loading chunks.

Explicit `add` persists an anchor even while unloaded. AE2 tile anchors default to
part `-1`; cable-bus anchors require an explicit Forge side `0..5` or internal part
`6`. Distinct parts at one position may represent separate grids. LSC accepts tile
anchors only. Repeated add/list/enable/disable commands preserve IDs and settings.

Absent or unsupported integrations report that state; saved selections remain
visible and can be disabled. Enabling requires the pinned supported integration.
Teams deleted or consumed by a merge stay configured and unavailable; select the
surviving team explicitly. No command changes upstream powerfail records.

T7 still needs to register the collectors and complete server smoke tests. T5's
commands configure selections; they alone do not publish the new metric families.

Validation: `./gradlew spotlessApply build --no-configuration-cache` passes all 49
tests, Checkstyle, formatting and reobfuscation. Five new command tests cover
syntax, console dimensions, stable IDs across reload, idempotent configuration,
missing integrations, multipart/subnet discovery, aliases, offline/deleted teams,
the configured permission level, the existing alias and tab completion. Actual
Forge command dispatch and beta-3 loaded-world discovery still need the T7 smoke test.
