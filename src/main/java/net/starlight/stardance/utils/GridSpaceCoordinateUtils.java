package net.starlight.stardance.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.starlight.stardance.core.LocalGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * VS2-style coordinate utilities for handling raycast edge cases.
 * Solves the "off by 1" problem when raycasting hits block faces.
 */
public class GridSpaceCoordinateUtils implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    /**
     * Find the actual grid block at or near the given GridSpace position.
     * Checks adjacent positions to handle raycast edge cases.
     * 
     * This is the VS2 approach to handle "hit block face vs actual block" issues.
     */
    public static Optional<GridSpaceBlockResult> findActualGridBlock(BlockPos gridSpacePos, Level world) {
        try {
            // 1. First try the exact position
            Optional<TransformationAPI.GridSpaceTransformResult> directResult = 
                TransformationAPI.getInstance().detectGridSpacePosition(gridSpacePos, world);
                
            if (directResult.isPresent()) {
                LocalGrid grid = directResult.get().grid;
                BlockPos gridLocalPos = directResult.get().gridLocalPos;
                
                if (grid.hasBlock(gridLocalPos)) {
                    SLogger.log("GridSpaceCoordinateUtils", 
                        "Found block at exact position: " + gridSpacePos + " -> " + gridLocalPos);
                    return Optional.of(new GridSpaceBlockResult(grid, gridLocalPos, gridSpacePos, directResult.get()));
                }
            }
            
            // 2. Check all 6 adjacent positions (VS2 approach)
            List<BlockPos> adjacentPositions = getAdjacentPositions(gridSpacePos);
            
            for (BlockPos adjacentPos : adjacentPositions) {
                Optional<TransformationAPI.GridSpaceTransformResult> adjacentResult = 
                    TransformationAPI.getInstance().detectGridSpacePosition(adjacentPos, world);
                    
                if (adjacentResult.isPresent()) {
                    LocalGrid grid = adjacentResult.get().grid;
                    BlockPos gridLocalPos = adjacentResult.get().gridLocalPos;
                    
                    if (grid.hasBlock(gridLocalPos)) {
                        SLogger.log("GridSpaceCoordinateUtils", 
                            String.format("Found block at adjacent position: %s -> %s (grid-local: %s)", 
                                gridSpacePos, adjacentPos, gridLocalPos));
                        return Optional.of(new GridSpaceBlockResult(grid, gridLocalPos, adjacentPos, adjacentResult.get()));
                    }
                }
            }
            
            SLogger.log("GridSpaceCoordinateUtils", 
                "No block found at " + gridSpacePos + " or any adjacent positions");
            return Optional.empty();
            
        } catch (Exception e) {
            SLogger.log("GridSpaceCoordinateUtils", 
                "Error finding grid block: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all 6 adjacent positions (north, south, east, west, up, down).
     */
    private static List<BlockPos> getAdjacentPositions(BlockPos center) {
        List<BlockPos> adjacent = new ArrayList<>();
        
        for (Direction direction : Direction.values()) {
            adjacent.add(center.relative(direction));
        }
        
        return adjacent;
    }

    /**
     * Result of finding a grid block, including the actual coordinates found.
     */
    public static class GridSpaceBlockResult {
        public final LocalGrid grid;
        public final BlockPos gridLocalPos;
        public final BlockPos actualGridSpacePos;  // The GridSpace position where block was actually found
        public final TransformationAPI.GridSpaceTransformResult transformResult;

        public GridSpaceBlockResult(LocalGrid grid, BlockPos gridLocalPos, BlockPos actualGridSpacePos, 
                                   TransformationAPI.GridSpaceTransformResult transformResult) {
            this.grid = grid;
            this.gridLocalPos = gridLocalPos;
            this.actualGridSpacePos = actualGridSpacePos;
            this.transformResult = transformResult;
        }

        @Override
        public String toString() {
            return String.format("GridSpaceBlockResult{gridLocal=%s, gridSpace=%s, grid=%s}", 
                gridLocalPos, actualGridSpacePos, grid.getGridId().toString().substring(0, 8));
        }
    }
}