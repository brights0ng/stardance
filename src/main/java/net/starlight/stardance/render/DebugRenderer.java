package net.starlight.stardance.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Provides client-side rendering of debug shapes and information.
 * This is a utility class for visualizing physics interactions, collision detection, etc.
 */
public class DebugRenderer implements ILoggingControl {
    // Static instance
    private static final DebugRenderer INSTANCE = new DebugRenderer();

    // Debug elements
    private final List<DebugElement> elements = new ArrayList<>();

    // Enabled state
    private boolean enabled = true;

    /**
     * Private constructor for singleton.
     */
    private DebugRenderer() {
        // Register world render event for rendering
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::render);

        // Register tick event for updating lifetimes
        ClientTickEvents.END_CLIENT_TICK.register(client -> updateElements());

        SLogger.log(this, "DebugRenderer initialized");
    }

    /**
     * Gets the singleton instance.
     */
    public static DebugRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * Enables or disables debug rendering.
     */
    public static void setEnabled(boolean enabled) {
        INSTANCE.enabled = enabled;
        SLogger.log(INSTANCE, "Debug rendering " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Gets the number of active debug elements.
     */
    public static int getElementCount() {
        return INSTANCE.elements.size();
    }

    /**
     * Clears all debug elements.
     */
    public static void clear() {
        INSTANCE.elements.clear();
        SLogger.log(INSTANCE, "Debug elements cleared");
    }

    /**
     * Updates all debug elements, removing ones that have expired.
     */
    private void updateElements() {
        Iterator<DebugElement> iterator = elements.iterator();

        while (iterator.hasNext()) {
            DebugElement element = iterator.next();

            // Update lifetime
            if (element.updateLifetime()) {
                // Element has expired, remove it
                iterator.remove();
            }
        }
    }

    /**
     * Renders all debug elements.
     */
    private void render(WorldRenderContext context) {
        if (!enabled || elements.isEmpty()) {
            return;
        }

        // Get camera position and look direction
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();

        // Get look vector from camera rotation
        float pitch = client.gameRenderer.getCamera().getPitch();
        float yaw = client.gameRenderer.getCamera().getYaw();
        Vec3d lookDir = getLookVectorFromRotation(pitch, yaw);

        // Get matrix from context
        Matrix4f positionMatrix = context.matrixStack().peek().getPositionMatrix();

        // Critical for drawing through blocks:
        // 1. Save the current depth function
        int originalDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        // 2. Save the depth test enabled state
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        // Start building lines
        RenderSystem.disableDepthTest();  // Fully disable depth testing
        RenderSystem.depthMask(false);    // Disable writing to the depth buffer
        RenderSystem.disableCull();       // Disable face culling
        RenderSystem.enableBlend();       // Enable transparency
        RenderSystem.defaultBlendFunc();  // Use default blending
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // Render line elements
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        for (DebugElement element : elements) {
            if (element instanceof Line) {
                ((Line) element).render(buffer, positionMatrix, cameraPos, lookDir);
            }
        }

        tessellator.draw();

        // Render point elements
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        for (DebugElement element : elements) {
            if (element instanceof Point) {
                ((Point) element).render(buffer, positionMatrix, cameraPos, lookDir);
            }
        }

        tessellator.draw();

        // Render box elements - these use DEBUG_LINES mode since they're wireframes
        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (DebugElement element : elements) {
            if (element instanceof Box3D) {
                ((Box3D) element).render(buffer, positionMatrix, cameraPos, lookDir);
            }
        }

        tessellator.draw();

        // Reset render state to what it was before
        if (depthTestEnabled) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }

        // Restore depth mask
        RenderSystem.depthMask(true);

        // Restore depth function
        GL11.glDepthFunc(originalDepthFunc);

        // Reset other render states
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Converts pitch and yaw angles to a look direction vector.
     */
    private Vec3d getLookVectorFromRotation(float pitch, float yaw) {
        float pitchRad = pitch * 0.017453292F;  // Convert to radians
        float yawRad = -yaw * 0.017453292F;     // Convert to radians

        float cosYaw = (float) Math.cos(yawRad);
        float sinYaw = (float) Math.sin(yawRad);
        float cosPitch = (float) Math.cos(pitchRad);
        float sinPitch = (float) Math.sin(pitchRad);

        return new Vec3d(
                sinYaw * cosPitch,
                -sinPitch,
                cosYaw * cosPitch
        );
    }

    // ===== Debug Shape Factory Methods =====

    /**
     * Adds a line to the debug renderer.
     *
     * @param start Start position
     * @param end End position
     * @param color ARGB color
     * @param width Line width
     * @param durationTicks How long to display the line (-1 for permanent)
     */
    public static void addLine(Vec3d start, Vec3d end, int color, float width, int durationTicks) {
        INSTANCE.elements.add(new Line(start, end, color, width, durationTicks));
    }

    /**
     * Adds a point to the debug renderer.
     *
     * @param position Point position
     * @param color ARGB color
     * @param size Point size
     * @param durationTicks How long to display the point (-1 for permanent)
     */
    public static void addPoint(Vec3d position, int color, float size, int durationTicks) {
        INSTANCE.elements.add(new Point(position, color, size, durationTicks));
    }

    /**
     * Adds a box to the debug renderer.
     *
     * @param box Box to render
     * @param color ARGB color
     * @param width Line width
     * @param durationTicks How long to display the box (-1 for permanent)
     */
    public static void addBox(Box box, int color, float width, int durationTicks) {
        INSTANCE.elements.add(new Box3D(box, color, width, durationTicks));
    }

    /**
     * Adds a crosshair marker at the specified position.
     *
     * @param position Center position
     * @param color ARGB color
     * @param size Size of the crosshair
     * @param width Line width
     * @param durationTicks How long to display (-1 for permanent)
     */
    public static void addCrosshair(Vec3d position, int color, float size, float width, int durationTicks) {
        // X-axis line
        addLine(
                position.add(-size, 0, 0),
                position.add(size, 0, 0),
                color, width, durationTicks
        );

        // Y-axis line
        addLine(
                position.add(0, -size, 0),
                position.add(0, size, 0),
                color, width, durationTicks
        );

        // Z-axis line
        addLine(
                position.add(0, 0, -size),
                position.add(0, 0, size),
                color, width, durationTicks
        );
    }

    /**
     * Adds a ray visualization.
     *
     * @param start Ray start position
     * @param direction Ray direction
     * @param length Ray length
     * @param color ARGB color
     * @param width Line width
     * @param durationTicks How long to display (-1 for permanent)
     */
    public static void addRay(Vec3d start, Vec3d direction, double length, int color, float width, int durationTicks) {
        Vec3d normalizedDir = direction.normalize();
        Vec3d end = start.add(normalizedDir.multiply(length));

        addLine(start, end, color, width, durationTicks);
    }

    /**
     * Creates a trajectory visualization.
     *
     * @param start Start position
     * @param velocity Initial velocity
     * @param steps Number of steps to visualize
     * @param timeStep Time step between points (in seconds)
     * @param gravity Whether to apply gravity
     * @param color ARGB color
     * @param width Line width
     * @param durationTicks How long to display (-1 for permanent)
     */
    public static void addTrajectory(Vec3d start, Vec3d velocity, int steps,
                                     float timeStep, boolean gravity,
                                     int color, float width, int durationTicks) {
        Vec3d pos = start;
        Vec3d vel = velocity;
        Vec3d gravityVec = gravity ? new Vec3d(0, -9.8, 0) : Vec3d.ZERO;

        for (int i = 0; i < steps - 1; i++) {
            Vec3d nextPos = pos.add(vel.multiply(timeStep));
            Vec3d nextVel = vel.add(gravityVec.multiply(timeStep));

            addLine(pos, nextPos, color, width, durationTicks);

            pos = nextPos;
            vel = nextVel;
        }
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    // ===== Debug Element Classes =====

    /**
     * Base class for all debug elements.
     */
    private abstract static class DebugElement {
        protected final int color;
        protected final float width;
        protected int remainingTicks;
        protected final boolean isPermanent;

        /**
         * Creates a new debug element.
         *
         * @param color ARGB color
         * @param width Line width
         * @param durationTicks How long to display (-1 for permanent)
         */
        public DebugElement(int color, float width, int durationTicks) {
            this.color = color;
            this.width = width;
            this.remainingTicks = durationTicks;
            this.isPermanent = (durationTicks < 0);
        }

        /**
         * Updates the lifetime of this element.
         *
         * @return True if the element has expired and should be removed
         */
        public boolean updateLifetime() {
            if (isPermanent) {
                return false;
            }

            remainingTicks--;
            return remainingTicks <= 0;
        }

        /**
         * Gets the RGBA components from a packed color.
         */
        protected int[] getRGBA(int color) {
            int a = (color >> 24) & 0xFF;
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            // If alpha is 0, make it fully opaque
            if (a == 0) {
                a = 255;
            }

            return new int[] { r, g, b, a };
        }
    }

    /**
     * Represents a line in 3D space, rendered as a billboarded quad.
     */
    private static class Line extends DebugElement {
        private final Vec3d start;
        private final Vec3d end;

        /**
         * Creates a new line.
         */
        public Line(Vec3d start, Vec3d end, int color, float width, int durationTicks) {
            super(color, width, durationTicks);
            this.start = start;
            this.end = end;
        }

        /**
         * Renders the line as a billboarded quad.
         */
        public void render(BufferBuilder buffer, Matrix4f matrix, Vec3d camera, Vec3d cameraLook) {
            // If width is very small, skip rendering
            if (width < 0.01f) {
                return;
            }

            int[] rgba = getRGBA(color);

            // Line direction vector
            Vec3d lineDir = end.subtract(start).normalize();

            // Create a vector perpendicular to both the line and view direction
            // This will be our "up" vector for the billboard
            Vec3d viewDir = camera.subtract(start.add(end).multiply(0.5)).normalize();
            Vec3d right = viewDir.crossProduct(lineDir).normalize();

            // If right is too small (line is parallel to view direction), use a fallback
            if (right.lengthSquared() < 0.001) {
                // Use world up as fallback
                right = new Vec3d(0, 1, 0).crossProduct(lineDir);

                // If still too small, use world forward
                if (right.lengthSquared() < 0.001) {
                    right = new Vec3d(0, 0, 1).crossProduct(lineDir);
                }

                right = right.normalize();
            }

            // Scale by half width
            Vec3d offset = right.multiply(width * 0.5);

            // Four corners of the quad
            Vec3d startTop = start.add(offset);
            Vec3d startBottom = start.subtract(offset);
            Vec3d endTop = end.add(offset);
            Vec3d endBottom = end.subtract(offset);

            // Draw as two triangles
            addVertex(buffer, matrix, camera, startTop, rgba);
            addVertex(buffer, matrix, camera, startBottom, rgba);
            addVertex(buffer, matrix, camera, endTop, rgba);

            addVertex(buffer, matrix, camera, endTop, rgba);
            addVertex(buffer, matrix, camera, startBottom, rgba);
            addVertex(buffer, matrix, camera, endBottom, rgba);
        }

        /**
         * Helper to add a vertex to the buffer.
         */
        private void addVertex(BufferBuilder buffer, Matrix4f matrix, Vec3d camera, Vec3d pos, int[] rgba) {
            buffer.vertex(matrix,
                            (float)(pos.x - camera.x),
                            (float)(pos.y - camera.y),
                            (float)(pos.z - camera.z))
                    .color(rgba[0], rgba[1], rgba[2], rgba[3])
                    .next();
        }
    }

    /**
     * Represents a point in 3D space, rendered as a billboarded quad.
     */
    private static class Point extends DebugElement {
        private final Vec3d position;
        private final float size;

        /**
         * Creates a new point.
         */
        public Point(Vec3d position, int color, float size, int durationTicks) {
            super(color, 1.0f, durationTicks);
            this.position = position;
            this.size = size;
        }

        /**
         * Renders the point as a billboarded quad.
         */
        public void render(BufferBuilder buffer, Matrix4f matrix, Vec3d camera, Vec3d cameraLook) {
            int[] rgba = getRGBA(color);

            // Create two perpendicular vectors in the plane facing the camera
            Vec3d viewDir = camera.subtract(position).normalize();

            // Find right and up vectors for the billboard
            Vec3d worldUp = new Vec3d(0, 1, 0);
            Vec3d right = worldUp.crossProduct(viewDir).normalize();
            if (right.lengthSquared() < 0.001) {
                // Camera looking straight up/down - use a different reference vector
                right = new Vec3d(1, 0, 0);
            }

            Vec3d up = viewDir.crossProduct(right).normalize();

            // Scale by size/2
            right = right.multiply(size / 2.0);
            up = up.multiply(size / 2.0);

            // Four corners of the quad
            Vec3d topRight = position.add(up).add(right);
            Vec3d topLeft = position.add(up).subtract(right);
            Vec3d bottomLeft = position.subtract(up).subtract(right);
            Vec3d bottomRight = position.subtract(up).add(right);

            // Draw as two triangles
            addVertex(buffer, matrix, camera, topRight, rgba);
            addVertex(buffer, matrix, camera, topLeft, rgba);
            addVertex(buffer, matrix, camera, bottomLeft, rgba);

            addVertex(buffer, matrix, camera, bottomLeft, rgba);
            addVertex(buffer, matrix, camera, bottomRight, rgba);
            addVertex(buffer, matrix, camera, topRight, rgba);
        }

        /**
         * Helper to add a vertex to the buffer.
         */
        private void addVertex(BufferBuilder buffer, Matrix4f matrix, Vec3d camera, Vec3d pos, int[] rgba) {
            buffer.vertex(matrix,
                            (float)(pos.x - camera.x),
                            (float)(pos.y - camera.y),
                            (float)(pos.z - camera.z))
                    .color(rgba[0], rgba[1], rgba[2], rgba[3])
                    .next();
        }
    }

    /**
     * Represents a 3D box, rendered as billboarded lines for each edge.
     */
    private static class Box3D extends DebugElement {
        private final Box box;

        /**
         * Creates a new box.
         */
        public Box3D(Box box, int color, float width, int durationTicks) {
            super(color, width, durationTicks);
            this.box = box;
        }

        /**
         * Renders the box as a wireframe with billboarded lines.
         * For boxes we use the standard DEBUG_LINES to keep things simple,
         * but each edge could be replaced with a billboarded line if needed.
         */
        public void render(BufferBuilder buffer, Matrix4f matrix, Vec3d camera, Vec3d cameraLook) {
            int[] rgba = getRGBA(color);

            // Bottom face
            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.minY, box.minZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.minY, box.minZ,
                    box.maxX, box.minY, box.maxZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.minY, box.maxZ,
                    box.minX, box.minY, box.maxZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.minY, box.maxZ,
                    box.minX, box.minY, box.minZ);

            // Top face
            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.maxY, box.minZ,
                    box.maxX, box.maxY, box.minZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.maxY, box.minZ,
                    box.maxX, box.maxY, box.maxZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.maxY, box.maxZ,
                    box.minX, box.maxY, box.maxZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.maxY, box.maxZ,
                    box.minX, box.maxY, box.minZ);

            // Connecting lines
            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.minY, box.minZ,
                    box.minX, box.maxY, box.minZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.minZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.maxX, box.minY, box.maxZ,
                    box.maxX, box.maxY, box.maxZ);

            renderLine(buffer, matrix, camera, rgba,
                    box.minX, box.minY, box.maxZ,
                    box.minX, box.maxY, box.maxZ);
        }

        /**
         * Helper to render a line segment.
         */
        private void renderLine(BufferBuilder buffer, Matrix4f matrix, Vec3d camera,
                                int[] rgba, double x1, double y1, double z1, double x2, double y2, double z2) {
            buffer.vertex(matrix,
                            (float)(x1 - camera.x),
                            (float)(y1 - camera.y),
                            (float)(z1 - camera.z))
                    .color(rgba[0], rgba[1], rgba[2], rgba[3])
                    .next();

            buffer.vertex(matrix,
                            (float)(x2 - camera.x),
                            (float)(y2 - camera.y),
                            (float)(z2 - camera.z))
                    .color(rgba[0], rgba[1], rgba[2], rgba[3])
                    .next();
        }
    }
}