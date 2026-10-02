package com.github.cpburnz.minecraft_prometheus_exporter.tracking;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * World-scoped, server-thread-owned registry. Failed reads never overwrite the original file.
 * Mutations become visible only after a successful atomic save.
 */
public final class TargetRegistry {

    public static final int MAX_TARGETS = 4096;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Thread owner = Thread.currentThread();
    private final Path file;
    private Map<String, Target> targets = new LinkedHashMap<>();
    private Map<Target.Kind, Long> next = new EnumMap<>(Target.Kind.class);
    private boolean closed;

    private TargetRegistry(Path file) {
        this.file = file;
        next.put(Target.Kind.LSC, 1L);
        next.put(Target.Kind.AE2, 1L);
    }

    public static TargetRegistry open(Path worldDirectory) throws IOException {
        TargetRegistry registry = new TargetRegistry(worldDirectory.resolve("prometheus-exporter-targets.json"));
        if (!Files.exists(registry.file)) return registry;
        try {
            if (Files.size(registry.file) > MAX_FILE_BYTES) throw new IllegalArgumentException("File too large");
            JsonObject root = new JsonParser()
                .parse(new String(Files.readAllBytes(registry.file), StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (integer(root, "version") != 1) throw new IllegalArgumentException("Unsupported configuration version");
            JsonObject counters = root.getAsJsonObject("nextIds");
            for (Target.Kind kind : registry.next.keySet()) {
                long value = integer(counters, kind.name());
                if (value < 1) throw new IllegalArgumentException("Invalid allocation counter");
                registry.next.put(kind, value);
            }
            for (JsonElement element : root.getAsJsonArray("targets")) {
                JsonObject row = element.getAsJsonObject();
                Target.Kind kind = Target.Kind.valueOf(string(row, "kind"));
                Target.Anchor anchor = null;
                UUID team = null;
                if (kind == Target.Kind.POWERFAILS) team = UUID.fromString(string(row, "teamId"));
                else {
                    JsonObject a = row.getAsJsonObject("anchor");
                    anchor = new Target.Anchor(
                        Math.toIntExact(integer(a, "dimension")),
                        Math.toIntExact(integer(a, "x")),
                        Math.toIntExact(integer(a, "y")),
                        Math.toIntExact(integer(a, "z")),
                        Math.toIntExact(integer(a, "part")));
                }
                Target target = new Target(
                    string(row, "id"),
                    kind,
                    anchor,
                    team,
                    string(row, "name"),
                    bool(row, "enabled"));
                if (registry.find(kind, anchor, team) != null || registry.targets.put(target.id(), target) != null)
                    throw new IllegalArgumentException("Duplicate target");
                if (kind != Target.Kind.POWERFAILS && Long.parseLong(
                    target.id()
                        .substring(
                            target.id()
                                .indexOf('-') + 1))
                    >= registry.next.get(kind))
                    throw new IllegalArgumentException("Allocation counter would reuse an ID");
                if (registry.targets.size() > MAX_TARGETS) throw new IllegalArgumentException("Too many targets");
            }
            return registry;
        } catch (RuntimeException e) {
            throw new IOException(
                "Invalid target configuration " + registry.file + "; file retained, tracking disabled",
                e);
        }
    }

    private static long integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isNumber())
            throw new IllegalArgumentException("Expected integer for " + key);
        return value.getAsBigDecimal()
            .longValueExact();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString())
            throw new IllegalArgumentException("Expected string for " + key);
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isBoolean())
            throw new IllegalArgumentException("Expected boolean for " + key);
        return value.getAsBoolean();
    }

    public void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Tracking requires the server thread");
        if (closed) throw new IllegalStateException("Registry is closed");
    }

    public List<Target> list() {
        checkThread();
        return Collections.unmodifiableList(new ArrayList<>(targets.values()));
    }

    public Target get(String id) {
        checkThread();
        return targets.get(id);
    }

    private Target find(Target.Kind kind, Target.Anchor anchor, UUID team) {
        for (Target target : targets.values()) {
            if (target.kind() == kind && java.util.Objects.equals(anchor, target.anchor())
                && java.util.Objects.equals(team, target.teamId())) return target;
        }
        return null;
    }

    /** Discovery is idempotent, preserves settings, and registers new anchors disabled. */
    public Target discover(Target.Kind kind, Target.Anchor anchor, String name) throws IOException {
        checkThread();
        if (kind == Target.Kind.POWERFAILS) throw new IllegalArgumentException("Use discoverTeam");
        Target existing = find(kind, anchor, null);
        if (existing != null) return existing;
        long number = next.get(kind);
        Map<Target.Kind, Long> counters = new EnumMap<>(next);
        counters.put(kind, Math.addExact(number, 1));
        return add(
            new Target(
                kind.name()
                    .toLowerCase(java.util.Locale.ROOT) + "-"
                    + number,
                kind,
                anchor,
                null,
                name,
                false),
            counters);
    }

    public Target discoverTeam(UUID teamId, String name) throws IOException {
        checkThread();
        Target existing = find(Target.Kind.POWERFAILS, null, teamId);
        if (existing != null) return existing;
        return add(new Target("powerfails-" + teamId, Target.Kind.POWERFAILS, null, teamId, name, false), next);
    }

    private Target add(Target target, Map<Target.Kind, Long> counters) throws IOException {
        if (targets.size() >= MAX_TARGETS) throw new IllegalStateException("Target limit reached");
        Map<String, Target> updated = new LinkedHashMap<>(targets);
        updated.put(target.id(), target);
        commit(updated, counters);
        return target;
    }

    public void configure(String id, String name, boolean enabled) throws IOException {
        checkThread();
        Target target = targets.get(id);
        if (target == null) throw new IllegalArgumentException("Unknown target " + id);
        Target replacement = target.withSettings(name, enabled);
        if (replacement.equals(target)) return;
        Map<String, Target> updated = new LinkedHashMap<>(targets);
        updated.put(id, replacement);
        commit(updated, next);
    }

    public void remove(String id) throws IOException {
        checkThread();
        if (!targets.containsKey(id)) return;
        Map<String, Target> updated = new LinkedHashMap<>(targets);
        updated.remove(id);
        commit(updated, next);
    }

    private void commit(Map<String, Target> updated, Map<Target.Kind, Long> counters) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject ids = new JsonObject();
        counters.forEach((kind, value) -> ids.addProperty(kind.name(), value));
        root.add("nextIds", ids);
        JsonArray rows = new JsonArray();
        for (Target target : updated.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", target.id());
            row.addProperty(
                "kind",
                target.kind()
                    .name());
            row.addProperty("name", target.name());
            row.addProperty("enabled", target.enabled());
            if (target.teamId() != null) row.addProperty(
                "teamId",
                target.teamId()
                    .toString());
            else {
                Target.Anchor a = target.anchor();
                JsonObject anchor = new JsonObject();
                anchor.addProperty("dimension", a.dimension());
                anchor.addProperty("x", a.x());
                anchor.addProperty("y", a.y());
                anchor.addProperty("z", a.z());
                anchor.addProperty("part", a.part());
                row.add("anchor", anchor);
            }
            rows.add(row);
        }
        root.add("targets", rows);
        byte[] bytes = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(root)
            .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Target configuration exceeds file size limit");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "prometheus-targets-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // No non-atomic fallback: a failed save must preserve the previous valid configuration.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            targets = updated;
            next = new EnumMap<>(counters);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void close() {
        checkThread();
        targets.clear();
        closed = true;
    }
}
