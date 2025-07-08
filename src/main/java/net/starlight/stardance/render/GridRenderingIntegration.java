package net.starlight.stardance.render;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;

import java.util.Set;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Integration class that hooks into Minecraft's rendering pipeline.
 * This is where you'll connect the GridSpaceRenderer to Minecraft's world rendering.
 *
 * You'll need to call renderGrids() from your main rendering code, probably via a mixin
 * to WorldRenderer or similar.
 */
public class GridRenderingIntegration {

    private static final GridSpaceRenderer gridRenderer = new GridSpaceRenderer();

    /**
     * Call this from your world rendering code (via mixin or event).
     * This should be called during the block rendering phase.
     *
     * @param matrixStack Matrix stack from world renderer
     * @param vertexConsumers Vertex consumers from world renderer
     * @param camera Camera position for culling
     */
    public static void renderAllGrids(MatrixStack matrixStack, VertexConsumerProvider vertexConsumers, Vec3d camera) {
        System.out.println("=== GridRenderingIntegration.renderAllGrids() called ===");
        System.out.println("Camera position: " + camera);

        try {
            // Get all grids from the physics engine
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                System.out.println("Client world is null, exiting");
                return;
            }

            System.out.println("Client world: " + client.world.getRegistryKey().getValue());

            // Try to get the physics engine - first try with client world directly
            PhysicsEngine engine = engineManager.getEngine(client.world);

            if (engine == null) {
                System.out.println("No PhysicsEngine found for client world directly");

                // If that fails, try to get the server world (for integrated server)
                if (client.getServer() != null) {
                    System.out.println("Trying to get server world from integrated server");
                    var serverWorld = client.getServer().getWorld(client.world.getRegistryKey());
                    if (serverWorld != null) {
                        System.out.println("Found server world: " + serverWorld.getRegistryKey().getValue());
                        engine = engineManager.getEngine(serverWorld);
                    } else {
                        System.out.println("No server world found");
                    }
                } else {
                    System.out.println("No integrated server available");
                }
            }

            if (engine == null) {
                System.out.println("No PhysicsEngine found for any world - this is likely the issue!");
                System.out.println("Available engines in engineManager: " + engineManager.toString());
                return;
            }

            System.out.println("Found PhysicsEngine: " + engine);

            Set<LocalGrid> grids = engine.getGrids();
            if (grids == null) {
                System.out.println("Engine.getGrids() returned null");
                return;
            }

            if (grids.isEmpty()) {
                System.out.println("No grids found in physics engine");
                return;
            }

            System.out.println("Found " + grids.size() + " grids to render");

            // List all grids for debugging
            for (LocalGrid grid : grids) {
                System.out.println("Grid: " + grid.getGridId() + ", blocks: " + grid.getBlocks().size() +
                        ", destroyed: " + grid.isDestroyed());
            }

            // Render all grids using VS2-style projection
            gridRenderer.renderGrids(matrixStack, vertexConsumers, camera, grids);

        } catch (Exception e) {
            // Log error but don't crash rendering
            System.err.println("Error rendering grids: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Cleanup method to call on mod shutdown.
     */
    public static void cleanup() {
        gridRenderer.cleanup();
    }
}