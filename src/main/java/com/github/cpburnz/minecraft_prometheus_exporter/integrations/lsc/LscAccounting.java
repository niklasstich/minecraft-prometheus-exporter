package com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc;

import java.math.BigInteger;

/** A single running tick; failed wireless attempts never enter actual flow totals. */
public final class LscAccounting {

    private long input;
    private long output;
    private BigInteger wireless = BigInteger.ZERO;
    private BigInteger loss = BigInteger.ZERO;
    private boolean rebalanced;

    public void beforeRebalance(long input, long output) {
        this.input = input;
        this.output = output;
        rebalanced = true;
    }

    public void wireless(BigInteger transfer, boolean success) {
        if (success) wireless = wireless.add(transfer);
    }

    public void loss(BigInteger resultBeforeClamp, long passive) {
        loss = passive < 0 ? BigInteger.valueOf(-1)
            : resultBeforeClamp.add(BigInteger.valueOf(passive))
                .max(BigInteger.ZERO)
                .min(BigInteger.valueOf(passive));
    }

    public BigInteger[] finish(long finalInput, long finalOutput) {
        long wiredInput = rebalanced ? input : finalInput;
        long wiredOutput = rebalanced ? output : finalOutput;
        if (wiredInput < 0 || wiredOutput < 0 || loss.signum() < 0) return null;
        return new BigInteger[] { BigInteger.valueOf(wiredInput)
            .add(
                wireless.min(BigInteger.ZERO)
                    .negate()),
            BigInteger.valueOf(wiredOutput)
                .add(wireless.max(BigInteger.ZERO)),
            loss };
    }
}
