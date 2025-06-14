package net.starlight.stardance.interaction;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

/**
 * Main coordinator for grid interactions. Delegates to specialized services.
 */
public class GridInteractionHandler implements ILoggingControl {

    private final GridDetectionService detectionService = new GridDetectionService();
    private final GridRaycastEngine raycastEngine = new GridRaycastEngine();
    private final GridBlockEntityHandler blockEntityHandler = new GridBlockEntityHandler();
    private final GridBlockPlacer blockPlacer = new GridBlockPlacer();
    private final GridBlockBreaker blockBreaker = new GridBlockBreaker();

    private final float playerReachDistance = 4.5f;

    public ActionResult onUseBlock(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }

        SLogger.log(this, "=== onUseBlock START === Player: " + player.getName().getString() +
                ", Sneaking: " + player.isSneaking() +
                ", Side: " + (player.getEntityWorld().isClient ? "CLIENT" : "SERVER"));

        ItemStack heldItem = player.getStackInHand(hand);
        boolean holdingBlockItem = heldItem.getItem() instanceof BlockItem;

        LocalGrid gridInView = detectionService.getGridPlayerIsLookingAt(player);

        if (gridInView != null) {
            SLogger.log(this, "Player is looking at a grid - intercepting on " +
                    (player.getEntityWorld().isClient ? "CLIENT" : "SERVER"));

            if (player.getEntityWorld().isClient) {
                return ActionResult.SUCCESS;
            }

            // SERVER-SIDE LOGIC
            GridBlockEntityInfo blockEntityInfo = getGridBlockEntityPlayerIsLookingAt(player);

            if (blockEntityInfo != null && !player.isSneaking()) {
                boolean handled = blockEntityHandler.handleBlockEntityInteraction(
                        player, hand, blockEntityInfo.grid, blockEntityInfo.gridLocalPos,
                        blockEntityInfo.blockState, blockEntityInfo.raycastResult);
                return handled ? ActionResult.SUCCESS : ActionResult.SUCCESS;
            }

            if (holdingBlockItem) {
                boolean handled = handleGridInteraction(player, hand, (BlockItem) heldItem.getItem());
                return ActionResult.SUCCESS;
            }

            if (blockEntityInfo != null && player.isSneaking()) {
                boolean handled = blockEntityHandler.handleBlockEntityInteraction(
                        player, hand, blockEntityInfo.grid, blockEntityInfo.gridLocalPos,
                        blockEntityInfo.blockState, blockEntityInfo.raycastResult);
                return handled ? ActionResult.SUCCESS : ActionResult.SUCCESS;
            }

            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    public boolean handleGridInteraction(PlayerEntity player, Hand hand, BlockItem blockItem) {
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        LocalGrid grid = detectionService.getGridPlayerIsLookingAt(player);
        if (grid == null) {
            return false;
        }

        GridRaycastResult result = raycastEngine.performGridRaycast(eyePos, reachPoint, grid);
        if (result != null) {
            blockPlacer.placeBlockInGrid(player, grid, result, blockItem);
            return true;
        }

        return false;
    }

    public GridBlockEntityInfo getGridBlockEntityPlayerIsLookingAt(PlayerEntity player) {
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        LocalGrid grid = detectionService.getGridPlayerIsLookingAt(player);
        if (grid == null) {
            return null;
        }

        GridRaycastResult result = raycastEngine.performGridRaycast(eyePos, reachPoint, grid);
        if (result == null) {
            return null;
        }

        BlockPos blockPos = result.getBlockPosition();
        LocalBlock localBlock = grid.getBlocks().get(blockPos);
        if (localBlock == null) {
            return null;
        }

        if (blockEntityHandler.isBlockEntityType(localBlock.getState().getBlock())) {
            return new GridBlockEntityInfo(grid, blockPos, localBlock.getState(), result);
        }

        return null;
    }

    /**
     * Delegation method for backward compatibility.
     * Finds the LocalGrid the player is looking at.
     */
    public LocalGrid getGridPlayerIsLookingAt(PlayerEntity player) {
        return detectionService.getGridPlayerIsLookingAt(player);
    }

    /**
     * Public access to raycast engine for other classes that might need it.
     */
    public GridRaycastResult performGridRaycast(Vec3d startWorld, Vec3d endWorld, LocalGrid grid) {
        return raycastEngine.performGridRaycast(startWorld, endWorld, grid);
    }

    public static class GridBlockEntityInfo {
        public final LocalGrid grid;
        public final BlockPos gridLocalPos;
        public final net.minecraft.block.BlockState blockState;
        public final GridRaycastResult raycastResult;

        public GridBlockEntityInfo(LocalGrid grid, BlockPos gridLocalPos,
                                   net.minecraft.block.BlockState blockState, GridRaycastResult raycastResult) {
            this.grid = grid;
            this.gridLocalPos = gridLocalPos;
            this.blockState = blockState;
            this.raycastResult = raycastResult;
        }
    }

    /**
     * Handles block breaking on grids.
     */
    public boolean handleGridBlockBreaking(PlayerEntity player, BlockPos pos, Direction direction) {
        LocalGrid grid = detectionService.getGridPlayerIsLookingAt(player);
        if (grid == null) {
            return false;
        }

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        GridRaycastResult result = raycastEngine.performGridRaycast(eyePos, reachPoint, grid);
        if (result != null) {
            return blockBreaker.breakBlockInGrid(player, grid, result.getBlockPosition());
        }

        return false;
    }

    /**
     * Checks if the player is targeting a grid block for breaking.
     */
    public boolean isTargetingGridBlock(PlayerEntity player) {
        LocalGrid grid = detectionService.getGridPlayerIsLookingAt(player);
        if (grid == null) {
            return false;
        }

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        GridRaycastResult result = raycastEngine.performGridRaycast(eyePos, reachPoint, grid);
        return result != null;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}