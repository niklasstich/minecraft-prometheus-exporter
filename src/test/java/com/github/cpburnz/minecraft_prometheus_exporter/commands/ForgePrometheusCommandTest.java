package com.github.cpburnz.minecraft_prometheus_exporter.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.cpburnz.minecraft_prometheus_exporter.ExporterConfig;

class ForgePrometheusCommandTest {

    @Test
    void existingPermissionAliasesAndCompletionRemainAvailable() {
        ForgePrometheusCommand command = new ForgePrometheusCommand();
        int previous = ExporterConfig.collector.command_permission_level;
        try {
            ExporterConfig.collector.command_permission_level = 4;
            assertEquals(4, command.getRequiredPermissionLevel());
            assertTrue(
                command.getCommandAliases()
                    .contains("prom"));
            List<String> roots = command.addTabCompletionOptions(null, new String[] { "" });
            assertTrue(
                roots.containsAll(java.util.Arrays.asList("start", "stop", "restart", "lsc", "ae2", "powerfails")));
            assertTrue(
                command.addTabCompletionOptions(null, new String[] { "ae2", "" })
                    .contains("add"));
            assertEquals(
                java.util.Collections.singletonList("disable"),
                command.addTabCompletionOptions(null, new String[] { "powerfails", "dis" }));
        } finally {
            ExporterConfig.collector.command_permission_level = previous;
        }
    }
}
