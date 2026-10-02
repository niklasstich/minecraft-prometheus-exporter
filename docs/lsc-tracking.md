# T2 LSC adapter and accounting

`integrations/lsc/LscAdapter` resolves persisted LSC selections and supplies loaded-only,
deterministically sorted discovery for T5. `create(registry, instrumentation)` returns
null unless GregTech is exactly `5.09.54.132`. GregTech and the minimum inherited API
dependencies are compile-only, pinned to GTNH 2.9.0-beta-3; no mod is bundled or installed.
The Minecraft backend is isolated from the adapter's injectable test backend.

Resolution checks loaded dimensions/chunks, then the GT base tile's current MetaTileEntity.
It requires the late mixin's `LscAccess` interface, binds accounting to that exact live
MetaTileEntity, and returns immutable full-precision values. Disabled, unloaded, replaced,
invalid or erroneous targets do not retain their old live measurements. Capacitor metadata
order is IV, LuV, ZPM, UV, UHV, None, EV, UEV, UIV, UMV. `None` represents empty cells.
Unformed structures expose no capacity, charge ratio, passive-loss gauge or capacitor
inventory metrics, because upstream can retain values from an earlier successful check.

`LscCollector` publishes immutable snapshots through `Sampler`. HTTP collection touches
neither registry nor world state. Enabled unavailable targets expose `mc_lsc_available=0`
and no other rows. Disabled selections expose no rows. Available targets expose formation,
wireless state, stored EU, formed capacity/ratio/inventory, nominal passive EU per running
tick, and input/output/actual passive-loss EU counters. Counters use only `target_id`;
coordinates and the configured name belong to `mc_lsc_info`. BigInteger values convert
to double only for exposition; Prometheus cannot preserve integer precision at huge EU
values. The ratio is calculated before that conversion with DECIMAL128 precision.

## Tick instrumentation audit

The pinned `onRunningTick` resets I/O, processes six hatch groups, optionally calls
`rebalance`, subtracts maintenance-adjusted passive loss and clamps stored EU to zero.
Inactive machines do not call it, so sampling does not invent flow between events.
The hook commits one event only on successful return, using the bound live object's
identity. All injection sites require a match and use the version-guarded `lsc.list`.
No mapped Minecraft member is an injection selector, so these hooks need no refmap.

Upstream `rebalance` increments its long I/O fields **before** the wireless manager
accepts the transfer. It also narrows BigInteger transfers, potentially wrapping them.
The hook captures wired I/O immediately before rebalance, observes the manager's actual
return value, and adds only successful wireless transfers using their original
BigInteger amounts. Failed attempts add zero; successful overflow-sized transfers retain
their exact amount. This deliberately differs from misleading upstream attempted-flow
statistics. Gameplay is unchanged. Negative wired totals reject the entire event rather
than becoming positive counter increments; upstream long arithmetic can still overflow
to a nonnegative value, which these final wired fields cannot diagnose.

Actual passive loss is capped by the energy available at the upstream loss/clamp site.
For example, 2 EU available with a nominal 10 EU loss increments the loss counter by 2.
The nominal gauge remains 10. These measurements do not infer transfers from stored-energy
deltas. The loss calculation observes the one upstream BigInteger addition for the loss
and clamp, after any successful wireless rebalance.

## T5/T7 wiring

T5 can call `discoverLoaded(dimension)` and persist returned anchors with the shared
registry. Construct one adapter per active exporter/world and add a `LscCollector` to
the scheduler/Prometheus registry in T7. Its interval is independent of tick accounting;
20 ticks is suitable for the proposed roughly one-second snapshots. Accounting begins
at the first successful resolution/bind, not retroactively at selection time.

Call `adapter.clear()` on exporter stop and world unload **before** closing the target
registry. T1 already clears instrumentation on dimension unload and stop; bindings reset
on the next resolution. Runtime counters reset on disable/enable, settings changes,
unload/rebind, controller replacement and exporter/world restart. Persistent selections
remain. The adapter holds no worlds or tiles; the shared store owns bounded live bindings.
Commands, registration, recording rules and end-to-end server validation remain T5/T7.

## Validation

Tests cover huge capacities and ratios, mixed metadata tiers, simultaneous flows,
failed and overflow-sized successful wireless transfers, depleted-store loss,
negative wired fields, inactive sampling, unload/replacement, disabled targets,
unformed structures, immutable snapshots, per-target error isolation and stale metric removal. The tests use the
injectable backend without requiring GregTech on their runtime classpath.
Live mixin application and GTNH server behavior still require the T7 beta-3 smoke test.

`./gradlew spotlessApply build --no-configuration-cache` passes, including seven new
LSC tests (32 total), Checkstyle, formatting checks and the reobfuscated artifact.
