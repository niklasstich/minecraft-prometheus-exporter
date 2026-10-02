# T4 AE2 CPU instrumentation

The CPU collector consumes T3's `Ae2GridResolver.resolveSelections()`. Only the
canonical enabled target for each live grid publishes CPU rows. Sampling uses
`ICraftingGrid.getCpus()` on the server thread. HTTP reads immutable metric snapshots;
CPU binding tokens are never published. Unsupported/uninstrumented CPUs or reader
errors make that target unavailable and remove its old CPU rows.

Every CPU label set contains `target_id`, `cpu_name`, `cpu_dim_id`, `cpu_x`, `cpu_y`,
and `cpu_z`, including uniquely named CPUs. The anchor is the lexicographically
smallest dimension/x/y/z coordinate among its member tiles. Duplicate names remain
distinct. Idle CPUs expose busy=0 and their work counter; job gauges/resource info
disappear at completion or cancellation.

Metrics use the `mc_ae2_cpu_` prefix:

- `available{target_id}`: availability of CPU sampling for the selected network.
- `busy`: upstream busy state.
- `request_total`: original requested native amount, including merged requests.
- `request_remaining`: upstream remaining **primary output**, excluding coproducts.
- `progress_total`, `progress_remaining`: upstream in-game progress amounts.
- `progress_completed_total`: event counter of actual upstream progress decrements.
- `request_info`: resource type, ID, metadata, bounded SHA-256 NBT fingerprint and
  display name; its value is 1. Amounts never enter labels or the work counter identity.

## Pinned beta-3 source audit

The baseline is `.research/beta3/Applied-Energistics-2-Unofficial`, commit
`f9f49159899bdcdbf88a341e22b159cd58a61585`. The guarded late mixin shadows only
`CraftingCPUCluster.finalOutput` and hooks `updateElapsedTime` at successful return,
with `require=1` and unmapped AE selectors. No CraftUpdateListener or sampled job
delta is used. Both full and partial MODULATE insertions call this method with the
accepted amount; SIMULATE insertions do not. Completion clears progress after the
hook runs, so jobs finishing between snapshots still contribute. Cancellation and
`prepareStepCount` (including merged jobs) never manufacture completed work.

`finalOutput.getOriginalOutput()` preserves the original requested stack even when
tracking begins during a job. `findPrecise(original)` returns the primary output's
remaining amount. `addOutputs` rounds to whole pattern batches and retains all
coproducts separately. Consequently remaining primary output can initially exceed
the original requested amount (e.g. request 5, two batches of 4 => remaining 8).
The exporter preserves that upstream quantity rather than inventing an exact
unfulfilled-request amount. Returned primary output reserved as an ingredient is
not removed from final output until its surplus is delivered. Merges update the
original count and pending batch outputs independently.

Fake crafting calls `performFakeCrafting`, subtracts final pattern outputs and may
complete the job without calling `updateElapsedTime`. Those synthetic final outputs
do not increment the progress counter, matching the pinned upstream progress
semantics. Real intermediary returns still count. This counter measures accepted
progress amounts, not recipes, pattern executions or final requested amounts.

Native `IAEItemStack` amounts are item counts; `IAEFluidStack` amounts are mB;
ThE `AEEssentiaStack` amounts are native aspect units. ThE identity comes from its
serialized `AspectTag`, avoiding a hard dependency on the addon. AE2FC fluid packet
items remain item identities and packet counts: their stack count is not relabeled
as mB. NBT fingerprints exclude the AE amount and use sorted compound keys, with
list ordering preserved in the digest. Native fluid tags are included too.

## T7 lifecycle wiring

Construct `new Ae2CpuCollector(resolver, instrumentation, intervalTicks)` only with
the version-supported resolver, register it with Prometheus and the tick scheduler,
and use approximately 20 ticks for one-second snapshots. Call `collector.clear()`
before exporter/world stop and before closing the registry. No shared registration
or command files are changed by T4.

Instrumentation begins at the first successful sampling/bind, without reconstructing
previous work from an already-running job. Counter state is bounded by the shared
store's per-target identity limit. Disabled/unavailable/alias selections and missing
CPUs are pruned on each snapshot. Counters reset on CPU rename, replacement,
destruction/unload/rebind, target configuration changes, disable/enable and exporter
restart. Grid ownership changes also reset the target-labelled counter. Selections
remain persisted. Numeric conversion to double happens only at exposition.

Tests exercise injected event accounting across snapshots, duplicate names, idle
completion/cancellation behavior, new/merged jobs, three resource info types,
renames, destruction, disabled selections, reader errors and immutable publication.
They run without optional mods. Actual item/fluid/essentia stack decoding and live
mixin application require the T7 GTNH beta-3 server smoke test; no live server
validation has been performed in T4.

`./gradlew spotlessApply build --no-configuration-cache` passes: 44 tests,
including two new CPU tests, formatting, Checkstyle and the reobfuscated artifact.
