package net.starlight.stardance.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLEAN: Client-side grid with simple, reliable interpolation between known server states.
 * No extrapolation - just smooth movement between last and current positions.
 */
public class ClientLocalGrid implements ILoggingControl {

    // Grid identification
    private final UUID gridId;

    // Simple two-state interpolation
    private Vector3f lastPosition = new Vector3f();
    private Vector3f currentPosition = new Vector3f();
    private Quat4f lastRotation = new Quat4f(0, 0, 0, 1);
    private Quat4f currentRotation = new Quat4f(0, 0, 0, 1);
    private Vector3f currentCentroid = new Vector3f();

    // Timing for interpolation
    private long lastUpdateTime = 0;
    private long updateInterval = 50; // Expected time between updates (50ms = 20 TPS)
    private boolean hasValidState = false;
    private long lastServerTick = 0;

    // Block storage
    private final Map<BlockPos, BlockState> gridLocalBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> gridSpaceBlocks = new ConcurrentHashMap<>();

    // GridSpace region information
    private int regionId = -1;
    private BlockPos regionOrigin = null;
    private boolean hasGridSpaceInfo = false;

    // Debug
    private int renderCallCount = 0;

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------

    public ClientLocalGrid(UUID gridId) {
        this.gridId = gridId;
        SLogger.log(this, "Created clean interpolation ClientLocalGrid: " + gridId);
    }

    // ----------------------------------------------
    // STATE UPDATES
    // ----------------------------------------------

    /**
     * CLEAN: Simple state updates with proper interpolation setup.
     */
    public void updateState(Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
        long currentTime = System.currentTimeMillis();

        // Basic rate limiting: max 25 updates per second
        if (hasValidState && (currentTime - lastUpdateTime) < 40) {
            return; // Too frequent
        }

        // Ignore old packets
        if (hasValidState && serverTick < lastServerTick) {
            return;
        }

        // Ignore tiny position changes
        if (hasValidState) {
            float distance = new Vector3f(
                    position.x - currentPosition.x,
                    position.y - currentPosition.y,
                    position.z - currentPosition.z
            ).length();

            if (distance < 0.005f) { // Less than 5mm
                return;
            }
        }

        // Set up interpolation: current becomes last, new becomes current
        if (hasValidState) {
            lastPosition.set(currentPosition);
            lastRotation.set(currentRotation);

            // Calculate actual interval for better interpolation timing
            updateInterval = Math.max(40, currentTime - lastUpdateTime);
        } else {
            // First update - initialize both to same position (no interpolation)
            lastPosition.set(position);
            lastRotation.set(rotation);
        }

        // Update current state
        currentPosition.set(position);
        currentRotation.set(rotation);
        currentCentroid.set(centroid);
        lastServerTick = serverTick;
        lastUpdateTime = currentTime;
        hasValidState = true;

        // Log occasionally
        if (Math.random() < 0.02) { // 2% chance
            SLogger.log(this, "Updated state for grid " + gridId + " at tick " + serverTick +
                    ", interval: " + updateInterval + "ms");
        }
    }

    /**
     * GridSpace region info update.
     */
    public void updateGridSpaceInfo(int regionId, BlockPos regionOrigin) {
        this.regionId = regionId;
        this.regionOrigin = regionOrigin;
        this.hasGridSpaceInfo = true;
    }

    /**
     * Updates blocks using GridSpace coordinates.
     */
    public void updateGridSpaceBlocks(Map<BlockPos, BlockState> blocks) {
        gridSpaceBlocks.clear();
        gridSpaceBlocks.putAll(blocks);

        if (blocks.size() > 0) {
            SLogger.log(this, "Updated GridSpace blocks for grid " + gridId + ": " + blocks.size() + " blocks");
        }
    }

    /**
     * Updates blocks using grid-local coordinates (legacy).
     */
    public void updateBlocks(Map<BlockPos, BlockState> blocks) {
        gridLocalBlocks.clear();
        gridLocalBlocks.putAll(blocks);
    }

    /**
     * Updates a single block.
     */
    public void updateBlock(BlockPos pos, BlockState state) {
        if (state != null) {
            gridLocalBlocks.put(pos, state);
        } else {
            gridLocalBlocks.remove(pos);
        }
    }

    // ----------------------------------------------
    // CLEAN INTERPOLATION RENDERING
    // ----------------------------------------------

    /**
     * CLEAN: Renders with simple, reliable interpolation between known states.
     */
    public void render(PoseStack matrices, MultiBufferSource vertexConsumers,
                       float partialTick, long currentWorldTick) {
        renderCallCount++;

        if (!hasValidState) {
            return; // No state to render
        }

        // Choose which block set to render
        Map<BlockPos, BlockState> blocksToRender;
        boolean usingGridSpaceBlocks = false;

        if (!gridSpaceBlocks.isEmpty() && hasGridSpaceInfo) {
            blocksToRender = gridSpaceBlocks;
            usingGridSpaceBlocks = true;
        } else if (!gridLocalBlocks.isEmpty()) {
            blocksToRender = gridLocalBlocks;
        } else {
            return; // No blocks to render
        }

        // Occasional logging
        if (renderCallCount % 300 == 0) { // Every 15 seconds at 20 FPS
            SLogger.log(this, "Rendering grid " + gridId + " with " + blocksToRender.size() +
                    " blocks (" + (usingGridSpaceBlocks ? "GridSpace" : "grid-local") + ")");
        }

        // Render blocks
        matrices.pushPose();
        try {
            if (usingGridSpaceBlocks) {
                renderGridSpaceBlocks(matrices, vertexConsumers, blocksToRender, partialTick);
            } else {
                renderGridLocalBlocks(matrices, vertexConsumers, blocksToRender, partialTick);
            }
        } catch (Exception e) {
            SLogger.log(this, "Error rendering grid " + gridId + ": " + e.getMessage());
        } finally {
            matrices.popPose();
        }
    }

    /**
     * CLEAN: Calculates interpolated position between last and current server states.
     * NO extrapolation - only interpolates between known positions.
     */
    private Vector3f getInterpolatedPosition() {
        if (!hasValidState) {
            return new Vector3f();
        }

        // Calculate how much time has passed since the last update
        long currentTime = System.currentTimeMillis();
        long timeSinceUpdate = currentTime - lastUpdateTime;

        // Calculate interpolation factor (0.0 = use last position, 1.0 = use current position)
        float interpFactor = Math.min(1.0f, (float) timeSinceUpdate / (float) updateInterval);

        // CRITICAL: Never extrapolate beyond current position (clamp to max 1.0)
        interpFactor = Math.max(0.0f, Math.min(1.0f, interpFactor));

        // Linear interpolation between last and current
        Vector3f result = new Vector3f();
        result.x = lastPosition.x + (currentPosition.x - lastPosition.x) * interpFactor;
        result.y = lastPosition.y + (currentPosition.y - lastPosition.y) * interpFactor;
        result.z = lastPosition.z + (currentPosition.z - lastPosition.z) * interpFactor;

        return result;
    }

    /**
     * CLEAN: Calculates interpolated rotation between last and current server states.
     */
    private Quaternionf getInterpolatedRotation() {
        if (!hasValidState) {
            return new Quaternionf(0, 0, 0, 1);
        }

        // Calculate interpolation factor (same as position)
        long currentTime = System.currentTimeMillis();
        long timeSinceUpdate = currentTime - lastUpdateTime;
        float interpFactor = Math.min(1.0f, (float) timeSinceUpdate / (float) updateInterval);
        interpFactor = Math.max(0.0f, Math.min(1.0f, interpFactor));

        // Check for valid quaternions
        if (!isValidQuaternion(lastRotation) || !isValidQuaternion(currentRotation)) {
            return new Quaternionf(currentRotation.x, currentRotation.y, currentRotation.z, currentRotation.w);
        }

        // Convert to JOML quaternions
        Quaternionf lastQuat = new Quaternionf(lastRotation.x, lastRotation.y, lastRotation.z, lastRotation.w);
        Quaternionf currentQuat = new Quaternionf(currentRotation.x, currentRotation.y, currentRotation.z, currentRotation.w);

        // SLERP interpolation
        Quaternionf result = new Quaternionf(lastQuat);
        result.slerp(currentQuat, interpFactor);
        result.normalize();

        return result;
    }

    /**
     * Check if quaternion is valid.
     */
    private boolean isValidQuaternion(Quat4f q) {
        float length = (float) Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
        return Math.abs(length - 1.0f) < 0.1f;
    }

    /**
     * Apply interpolated transform to matrix stack.
     */
    private void applyGridTransform(PoseStack matrices, float partialTick) {
        // Use interpolated position and rotation
        Vector3f interpPos = getInterpolatedPosition();
        Quaternionf interpRot = getInterpolatedRotation();

        // Apply position
        matrices.translate(interpPos.x, interpPos.y, interpPos.z);

        // Apply rotation
        matrices.mulPose(interpRot);

        // Apply centroid offset
        if (currentCentroid != null) {
            matrices.translate(-currentCentroid.x, -currentCentroid.y, -currentCentroid.z);
        }
    }

    // ----------------------------------------------
    // RENDERING HELPERS (UNCHANGED)
    // ----------------------------------------------

    private void renderGridSpaceBlocks(PoseStack matrices, MultiBufferSource vertexConsumers,
                                       Map<BlockPos, BlockState> gridSpaceBlocks, float partialTick) {
        applyGridTransform(matrices, partialTick);

        for (Map.Entry<BlockPos, BlockState> entry : gridSpaceBlocks.entrySet()) {
            BlockPos gridSpacePos = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos gridLocalPos = convertGridSpaceToGridLocal(gridSpacePos);

            if (gridLocalPos != null) {
                renderBlockAt(matrices, vertexConsumers, gridLocalPos, state);
            }
        }
    }

    private void renderGridLocalBlocks(PoseStack matrices, MultiBufferSource vertexConsumers,
                                       Map<BlockPos, BlockState> gridLocalBlocks, float partialTick) {
        applyGridTransform(matrices, partialTick);

        for (Map.Entry<BlockPos, BlockState> entry : gridLocalBlocks.entrySet()) {
            BlockPos gridLocalPos = entry.getKey();
            BlockState state = entry.getValue();
            renderBlockAt(matrices, vertexConsumers, gridLocalPos, state);
        }
    }

    private BlockPos convertGridSpaceToGridLocal(BlockPos gridSpacePos) {
        if (!hasGridSpaceInfo || regionOrigin == null) {
            return null;
        }

        int regionLocalX = gridSpacePos.getX() - regionOrigin.getX();
        int regionLocalY = gridSpacePos.getY() - regionOrigin.getY();
        int regionLocalZ = gridSpacePos.getZ() - regionOrigin.getZ();

        int gridLocalX = regionLocalX - 512; // GRIDSPACE_CENTER_OFFSET
        int gridLocalY = regionLocalY - 512;
        int gridLocalZ = regionLocalZ - 512;

        return new BlockPos(gridLocalX, gridLocalY, gridLocalZ);
    }

    /**
     * ENHANCED: Renders a single block with proper lighting, tinting, and render layers.
     */
    private void renderBlockAt(PoseStack matrices, MultiBufferSource vertexConsumers,
                               BlockPos gridLocalPos, BlockState state) {

        if (state == null || state.isAir()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderManager = client.getBlockRenderer();

        if (blockRenderManager == null) {
            return;
        }

        matrices.pushPose();
        try {
            matrices.translate(gridLocalPos.getX(), gridLocalPos.getY(), gridLocalPos.getZ());

            RenderShape renderType = state.getRenderShape();

            if (renderType == RenderShape.MODEL) {
                BakedModel model = blockRenderManager.getBlockModel(state);

                if (model != null) {
                    // ENHANCED: Calculate proper lighting based on world position
                    int lightValue = calculateLightValue(gridLocalPos);

                    // ENHANCED: Get block tinting for grass, leaves, etc.
                    int blockColor = getBlockColor(state, gridLocalPos);
                    float red = ((blockColor >> 16) & 0xFF) / 255.0f;
                    float green = ((blockColor >> 8) & 0xFF) / 255.0f;
                    float blue = (blockColor & 0xFF) / 255.0f;

                    // ENHANCED: Support multiple render layers
                    renderBlockInLayers(matrices, vertexConsumers, state, model,
                            red, green, blue, lightValue);
                }
            } else if (renderType == RenderShape.ENTITYBLOCK_ANIMATED) {
                // Handle special block entities (chests, etc.) if needed
                renderBlockEntity(matrices, vertexConsumers, gridLocalPos, state);
            }

        } catch (Exception e) {
            // Silent failure to avoid spam
        } finally {
            matrices.popPose();
        }
    }

    private void renderBlockInLayers(PoseStack matrices, MultiBufferSource vertexConsumers,
                                     BlockState state, BakedModel model,
                                     float red, float green, float blue, int lightValue) {

        Minecraft client = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderManager = client.getBlockRenderer();

        // Get the correct render layer(s) for this specific block
        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);

        try {
            blockRenderManager.getModelRenderer().renderModel(
                    matrices.last(),
                    vertexConsumers.getBuffer(layer),
                    state,
                    model,
                    red, green, blue,
                    lightValue,
                    OverlayTexture.NO_OVERLAY
            );
        } catch (Exception e) {
            // Fallback to solid if the correct layer fails
            blockRenderManager.getModelRenderer().renderModel(
                    matrices.last(),
                    vertexConsumers.getBuffer(RenderType.solid()),
                    state,
                    model,
                    red, green, blue,
                    lightValue,
                    OverlayTexture.NO_OVERLAY
            );
        }
    }

    /**
     * ENHANCED: Calculates proper lighting value based on world position.
     */
    private int calculateLightValue(BlockPos gridLocalPos) {
        Minecraft client = Minecraft.getInstance();

        if (client.level == null) {
            return 0xF000F0; // Fallback to full bright
        }

        // Get interpolated grid position in world space
        Vector3f worldPos = getInterpolatedPosition();
        BlockPos worldBlockPos = new BlockPos(
                (int) Math.floor(worldPos.x + gridLocalPos.getX()),
                (int) Math.floor(worldPos.y + gridLocalPos.getY()),
                (int) Math.floor(worldPos.z + gridLocalPos.getZ())
        );

        // Get lighting from the world at this position
        int skyLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, worldBlockPos);
        int blockLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, worldBlockPos);

        // Pack lighting values (Minecraft format)
        return LightTexture.pack(blockLight, skyLight);    }

    /**
     * ENHANCED: Gets proper block color including biome tinting.
     */
    private int getBlockColor(BlockState state, BlockPos gridLocalPos) {
        Minecraft client = Minecraft.getInstance();

        if (client.level == null) {
            return 0xFFFFFF; // White fallback
        }

        try {
            // Get block colors instance
            net.minecraft.client.color.block.BlockColors blockColors = client.getBlockColors();

            // Calculate world position for biome sampling
            Vector3f worldPos = getInterpolatedPosition();
            BlockPos worldBlockPos = new BlockPos(
                    (int) Math.floor(worldPos.x + gridLocalPos.getX()),
                    (int) Math.floor(worldPos.y + gridLocalPos.getY()),
                    (int) Math.floor(worldPos.z + gridLocalPos.getZ())
            );

            // Get color with biome context
            return blockColors.getColor(state, client.level, worldBlockPos, 0);

        } catch (Exception e) {
            return 0xFFFFFF; // White fallback
        }
    }

    /**
     * ENHANCED: Handles special block entities (chests, furnaces, etc.).
     */
    private void renderBlockEntity(PoseStack matrices, MultiBufferSource vertexConsumers,
                                   BlockPos gridLocalPos, BlockState state) {
        // For now, just render as a regular block
        // TODO: Implement full block entity rendering if needed
        Minecraft client = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderManager = client.getBlockRenderer();
        BakedModel model = blockRenderManager.getBlockModel(state);

        if (model != null) {
            int lightValue = calculateLightValue(gridLocalPos);

            blockRenderManager.getModelRenderer().renderModel(
                    matrices.last(),
                    vertexConsumers.getBuffer(RenderType.solid()),
                    state,
                    model,
                    1.0f, 1.0f, 1.0f,
                    lightValue,
                    OverlayTexture.NO_OVERLAY
            );
        }
    }

    // ----------------------------------------------
    // GETTERS
    // ----------------------------------------------

    public UUID getGridId() { return gridId; }
    public Vector3f getPosition() { return getInterpolatedPosition(); }
    public Vector3f getCentroid() { return currentCentroid; }
    public Quat4f getRotation() { return currentRotation; }
    public long getLastServerTick() { return lastServerTick; }
    public boolean hasValidState() { return hasValidState; }

    public Map<BlockPos, BlockState> getGridSpaceBlocks() { return gridSpaceBlocks; }
    public Map<BlockPos, BlockState> getGridLocalBlocks() { return gridLocalBlocks; }
    public boolean hasGridSpaceInfo() { return hasGridSpaceInfo; }
    public int getRegionId() { return regionId; }
    public BlockPos getRegionOrigin() { return regionOrigin; }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return true; }
}