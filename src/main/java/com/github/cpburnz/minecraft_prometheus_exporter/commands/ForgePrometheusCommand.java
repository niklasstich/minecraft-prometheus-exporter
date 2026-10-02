package com.github.cpburnz.minecraft_prometheus_exporter.commands;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import com.github.cpburnz.minecraft_prometheus_exporter.ExporterConfig;
import com.github.cpburnz.minecraft_prometheus_exporter.PrometheusExporterMod;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2GridResolver;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.Target;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;

/**
 * The ForgePrometheusCommand class defines the "prometheus" command for Forge.
 */
public class ForgePrometheusCommand extends CommandBase implements PrometheusCommand {

    /**
     * Constructs the instance.
     */
    public ForgePrometheusCommand() {}

    /**
     * Get the available options for tab completion given the arguments.
     *
     * @param sender The sender.
     * @param args   The arguments.
     * @return The completion options.
     */
    @Override
    public @Nullable List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "start", "stop", "restart", "lsc", "ae2", "powerfails");
        }
        Target.Kind kind;
        try {
            kind = TrackingCommands.kind(args[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (args.length == 2) return kind == Target.Kind.POWERFAILS
            ? getListOfStringsMatchingLastWord(args, "list", "status", "enable", "disable")
            : getListOfStringsMatchingLastWord(args, "list", "status", "enable", "disable", "add");
        TargetRegistry registry = PrometheusExporterMod.INSTANCE.targets();
        if (args.length == 3 && registry != null && (args[1].equals("enable") || args[1].equals("disable"))) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            for (Target target : registry.list()) if (target.kind() == kind) ids.add(target.id());
            return getListOfStringsMatchingLastWord(args, ids.toArray(new String[0]));
        }
        if (kind != Target.Kind.POWERFAILS && args.length >= 3) return getListOfStringsMatchingLastWord(args, "--dim");
        return null;
    }

    /**
     * Restart the prometheus exporter.
     *
     * @param sender The sender.
     */
    private void execRestart(ICommandSender sender) {
        this.execStop(sender);
        this.execStart(sender);
    }

    /**
     * Start the prometheus exporter.
     *
     * @param sender The sender.
     */
    private void execStart(ICommandSender sender) {
        PrometheusExporterMod mod = PrometheusExporterMod.INSTANCE;
        if (!mod.isExporterRunning()) {
            try {
                mod.startExporter();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.sendAdminMessage(sender, MSG_START_SUCCESS);
        } else {
            this.sendChatMessage(sender, MSG_START_INVALID);
        }
    }

    /**
     * Stop the prometheus exporter.
     *
     * @param sender The sender.
     */
    private void execStop(ICommandSender sender) {
        PrometheusExporterMod mod = PrometheusExporterMod.INSTANCE;
        if (mod.isExporterRunning()) {
            mod.stopExporter();
            this.sendAdminMessage(sender, MSG_STOP_SUCCESS);
        } else {
            this.sendChatMessage(sender, MSG_STOP_INVALID);
        }
    }

    /**
     * Get the command aliases.
     *
     * @return The aliases.
     */
    @Override
    public @Nullable List<String> getCommandAliases() {
        return ALIASES;
    }

    /**
     * Get the command name.
     *
     * @return The command name.
     */
    @Override
    public String getCommandName() {
        return NAME;
    }

    /**
     * Get the command usage.
     *
     * @param sender The sender.
     * @return The usage.
     */
    @Override
    public String getCommandUsage(ICommandSender sender) {
        return MSG_USAGE;
    }

    /**
     * Get the required permission level for this command.
     *
     * @return The required permission level.
     */
    @Override
    public int getRequiredPermissionLevel() {
        return ExporterConfig.collector.command_permission_level;
    }

    /**
     * Process the command.
     *
     * @param sender The sender.
     * @param args   The arguments.
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 0 && (args[0].equals("lsc") || args[0].equals("ae2") || args[0].equals("powerfails"))) {
            PrometheusExporterMod mod = PrometheusExporterMod.INSTANCE;
            TargetRegistry registry = mod.targets();
            if (registry == null) throw new CommandException("Target tracking unavailable; check server logs.");
            try {
                TrackingCommands commands = new TrackingCommands(
                    registry,
                    LscAdapter.create(registry, mod.instrumentation()),
                    Ae2GridResolver.create(registry),
                    PowerfailAdapter.create(registry));
                Integer dimension = sender instanceof EntityPlayerMP ? sender.getEntityWorld().provider.dimensionId
                    : null;
                for (String line : commands.execute(args, dimension))
                    sender.addChatMessage(new ChatComponentText(line));
            } catch (IOException e) {
                throw new CommandException("Could not save tracking selections: " + e.getMessage());
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new CommandException(e.getMessage());
            }
            return;
        }
        if (args.length != 1) {
            throw new WrongUsageException(MSG_USAGE);
        }

        // Parse subcommand.
        CommandArg cmd;
        try {
            cmd = CommandArg.from(args[0]);
        } catch (IllegalArgumentException e) {
            throw new WrongUsageException(MSG_USAGE);
        }

        switch (cmd) {
            case RESTART -> execRestart(sender);
            case START -> execStart(sender);
            case STOP -> execStop(sender);
        }
    }

    /**
     * Send the message to admins.
     *
     * @param sender    The sender.
     * @param msgFormat The message format.
     * @param msgParams The message parameters.
     */
    private void sendAdminMessage(ICommandSender sender, String msgFormat, Object... msgParams) {
        func_152373_a(sender, this, msgFormat, msgParams);
    }

    /**
     * Send the message to the user.
     *
     * @param sender    The sender.
     * @param msgFormat The message format.
     * @param msgParams The message parameters.
     */
    private void sendChatMessage(ICommandSender sender, String msgFormat, Object... msgParams) {
        sender.addChatMessage(new ChatComponentTranslation(msgFormat, msgParams));
    }
}
