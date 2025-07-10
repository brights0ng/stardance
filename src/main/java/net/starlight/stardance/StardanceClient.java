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
import net.starlight.stardance.render.GridRenderingIntegration;
import net.starlight.stardance.render.GridSpaceRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.KeybindRegistry;
import net.starlight.stardance.utils.SLogger;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Client entry point for the Stardance mod, handling client-side
 * initialization, rendering hooks, and debug overlays.
 */
public class StardanceClient implements ClientModInitializer, ILoggingControl {
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
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

        SLogger.log(this, "StardanceClient initialized");
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
        // Periodic cleanup
        if (client.world != null) {
            ClientGridManager registry = ClientGridManager.getInstance();

            // Here we could do periodic cleanup if needed
        }
    }

    private void onRenderStart(WorldRenderContext context) {
        // This is called at the start of each frame
    }

    // --------------------------------------------------
    // PRIVATE RENDERING HELPERS
    // --------------------------------------------------

    private void renderGrids(WorldRenderContext context) {
        // Get the client world
        ClientWorld clientWorld = MinecraftClient.getInstance().world;
        if (clientWorld == null) return;

        // Get camera position
        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        // Calculate server partial tick
        float partialTick = context.tickDelta();
        long currentWorldTick = clientWorld.getTime();

        // Position the rendering relative to camera
        MatrixStack matrices = context.matrixStack();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Render all client grids
        ClientGridManager registry = ClientGridManager.getInstance();
        registry.renderGrids(matrices, context.consumers(), partialTick, currentWorldTick);

        matrices.pop();
    }

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

        // Convert client world to server world
        ServerWorld serverWorld = MinecraftClient.getInstance().getServer().getWorld(clientWorld.getRegistryKey());
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