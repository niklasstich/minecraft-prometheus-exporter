package com.github.cpburnz.minecraft_prometheus_exporter;

import java.io.IOException;
import java.net.BindException;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Chunks;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.CollectorScheduler;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Entities;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.PlayerStatistics;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Players;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Sampler;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.SelfMetrics;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Teams;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.Ticks;
import com.github.cpburnz.minecraft_prometheus_exporter.collectors.TileEntities;
import com.github.cpburnz.minecraft_prometheus_exporter.commands.ForgePrometheusCommand;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2CpuCollector;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2GridResolver;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.ae2.Ae2NetworkCollector;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.lsc.LscCollector;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailAdapter;
import com.github.cpburnz.minecraft_prometheus_exporter.integrations.powerfails.PowerfailCollector;
import com.github.cpburnz.minecraft_prometheus_exporter.prometheus_exporter.Tags;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.InstrumentationStore;
import com.github.cpburnz.minecraft_prometheus_exporter.tracking.TargetRegistry;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

/**
 * The PrometheusExporterMod class defines the mod.
 */
@Mod(
    modid = PrometheusExporterMod.MODID,
    version = Tags.VERSION,
    name = PrometheusExporterMod.NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class PrometheusExporterMod {

    public static final String NAME = "Prometheus Exporter";
    public static final String MODID = "prometheus_exporter";

    /**
     * The mod instance.
     */
    @Mod.Instance
    public static PrometheusExporterMod INSTANCE;

    /**
     * The logger to use.
     */
    public static final Logger LOG = LogManager.getLogger(MODID);

    private @Nullable HTTPServer http_server;

    /**
     * Drives interval-based refreshes of the sampled collectors on the server
     * thread.
     */
    private @Nullable CollectorScheduler scheduler;

    /**
     * Whether the exporter is running.
     */
    private boolean is_running;

    /**
     * The Minecraft server.
     */
    private MinecraftServer mc_server;
    private TargetRegistry targets;
    private InstrumentationStore instrumentation;
    private LscAdapter lscAdapter;
    private Ae2CpuCollector ae2CpuCollector;

    /** Server-thread APIs; null means no world or an invalid selection file. */
    public TargetRegistry targets() {
        return targets;
    }

    public InstrumentationStore instrumentation() {
        return instrumentation;
    }

    /**
     * Constructs the instance.
     */

    public PrometheusExporterMod() {
        // Nothing to do.
    }

    /**
     * Unregister the metrics collectors.
     */
    private void closeCollectors() {
        this.clearTrackingBindings();
        this.lscAdapter = null;
        this.ae2CpuCollector = null;
        // Stop the refresh scheduler.
        if (this.scheduler != null) {
            this.scheduler.clear();
            this.scheduler = null;
        }
        CollectorScheduler.Instance = null;

        // Stop feeding the event-driven tick collector.
        Ticks.Instance = null;

        // Unregister all collectors.
        CollectorRegistry.defaultRegistry.clear();
    }

    /**
     * Stop the HTTP server.
     */
    private void closeHttpServer() {
        // WARNING: Remember to stop the HTTP server. Otherwise, the Minecraft
        // client will crash because the TCP port will already be in use when trying
        // to load a second saved world.
        if (this.http_server != null) {
            this.http_server.close();
            this.http_server = null;
        }
    }

    /**
     * Register the metrics collectors.
     */
    private void initCollectors() {
        ExporterConfig.Collector cfg = ExporterConfig.collector;

        // Start the refresh scheduler that samples on the server thread.
        this.scheduler = new CollectorScheduler();
        CollectorScheduler.Instance = this.scheduler;

        // Collect JVM stats.
        if (cfg.jwm_collector) DefaultExports.register(CollectorRegistry.defaultRegistry);

        // The Ticks collector is event-driven, not interval-sampled.
        if (cfg.ticks) new Ticks(this.mc_server).register();

        if (cfg.entities) this.addSampler(new Entities(this.mc_server, cfg.entities_interval_ticks));
        if (cfg.tileentities) this.addSampler(new TileEntities(this.mc_server, cfg.tileentities_interval_ticks));
        if (cfg.chunks) this.addSampler(new Chunks(this.mc_server, cfg.chunks_interval_ticks));
        if (cfg.players) this.addSampler(new Players(this.mc_server, cfg.players_interval_ticks));
        if (cfg.player_statistics)
            this.addSampler(new PlayerStatistics(this.mc_server, cfg.player_statistics_interval_ticks));
        if (cfg.teams && ModCompat.ServerUtilities.isLoaded())
            this.addSampler(new Teams(this.mc_server, cfg.teams_interval_ticks));

        if (this.targets != null && this.instrumentation != null) {
            this.initTrackingCollectors(
                LscAdapter.create(this.targets, this.instrumentation),
                Ae2GridResolver.create(this.targets),
                PowerfailAdapter.create(this.targets));
        }

        // Self-monitoring metrics about the collectors.
        if (cfg.self_metrics) new SelfMetrics(this.scheduler).register();

        // Populate every snapshot once so the first scrape is not empty. Runs on
        // the server thread (server-started event).
        this.scheduler.refreshAll();
    }

    /** Register supported integrations even with no selections, so commands can enable targets later. */
    void initTrackingCollectors(LscAdapter lsc, Ae2GridResolver ae2, PowerfailAdapter powerfails) {
        ExporterConfig.Collector cfg = ExporterConfig.collector;
        this.lscAdapter = lsc;
        if (lsc != null) this.addSampler(new LscCollector(this.targets, lsc, cfg.lsc_interval_ticks));
        if (ae2 != null) {
            this.addSampler(new Ae2NetworkCollector(ae2, cfg.ae2_network_interval_ticks));
            this.ae2CpuCollector = new Ae2CpuCollector(ae2, this.instrumentation, cfg.ae2_cpu_interval_ticks);
            this.addSampler(this.ae2CpuCollector);
        }
        if (powerfails != null)
            this.addSampler(new PowerfailCollector(this.targets, powerfails, cfg.powerfails_interval_ticks));
    }

    private void clearTrackingBindings() {
        if (this.ae2CpuCollector != null) this.ae2CpuCollector.clear();
        if (this.lscAdapter != null) this.lscAdapter.clear();
    }

    /**
     * Register a sampler both with the refresh scheduler and the Prometheus
     * registry.
     *
     * @param sampler The sampler.
     */
    private void addSampler(Sampler sampler) {
        this.scheduler.register(sampler);
        sampler.register();
    }

    /**
     * Start the HTTP server.
     *
     * @throws IOException When an I/O error occurs while starting the HTTP
     *                     server.
     */
    private void initHttpServer() throws IOException {
        // WARNING: Make sure the HTTP server thread is daemonized, otherwise the
        // Minecraft server process will not properly terminate.
        String address = ExporterConfig.web.listen_address;
        int port = ExporterConfig.web.listen_port;
        try {
            this.http_server = new HTTPServer(address, port, true);
            LOG.info("Listening on {}:{}", address, port);
        } catch (BindException e) {
            LOG.error("Failed to start prometheus exporter, port {} already in use.", port);
        }
    }

    /**
     * Check whether the exporter is running.
     *
     * @return Whether the exporter is running.
     */
    public boolean isExporterRunning() {
        return this.is_running;
    }

    /**
     * Called before any other phase. Configuration files should be read.
     *
     * @param event The event.
     */
    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) {
        // Register the server config.
        try {
            ConfigurationManager.registerConfig(ExporterConfig.class);
        } catch (ConfigException e) {
            throw new RuntimeException(e);
        }

        MinecraftForge.EVENT_BUS.register(this);

        // Register event handlers.
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote && this.instrumentation != null) {
            this.clearTrackingBindings();
            this.instrumentation.clearDimensionBindings();
        }
    }

    /**
     * Called when the server is starting up.
     *
     * @param event The event.
     */
    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        // Register server commands in this event handler.
        event.registerServerCommand(new ForgePrometheusCommand());

        // Record the Minecraft server.
        this.mc_server = event.getServer();
        try {
            this.targets = TargetRegistry.open(
                this.mc_server.worldServerForDimension(0)
                    .getSaveHandler()
                    .getWorldDirectory()
                    .toPath());
            this.instrumentation = new InstrumentationStore(this.targets);
        } catch (IOException e) {
            LOG.error("Target tracking disabled; repair the world target configuration and restart the server", e);
        }
    }

    /**
     * Called when the server has started.
     *
     * @param event The event.
     *
     * @throws IOException When an I/O error occurs while starting the HTTP
     *                     server.
     */
    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) throws IOException {
        if (event.getSide()
            .isServer()) {
            this.startExporter();
        }
    }

    /**
     * Called when the server has stopped.
     *
     * @param event The event.
     */
    @Mod.EventHandler
    public void onServerStopped(FMLServerStoppedEvent event) {
        if (this.is_running) {
            this.stopExporter();
        }
        if (this.instrumentation != null) this.instrumentation.stop();
        if (this.targets != null) this.targets.close();
        this.instrumentation = null;
        this.targets = null;
        this.mc_server = null;
    }

    /**
     * Start the exporter by starting the HTTP server and registering the
     * metric collectors.
     *
     * @throws IOException           When an I/O error occurs while starting the HTTP
     *                               server.
     * @throws IllegalStateException When the exporter is already running.
     */
    public void startExporter() throws IOException {
        if (this.is_running) {
            throw new IllegalStateException("Exporter is already running.");
        }

        // Start HTTP server.
        this.initHttpServer();

        if (this.instrumentation != null) this.instrumentation.start();
        // Register collectors.
        this.initCollectors();

        this.is_running = true;
    }

    /**
     * Stop the exporter by stopping the HTTP server and unregistering the metric
     * collectors.
     *
     * @throws IllegalStateException When the exporter is not running.
     */
    public void stopExporter() {
        if (!this.is_running) {
            throw new IllegalStateException("Exporter is not running.");
        }

        // Close collectors.
        this.closeCollectors();
        if (this.instrumentation != null) this.instrumentation.stop();

        // Stop HTTP server.
        this.closeHttpServer();

        this.is_running = false;
    }
}
