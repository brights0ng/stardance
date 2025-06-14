package net.starlight.stardance.interaction;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import static net.starlight.stardance.interaction.GridChestScreenHandler.getOrCreateChestBlockEntity;
import static net.starlight.stardance.interaction.GridFurnaceScreenHandler.getOrCreateFurnaceBlockEntity;

/**
 * Handles interactions with block entities on grids.
 */
public class GridBlockEntityHandler implements ILoggingControl {

    public boolean handleBlockEntityInteraction(PlayerEntity player, Hand hand, 
                                               LocalGrid grid, BlockPos blockPos, 
                                               BlockState blockState, GridRaycastResult raycastResult) {
        
        Block block = blockState.getBlock();
        SLogger.log(this, "Interacting with block entity: " + block.getClass().getSimpleName() + " at " + blockPos);

        if (block instanceof CraftingTableBlock) {
            return handleCraftingTable(player, hand, grid, blockPos, blockState);
        } else if (block instanceof FurnaceBlock) {
            return handleFurnace(player, hand, grid, blockPos, blockState);
        } else if (block instanceof ChestBlock) {
            return handleChest(player, hand, grid, blockPos, blockState);
        } else if (block instanceof AnvilBlock) {
            return handleAnvil(player, hand, grid, blockPos, blockState);
        }

        return handleDefaultBlockInteraction(player, hand, grid, blockPos, blockState, raycastResult);
    }

    private boolean handleCraftingTable(PlayerEntity player, Hand hand, LocalGrid grid,
                                       BlockPos gridLocalPos, BlockState blockState) {
        try {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, playerEntity) -> 
                        new GridCraftingScreenHandler(syncId, inventory, grid, gridLocalPos),
                    Text.translatable("container.crafting")
            ));
            return true;
        } catch (Exception e) {
            SLogger.log(this, "ERROR in handleCraftingTable: " + e.getMessage());
            return false;
        }
    }

    private boolean handleFurnace(PlayerEntity player, Hand hand, LocalGrid grid,
                                 BlockPos gridLocalPos, BlockState blockState) {
        try {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, playerEntity) -> 
                        new GridFurnaceScreenHandler(syncId, inventory, grid, gridLocalPos, 
                            getOrCreateFurnaceBlockEntity(grid, gridLocalPos)),
                    Text.translatable("container.furnace")
            ));
            return true;
        } catch (Exception e) {
            SLogger.log(this, "ERROR in handleFurnace: " + e.getMessage());
            return false;
        }
    }

    private boolean handleChest(PlayerEntity player, Hand hand, LocalGrid grid,
                               BlockPos gridLocalPos, BlockState blockState) {
        try {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, playerEntity) -> 
                        new GridChestScreenHandler(syncId, inventory, grid, gridLocalPos, 
                            getOrCreateChestBlockEntity(grid, gridLocalPos)),
                    Text.translatable("container.chest")
            ));
            return true;
        } catch (Exception e) {
            SLogger.log(this, "ERROR in handleChest: " + e.getMessage());
            return false;
        }
    }

    private boolean handleAnvil(PlayerEntity player, Hand hand, LocalGrid grid,
                               BlockPos gridLocalPos, BlockState blockState) {
        try {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, playerEntity) -> 
                        new GridAnvilScreenHandler(syncId, inventory, grid, gridLocalPos),
                    Text.translatable("container.repair")
            ));
            return true;
        } catch (Exception e) {
            SLogger.log(this, "ERROR in handleAnvil: " + e.getMessage());
            return false;
        }
    }

    private boolean handleDefaultBlockInteraction(PlayerEntity player, Hand hand, LocalGrid grid,
                                                 BlockPos gridLocalPos, BlockState blockState,
                                                 GridRaycastResult raycastResult) {
        // Implement default block interaction logic here if needed
        return false;
    }

    public boolean isBlockEntityType(Block block) {
        return block instanceof CraftingTableBlock ||
                block instanceof FurnaceBlock ||
                block instanceof ChestBlock ||
                block instanceof AnvilBlock;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}