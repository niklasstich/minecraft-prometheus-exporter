package com.github.cpburnz.minecraft_prometheus_exporter.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2GridResolver;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.IntegrationAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

/** Server-thread command service; no Minecraft classes are needed by syntax/persistence tests. */
public final class TrackingCommands {

    public static final String USAGE = "/prometheus <lsc|ae2|powerfails> <list|status|enable|disable|add> "
        + "[id | x y z [part]] [--dim dimension]";
    private final TargetRegistry registry;
    private final IntegrationAdapter<?> lsc;
    private final Ae2GridResolver ae2;
    private final PowerfailAdapter powerfails;

    public TrackingCommands(TargetRegistry registry, IntegrationAdapter<?> lsc, Ae2GridResolver ae2,
        PowerfailAdapter powerfails) {
        this.registry = registry;
        this.lsc = lsc;
        this.ae2 = ae2;
        this.powerfails = powerfails;
    }

    public List<String> execute(String[] args, Integer senderDimension) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException(USAGE);
        Target.Kind kind = kind(args[0]);
        List<String> words = new ArrayList<>(java.util.Arrays.asList(args));
        Integer dimension = senderDimension;
        int flag = words.indexOf("--dim");
        if (flag >= 0) {
            if (kind == Target.Kind.POWERFAILS || flag != words.size() - 2) throw new IllegalArgumentException(USAGE);
            dimension = integer(words.remove(flag + 1));
            words.remove(flag);
        }
        String action = words.get(1);
        IntegrationAdapter<?> adapter = adapter(kind);
        List<String> output = new ArrayList<>();
        switch (action) {
            case "enable", "disable" -> {
                if (words.size() != 3 || flag >= 0) throw new IllegalArgumentException(USAGE);
                String id = words.get(2);
                if (kind == Target.Kind.POWERFAILS && !id.startsWith("powerfails-"))
                    id = "powerfails-" + UUID.fromString(id);
                Target target = registry.get(id);
                if (target == null || target.kind() != kind)
                    throw new IllegalArgumentException("Unknown target " + id + "; run " + args[0] + " list first.");
                if (action.equals("enable") && adapter == null) throw new IllegalArgumentException(
                    "Integration absent or unsupported for the pinned beta-3 version.");
                registry.configure(id, target.name(), action.equals("enable"));
                output.add(id + " " + (action.equals("enable") ? "enabled" : "disabled"));
            }
            case "add" -> {
                if (kind == Target.Kind.POWERFAILS || words.size() < 5
                    || words.size() > 6
                    || (kind == Target.Kind.LSC && words.size() != 5)) throw new IllegalArgumentException(USAGE);
                int dim = dimension(dimension);
                int part = words.size() == 6 ? integer(words.get(5)) : -1;
                Target.Anchor anchor = new Target.Anchor(
                    dim,
                    integer(words.get(2)),
                    integer(words.get(3)),
                    integer(words.get(4)),
                    part);
                Target target = registry.discover(kind, anchor, kind == Target.Kind.LSC ? "LSC" : "AE2 network");
                output.add(
                    target.id() + " registered; "
                        + (target.enabled() ? "enabled" : "disabled")
                        + (kind == Target.Kind.AE2 ? ". Cable-bus anchors require an explicit part (0..6)." : "."));
            }
            case "list", "status" -> {
                if (words.size() != 2) throw new IllegalArgumentException(USAGE);
                int dim = kind == Target.Kind.POWERFAILS ? 0 : dimension(dimension);
                if (kind == Target.Kind.POWERFAILS) output.add("Team provider: GTNHLib (UUID identity)");
                if (adapter == null) output.add("Integration absent or unsupported for the pinned beta-3 version.");
                if (action.equals("list") && adapter != null) {
                    if (kind == Target.Kind.POWERFAILS) {
                        for (PowerfailAdapter.TeamSummary team : powerfails.discoverTeams()) {
                            Target t = registry.discoverTeam(team.id(), team.name());
                            if (!t.name()
                                .equals(team.name())) registry.configure(t.id(), team.name(), t.enabled());
                        }
                    } else for (IntegrationAdapter.Discovered found : adapter.discoverLoaded(dim))
                        registry.discover(kind, found.anchor(), found.name());
                }
                java.util.Map<String, String> aliases = new java.util.HashMap<>();
                if (kind == Target.Kind.AE2 && ae2 != null)
                    for (Ae2GridResolver.ResolvedGrid grid : ae2.resolveSelections()) if (!grid.aliasOf()
                        .isEmpty())
                        aliases.put(
                            grid.target()
                                .id(),
                            grid.aliasOf());
                for (Target target : registry.list()) {
                    if (target.kind() != kind || (target.anchor() != null && target.anchor()
                        .dimension() != dim)) continue;
                    String location = target.teamId() != null ? "UUID=" + target.teamId()
                        : "dim=" + dim
                            + " xyz="
                            + target.anchor()
                                .x()
                            + ","
                            + target.anchor()
                                .y()
                            + ","
                            + target.anchor()
                                .z()
                            + " part="
                            + target.anchor()
                                .part();
                    String state = adapter == null ? "UNSUPPORTED"
                        : adapter.inspect(target)
                            .status()
                            .name();
                    output.add(
                        target.id() + " "
                            + target.name()
                            + " "
                            + location
                            + " "
                            + (target.enabled() ? "enabled" : "disabled")
                            + " availability="
                            + state
                            + (aliases.containsKey(target.id()) ? " alias-of=" + aliases.get(target.id()) : ""));
                }
                if (output.isEmpty()) output.add("No configured " + args[0] + " targets in dimension " + dim + ".");
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
        return output;
    }

    private IntegrationAdapter<?> adapter(Target.Kind kind) {
        return switch (kind) {
            case LSC -> lsc;
            case AE2 -> ae2;
            case POWERFAILS -> powerfails;
        };
    }

    public static Target.Kind kind(String word) {
        try {
            return Target.Kind.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(USAGE);
        }
    }

    private static int integer(String word) {
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected an integer: " + word);
        }
    }

    private static int dimension(Integer value) {
        if (value == null) throw new IllegalArgumentException("Console commands require --dim <dimension>.");
        return value;
    }
}
