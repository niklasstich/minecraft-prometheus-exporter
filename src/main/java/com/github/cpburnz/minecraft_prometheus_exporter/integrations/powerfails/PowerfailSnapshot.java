package com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@com.github.bsideup.jabel.Desugar
public record PowerfailSnapshot(String teamName, List<Row> rows) {

    public PowerfailSnapshot {
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @com.github.bsideup.jabel.Desugar
    public record Row(int dimension, int x, int y, int z, int machineId, String machineName, long latestMillis) {}
}
