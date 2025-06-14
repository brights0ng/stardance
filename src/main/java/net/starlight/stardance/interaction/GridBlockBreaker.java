package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.List;

/**
 * Handles block breaking on grids.
 */
public class GridBlockBreaker implements ILoggingControl {

    public boolean breakBlockInGrid(PlayerEntity player, LocalGrid grid, BlockPos gridLocalPos) {
        LocalBlock localBlock = grid.getBlocks().get(gridLocalPos);
        if (localBlock == null) {
            SLogger.log(this, "No block found at position: " + gridLocalPos);
            return false;
        }

        BlockState blockState = localBlock.getState();
        
        SLogger.log(this, "Breaking block " + blockState.getBlock().getClass().getSimpleName() + 
                   " at position: " + gridLocalPos);

        // Check if player can break this block
        if (!canPlayerBreakBlock(player, blockState)) {
            SLogger.log(this, "Player cannot break this block");
            return false;
        }

        // Play breaking sound
        playBlockBreakingSound(player, grid, gridLocalPos, blockState);

        // Drop items if not in creative mode
        if (!player.isCreative()) {
            dropBlockItems(player, grid, gridLocalPos, blockState);
        }

        // Handle block entity cleanup
        if (localBlock.hasBlockEntity()) {
            handleBlockEntityBreaking(localBlock, player, grid, gridLocalPos);
        }

        // Remove the block from the grid
        grid.removeBlock(gridLocalPos);

        SLogger.log(this, "Successfully broke block at position: " + gridLocalPos);
        return true;
    }

    private boolean canPlayerBreakBlock(PlayerEntity player, BlockState blockState) {
        // In creative mode, players can break almost anything
        if (player.isCreative()) {
            return true;
        }

        // Check if the block is unbreakable
        if (blockState.getHardness(null, null) < 0) {
            return false;
        }

        // Check if player has the right tool (simplified)
        // In a full implementation, you'd check tool effectiveness
        return true;
    }

    private void playBlockBreakingSound(PlayerEntity player, LocalGrid grid, BlockPos gridLocalPos, BlockState blockState) {
        try {
            BlockSoundGroup soundGroup = blockState.getSoundGroup();
            SoundEvent breakSound = soundGroup.getBreakSound();

            // Calculate world position where the sound should play
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double worldX = gridWorldPos.x + gridLocalPos.getX() + 0.5;
            double worldY = gridWorldPos.y + gridLocalPos.getY() + 0.5;
            double worldZ = gridWorldPos.z + gridLocalPos.getZ() + 0.5;

            // Play sound for all nearby players
            player.getWorld().playSound(
                    null,
                    worldX, worldY, worldZ,
                    breakSound,
                    SoundCategory.BLOCKS,
                    soundGroup.getVolume(),
                    soundGroup.getPitch()
            );

            SLogger.log(this, "Played breaking sound for " + blockState.getBlock().getClass().getSimpleName());

        } catch (Exception e) {
            SLogger.log(this, "Failed to play block breaking sound: " + e.getMessage());
        }
    }

    private void dropBlockItems(PlayerEntity player, LocalGrid grid, BlockPos gridLocalPos, BlockState blockState) {
        try {
            // Calculate world position for drops
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double worldX = gridWorldPos.x + gridLocalPos.getX() + 0.5;
            double worldY = gridWorldPos.y + gridLocalPos.getY() + 0.5;
            double worldZ = gridWorldPos.z + gridLocalPos.getZ() + 0.5;

            BlockPos worldPos = new BlockPos((int)worldX, (int)worldY, (int)worldZ);

            // Create loot context for proper drop calculation
            if (player.getWorld() instanceof ServerWorld serverWorld) {
                LootContextParameterSet.Builder builder = new LootContextParameterSet.Builder(serverWorld)
                        .add(LootContextParameters.ORIGIN, new Vec3d(worldX, worldY, worldZ))
                        .add(LootContextParameters.TOOL, player.getMainHandStack())
                        .addOptional(LootContextParameters.THIS_ENTITY, player)
                        .addOptional(LootContextParameters.BLOCK_ENTITY, null);

                // Get the drops from the block's loot table
                List<ItemStack> drops = blockState.getDroppedStacks(builder);

                // Spawn the dropped items in the world
                for (ItemStack drop : drops) {
                    Block.dropStack(serverWorld, worldPos, drop);
                }

                SLogger.log(this, "Dropped " + drops.size() + " items for broken block");
            }

        } catch (Exception e) {
            SLogger.log(this, "Failed to drop block items: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleBlockEntityBreaking(LocalBlock localBlock, PlayerEntity player, LocalGrid grid, BlockPos gridLocalPos) {
        Object blockEntity = localBlock.getBlockEntity();

        // Calculate world position for drops
        Vector3f gridWorldPos = new Vector3f();
        Transform gridTransform = new Transform();
        grid.getRigidBody().getWorldTransform(gridTransform);
        gridTransform.origin.get(gridWorldPos);

        double worldX = gridWorldPos.x + gridLocalPos.getX() + 0.5;
        double worldY = gridWorldPos.y + gridLocalPos.getY() + 0.5;
        double worldZ = gridWorldPos.z + gridLocalPos.getZ() + 0.5;
        BlockPos worldPos = new BlockPos((int)worldX, (int)worldY, (int)worldZ);

        if (blockEntity instanceof GridFurnaceBlockEntity furnaceEntity) {
            // Drop furnace contents
            dropFurnaceContents(furnaceEntity, player.getWorld(), worldPos);
            SLogger.log(this, "Dropped furnace contents");
        } else if (blockEntity instanceof GridChestBlockEntity chestEntity) {
            // Drop chest contents
            dropChestContents(chestEntity, player.getWorld(), worldPos);
            SLogger.log(this, "Dropped chest contents");
        }

        // Clear the block entity reference
        localBlock.setBlockEntity(null);
    }

    private void dropFurnaceContents(GridFurnaceBlockEntity furnaceEntity, World world, BlockPos pos) {
        // Get all inventory slots and drop their contents
        for (int i = 0; i < furnaceEntity.size(); i++) {
            ItemStack stack = furnaceEntity.getStack(i);
            if (!stack.isEmpty()) {
                Block.dropStack(world, pos, stack);
            }
        }
        // Clear the inventory
        furnaceEntity.clear();
    }

    private void dropChestContents(GridChestBlockEntity chestEntity, World world, BlockPos pos) {
        // Get all inventory slots and drop their contents
        for (int i = 0; i < chestEntity.size(); i++) {
            ItemStack stack = chestEntity.getStack(i);
            if (!stack.isEmpty()) {
                Block.dropStack(world, pos, stack);
            }
        }
        // Clear the inventory
        chestEntity.clear();
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}