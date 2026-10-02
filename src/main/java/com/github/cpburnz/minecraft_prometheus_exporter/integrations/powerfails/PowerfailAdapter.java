package com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.github.cpburnz.minecraft_prometheus_exporter.ModCompat;
import com.github.cpburnz.minecraft_prometheus_exporter.mixins.powerfails.DimensionInfoAccess;
import com.github.cpburnz.minecraft_prometheus_exporter.mixins.powerfails.PowerfailDataAccess;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;
import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;

import gregtech.common.data.GTPowerfailTracker;

/** Read-only, server-thread team data access; never resolves players or loads worlds. */
public final class PowerfailAdapter implements IntegrationAdapter<PowerfailSnapshot> {

    @com.github.bsideup.jabel.Desugar
    public record TeamSummary(UUID id, String name) {}

    public interface Backend {

        List<TeamSummary> teams();

        PowerfailSnapshot read(UUID team);
    }

    private final TargetRegistry registry;
    private final Backend backend;

    public PowerfailAdapter(TargetRegistry registry, Backend backend) {
        this.registry = registry;
        this.backend = backend;
    }

    public static PowerfailAdapter create(TargetRegistry registry) {
        return ModCompat.GregTech.isSupported() && ModCompat.GTNHLib.isSupported()
            ? new PowerfailAdapter(registry, new MinecraftBackend())
            : null;
    }

    public List<TeamSummary> discoverTeams() {
        registry.checkThread();
        List<TeamSummary> teams = new ArrayList<>(backend.teams());
        teams.sort(
            Comparator.comparing(
                t -> t.id()
                    .toString()));
        return Collections.unmodifiableList(teams);
    }

    public List<Discovered> discoverLoaded(int dimension) {
        registry.checkThread();
        return Collections.emptyList();
    }

    public Resolution<PowerfailSnapshot> resolve(Target target) {
        registry.checkThread();
        if (target.kind() != Target.Kind.POWERFAILS)
            return new Resolution<>(Status.UNSUPPORTED, null, "Expected GTNHLib team selection");
        Target current = registry.get(target.id());
        if (current == null || !current.equals(target) || !current.enabled())
            return new Resolution<>(Status.DISABLED, null, "Selection disabled or stale");
        return inspect(target);
    }

    @Override
    public Resolution<PowerfailSnapshot> inspect(Target target) {
        registry.checkThread();
        if (target.kind() != Target.Kind.POWERFAILS)
            return new Resolution<>(Status.UNSUPPORTED, null, "Expected GTNHLib team selection");
        try {
            PowerfailSnapshot data = backend.read(target.teamId());
            return data == null ? new Resolution<>(Status.UNAVAILABLE, null, "GTNHLib team missing or deleted")
                : new Resolution<>(Status.AVAILABLE, data, "");
        } catch (RuntimeException | LinkageError e) {
            return new Resolution<>(Status.ERROR, null, e.toString());
        }
    }

    public void clear() {
        registry.checkThread();
    }

    private static final class MinecraftBackend implements Backend {

        public List<TeamSummary> teams() {
            List<TeamSummary> result = new ArrayList<>();
            for (Team team : TeamManager.getTeamMap()
                .values()) result.add(new TeamSummary(team.getTeamId(), team.getTeamName()));
            return result;
        }

        public PowerfailSnapshot read(UUID id) {
            Team team = TeamManager.getTeamById(id);
            if (team == null) return null;
            Object data = team.getData(GTPowerfailTracker.DATA_NAME);
            // No registered tracker data means unsupported, never a misleading zero.
            if (!(data instanceof PowerfailDataAccess))
                throw new IllegalStateException("Powerfail data or read access unavailable");
            List<PowerfailSnapshot.Row> rows = new ArrayList<>();
            for (Object dimension : ((PowerfailDataAccess) data).prometheus$worlds()
                .values()) {
                for (Object value : ((DimensionInfoAccess) dimension).prometheus$records()
                    .values()) {
                    GTPowerfailTracker.Powerfail p = (GTPowerfailTracker.Powerfail) value;
                    if (p.lastOccurrence == null) throw new IllegalStateException("Powerfail timestamp missing");
                    rows.add(
                        new PowerfailSnapshot.Row(
                            p.dim,
                            p.x,
                            p.y,
                            p.z,
                            p.mteId,
                            p.getMTEName(),
                            p.lastOccurrence.getTime()));
                }
            }
            rows.sort(
                Comparator.comparingInt(PowerfailSnapshot.Row::dimension)
                    .thenComparingInt(PowerfailSnapshot.Row::x)
                    .thenComparingInt(PowerfailSnapshot.Row::y)
                    .thenComparingInt(PowerfailSnapshot.Row::z));
            return new PowerfailSnapshot(team.getTeamName(), rows);
        }
    }
}
