package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;

@com.github.bsideup.jabel.Desugar
public record LscSnapshot(BigInteger stored, BigInteger capacity, boolean formed, boolean wireless,
    long passiveLossPerTick, List<Integer> capacitors, InstrumentationStore.Totals totals) {

    public static final List<String> TIERS = Collections
        .unmodifiableList(java.util.Arrays.asList("IV", "LuV", "ZPM", "UV", "UHV", "None", "EV", "UEV", "UIV", "UMV"));

    public LscSnapshot {
        if (capacitors.size() != TIERS.size()) throw new IllegalArgumentException("Unexpected capacitor inventory");
        capacitors = Collections.unmodifiableList(new ArrayList<>(capacitors));
    }

    public double chargeRatio() {
        return capacity.signum() <= 0 ? 0
            : new BigDecimal(stored).divide(new BigDecimal(capacity), MathContext.DECIMAL128)
                .doubleValue();
    }
}
