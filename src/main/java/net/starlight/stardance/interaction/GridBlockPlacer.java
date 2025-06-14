package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FurnaceBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;

/**
 * Handles block placement on grids.
 */
public class GridBlockPlacer implements ILoggingControl {

    public void placeBlockInGrid(PlayerEntity player, LocalGrid grid,
                                GridRaycastResult result, BlockItem blockItem) {
        BlockPos hitPosition = result.getBlockPosition();
        Direction hitFace = result.getHitFace();
        BlockPos placePosition = hitPosition.offset(hitFace);

        SLogger.log(this, "Attempting to place block at position: " + placePosition + ", hit face: " + hitFace);

        if (!grid.getBlocks().containsKey(placePosition)) {
            BlockState blockState = blockItem.getBlock().getDefaultState();
            LocalBlock localBlock = new LocalBlock(placePosition, blockState);

            createBlockEntityIfNeeded(localBlock, grid, placePosition);
            
            grid.addBlock(localBlock);
            playBlockPlacementSound(player, grid, placePosition, blockState);

            SLogger.log(this, "Placed block at position: " + placePosition);

            if (!player.isCreative()) {
                player.getStackInHand(Hand.MAIN_HAND).decrement(1);
            }
        } else {
            player.sendMessage(Text.literal("Cannot place block here"), true);
            SLogger.log(this, "Cannot place block at position: " + placePosition + " - position already occupied.");
        }
    }

    private void createBlockEntityIfNeeded(LocalBlock localBlock, LocalGrid grid, BlockPos placePosition) {
        Block block = localBlock.getState().getBlock();
        
        if (block instanceof FurnaceBlock) {
            GridFurnaceBlockEntity furnaceEntity = new GridFurnaceBlockEntity(grid, placePosition);
            localBlock.setBlockEntity(furnaceEntity);
            SLogger.log(this, "Created furnace block entity for new furnace at " + placePosition);
        } else if (block instanceof ChestBlock) {
            GridChestBlockEntity chestEntity = new GridChestBlockEntity(grid, placePosition);
            localBlock.setBlockEntity(chestEntity);
            SLogger.log(this, "Created chest block entity for new chest at " + placePosition);
        }
    }

    private void playBlockPlacementSound(PlayerEntity player, LocalGrid grid, BlockPos gridLocalPos, BlockState blockState) {
        try {
            BlockSoundGroup soundGroup = blockState.getSoundGroup();
            SoundEvent placeSound = soundGroup.getPlaceSound();

            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double worldX = gridWorldPos.x + gridLocalPos.getX() + 0.5;
            double worldY = gridWorldPos.y + gridLocalPos.getY() + 0.5;
            double worldZ = gridWorldPos.z + gridLocalPos.getZ() + 0.5;

            player.getWorld().playSound(
                    null,
                    worldX, worldY, worldZ,
                    placeSound,
                    SoundCategory.BLOCKS,
                    soundGroup.getVolume(),
                    soundGroup.getPitch()
            );

            SLogger.log(this, "Played placement sound for " + blockState.getBlock().getClass().getSimpleName());

        } catch (Exception e) {
            SLogger.log(this, "Failed to play block placement sound: " + e.getMessage());
        }
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}