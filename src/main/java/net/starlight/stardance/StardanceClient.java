package net.starlight.stardance;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.network.ClientGridManager;
import net.starlight.stardance.network.GridNetwork;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.render.CollisionShapeRenderer;
import net.starlight.stardance.render.DebugRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.KeybindRegistry;
import net.starlight.stardance.utils.SLogger;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * FIXED: Client entry point with enhanced debug logging and proper grid rendering.
 */
public class StardanceClient implements ClientModInitializer, ILoggingControl {

    // Debug counters
    private static int renderCallCount = 0;
    private static long lastRenderLogTime = 0;
    private static final long RENDER_LOG_INTERVAL = 3000; // Log every 3 seconds

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    // --------------------------------------------------
    // CLIENT MOD INITIALIZATION
    // --------------------------------------------------

    /**
     * Called during the client initialization phase.
     * Registers key bindings and world render event callbacks.
     */
    @Override
    public void onInitializeClient() {
        SLogger.log(this, "Initializing StardanceClient");

        // Register UI controls
        KeybindRegistry.registerKeyBindings();

        // Initialize the grid network system
        GridNetwork.init();

        // Initialize debug renderer
        DebugRenderer.getInstance();

        // Register rendering hooks
        WorldRenderEvents.BEFORE_ENTITIES.register(this::renderBeforeEntities);
        WorldRenderEvents.START.register(this::onRenderStart);
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderGrids);

        // Register world events
        ServerWorldEvents.LOAD.register(this::onServerWorldLoad);

        // Register client tick event
        ClientTickEvents.END_CLIENT_TICK.register(this::endClientTick);

        SLogger.log(this, "StardanceClient initialized successfully");
    }

    // --------------------------------------------------
    // RENDER CALLBACKS
    // --------------------------------------------------

    /**
     * Fired by Fabric's BEFORE_ENTITIES world render event. This is
     * where we inject custom grid rendering and collision shape
     * debug overlays.
     */
    public void renderBeforeEntities(WorldRenderContext context) {
        renderCollisionShapes(context);
    }

    private void onServerWorldLoad(MinecraftServer server, ServerWorld serverWorld) {
        SLogger.log(this, "Server world loaded: " + serverWorld.getRegistryKey().getValue());
    }

    private void endClientTick(MinecraftClient client) {
        // Periodic cleanup and debug info
        if (client.world != null) {
            ClientGridManager registry = ClientGridManager.getInstance();

            // Periodic debug logging
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRenderLogTime > RENDER_LOG_INTERVAL) {
                int gridCount = registry.getAllGrids().size();
                if (gridCount > 0) {
                    SLogger.log(this, "Client tick - managing " + gridCount + " grids, " +
                            renderCallCount + " render calls in last " +
                            (RENDER_LOG_INTERVAL / 1000) + " seconds");
                }
                renderCallCount = 0;
                lastRenderLogTime = currentTime;
            }
        }
    }

    private void onRenderStart(WorldRenderContext context) {
        // This is called at the start of each frame
        renderCallCount++;
    }

    // --------------------------------------------------
    // GRID RENDERING (ENHANCED)
    // --------------------------------------------------

    /**
     * FIXED: Enhanced grid rendering with interpolation support and debug logging.
     */
    private void renderGrids(WorldRenderContext context) {
        // Get the client world
        ClientWorld clientWorld = MinecraftClient.getInstance().world;
        if (clientWorld == null) {
            return;
        }

        // Get camera position
        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        // IMPORTANT: Get partial tick for smooth interpolation
        float partialTick = context.tickDelta();
        long currentWorldTick = clientWorld.getTime();

        // Get the grid manager
        ClientGridManager registry = ClientGridManager.getInstance();

        // Debug logging (periodic)
        if (renderCallCount % 60 == 0) { // Every 3 seconds at 20 FPS
            int gridCount = registry.getAllGrids().size();
            if (gridCount > 0) {
                SLogger.log(this, "RENDER: Attempting to render " + gridCount + " grids at tick " +
                        currentWorldTick + ", partialTick=" + String.format("%.3f", partialTick) +
                        ", camera at " + cameraPos);

                // Log details about each grid
                registry.getAllGrids().forEach((uuid, grid) -> {
                    if (grid != null) {
                        SLogger.log(this, "  Grid " + uuid + ": hasValidState=" + grid.hasValidState() +
                                ", GridSpace blocks=" + grid.getGridSpaceBlocks().size() +
                                ", Grid-local blocks=" + grid.getGridLocalBlocks().size() +
                                ", hasGridSpaceInfo=" + grid.hasGridSpaceInfo() +
                                ", position=" + grid.getPosition());
                    }
                });
            } else {
                SLogger.log(this, "RENDER: No grids to render (partialTick=" + String.format("%.3f", partialTick) + ")");
            }
        }

        // Position the rendering relative to camera
        MatrixStack matrices = context.matrixStack();
        matrices.push();

        try {
            // Translate to camera-relative coordinates
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            // IMPORTANT: Pass partialTick to grid manager for smooth interpolation
            registry.renderGrids(matrices, context.consumers(), partialTick, currentWorldTick);

        } catch (Exception e) {
            SLogger.log(this, "ERROR in grid rendering: " + e.getMessage());
            e.printStackTrace();
        } finally {
            matrices.pop();
        }
    }

    // --------------------------------------------------
    // COLLISION SHAPE RENDERING (UNCHANGED)
    // --------------------------------------------------

    /**
     * Renders collision shape wireframes if enabled, using the
     * current physics engine's Bullet data.
     */
    private void renderCollisionShapes(WorldRenderContext context) {
        if (!CollisionShapeRenderer.ENABLED) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider vertexConsumers = context.consumers();
        float tickDelta = context.tickDelta();

        // Position the rendering to the camera reference frame
        matrices.push();
        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Get the client world
        ClientWorld clientWorld = MinecraftClient.getInstance().world;
        if (clientWorld == null) {
            matrices.pop();
            return;
        }

        // Check if we're in singleplayer (integrated server)
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) {
            // We're on a dedicated server - collision shape rendering not available
            matrices.pop();
            return;
        }

        // Convert client world to server world
        ServerWorld serverWorld = server.getWorld(clientWorld.getRegistryKey());
        if (serverWorld == null) {
            matrices.pop();
            return;
        }

        // Fetch the physics engine
        PhysicsEngine physicsEngine = engineManager.getEngine(serverWorld);
        if (physicsEngine == null) {
            matrices.pop();
            return;
        }

        // Draw collision shapes in the bullet dynamics world
        DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
        new CollisionShapeRenderer().render(dynamicsWorld, matrices, vertexConsumers, tickDelta, physicsEngine);

        matrices.pop();
    }
}