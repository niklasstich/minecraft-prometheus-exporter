# T3 AE2 grid resolution and network statistics

The integration targets beta-3 AE2 `rv3-beta-1050-GTNH` and is constructed only
through `Ae2GridResolver.create` after the shared exact-version check. Optional
AE2FC and Thaumic Energistics device types are recognized by their complete class
names in the live host's hierarchy; neither addon is required on the classpath.

`Ae2GridResolver` is the server-thread API for T4/T5. `resolveSelections()` returns
enabled targets with a resolution and `aliasOf`; an available resolution's opaque
value is the current AE2 `IGrid`. Consumers may cast it on the server thread, but
must never retain it across world/exporter lifecycle boundaries or expose it to
HTTP. The resolver itself retains no live grid references. Numeric target-ID order
selects the owner after merges; aliases expose availability and alias information
but no duplicate network measurements. Re-resolution naturally tracks splits,
including loaded cross-dimension grid members, without enabling unanchored grids.
Disabled anchors still preserve their discovery identity. No storage-bus recursion
occurs: only `IGrid.getNodes()` supplies members.

Tile anchors use part `-1`; cable-bus anchors must explicitly choose `0..5` or
the internal part `6` (Forge UNKNOWN). A cable-bus tile is not silently substituted
for its internal part. Loaded discovery deduplicates grids, preserves existing
anchors, and chooses the smallest dimension/x/y/z/part anchor for new grids.
Configured unavailable targets remain in the shared registry for command listing.
The backend checks dimension, height and existing chunk before tile lookup.

`Ae2NetworkCollector` samples through `Sampler`; HTTP reads only immutable metric
families. T7 must register it with its own snapshot interval and call resolver
`clear()` on stop/unload. Errors affect only the failed selected target. Unavailable
targets emit availability zero; stale live rows disappear at the next snapshot.
Device counts deduplicate actual machine object identities, including parts, with
exclusive dual-interface classification before item-interface classification.
Unknown devices are counted as `other`. Average power is upstream's ten-tick
average AE/t including idle usage; idle AE/t is exposed separately.

Controller capacity is 32 times the geometric exterior-face count of the loaded
controller coordinate set. Coordinate adjacency removes internal faces without
looking up neighboring blocks, so unused and obstructed faces are included and
no neighbor chunks are loaded. Used channels sum distinct outgoing controller
connections, excluding controller-to-controller links. The per-face diagnostic
label is dimension:x:y:z:Forge-direction-index. Cable bottlenecks and P2P devices
do not expand geometric capacity or cause recursive link summation.

The beta-3 `PathingCalculation` explicitly seeds outgoing standard and creative
controller routes; `GridConnection.getUsedChannels()` returns its finalized routed
count. The exporter preserves those native routing units, including compressed
channel behavior, without claiming that they count attached devices. It reports
observed usage without clamping. Nonphysical routes, negative counts, any face
over 32, or usage over geometric capacity invalidate capacity comparison and omit
available-channel estimates. No-controller/conflict/booting modes also invalidate
comparison. Channels-disabled mode omits channel capacity/usage/available gauges.
Creative controllers expose state and observed usage but omit finite capacity and
available gauges because their unlimited policy is not standard physical capacity.

Metric names start with `mc_ae2_network_`: availability, alias/info, device kinds,
power average/idle, controller state, channels enabled, exterior faces, capacity
validity, capacity/used/available channels and per-face used diagnostics. Coordinates
and configured name appear in info rows; runtime UUIDs never become identity labels.

Unit tests cover merges/splits, disable/unavailability, subnet discovery, preserved
multipart anchors, exterior/internal and cross-dimension controller geometry,
optional addon classification, invalid modes/over-capacity routes, reader error
isolation and stale rows. A live beta-3 fixture is still needed to verify multipart
hosts, actual controller boot/conflict transitions, cable bottlenecks, P2P and
compression routing, cross-dimension links, optional-mod startup and tick overhead.
The pure tests validate the policy but cannot establish live routing comparability.

Validation: `./gradlew spotlessApply build --no-configuration-cache` passes with
42 tests, including six AE2 tests and four powerfail tests, formatting, Checkstyle
and the reobfuscated artifact. No live beta-3 server was available for this task.
