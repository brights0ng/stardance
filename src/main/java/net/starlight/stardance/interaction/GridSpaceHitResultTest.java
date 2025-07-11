package net.starlight.stardance.interaction;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

/**
 * Test class for GridSpace HitResult system.
 * Use this to verify the coordinate transformations and HitResult wrapping works correctly.
 */
public class GridSpaceHitResultTest {
    
    /**
     * Test method - call this from a command or debug item.
     */
    public static void testHitResultSystem(ServerWorld world) {
        SLogger.log("GridSpaceHitResultTest", "=== TESTING GRIDSPACE HITRESULT SYSTEM ===");
        
        // Test 1: Create a vanilla hit result and try to convert it
        testVanillaToGridSpaceConversion(world);
        
        // Test 2: Test hit result detection methods
        testHitResultDetection(world);
        
        // Test 3: Test coordinate extraction
        testCoordinateExtraction(world);
        
        SLogger.log("GridSpaceHitResultTest", "=== TESTING COMPLETE ===");
    }
    
    private static void testVanillaToGridSpaceConversion(ServerWorld world) {
        SLogger.log("GridSpaceHitResultTest", "Test 1: Vanilla to GridSpace conversion...");
        
        // Create a test vanilla BlockHitResult
        Vec3d testWorldPos = new Vec3d(100, 64, 100); // Adjust to where you have a grid
        BlockPos testBlockPos = new BlockPos((int)testWorldPos.x, (int)testWorldPos.y, (int)testWorldPos.z);
        BlockHitResult vanillaHit = new BlockHitResult(testWorldPos, Direction.UP, testBlockPos, false);
        
        SLogger.log("GridSpaceHitResultTest", "Created vanilla hit result: " + vanillaHit.getPos());
        
        // Try to convert to GridSpace
        HitResult converted = GridSpaceHitResultFactory.convertToGridSpaceHitResult(vanillaHit, world);
        
        if (GridSpaceHitResultFactory.isGridSpaceHitResult(converted)) {
            SLogger.log("GridSpaceHitResultTest", "✅ Successfully converted to GridSpace hit result");
            GridSpaceHitResultFactory.debugHitResult(converted, "CONVERTED");
            
            // Test conversion back to vanilla
            HitResult backToVanilla = GridSpaceHitResultFactory.convertToVanillaHitResult(converted, false);
            SLogger.log("GridSpaceHitResultTest", "✅ Converted back to vanilla: " + backToVanilla.getPos());
            
        } else {
            SLogger.log("GridSpaceHitResultTest", "ℹ️ No grid found at test position - this is normal if no grid exists there");
        }
    }
    
    private static void testHitResultDetection(ServerWorld world) {
        SLogger.log("GridSpaceHitResultTest", "Test 2: Hit result detection...");
        
        // Create test hit results
        Vec3d testPos = new Vec3d(50, 64, 50);
        BlockHitResult vanillaHit = new BlockHitResult(testPos, Direction.UP, BlockPos.ofFloored(testPos), false);
        
        // Test detection methods
        boolean isGridSpace = GridSpaceHitResultFactory.isGridSpaceHitResult(vanillaHit);
        boolean isGridBlock = GridSpaceHitResultFactory.isGridSpaceBlockHitResult(vanillaHit);
        LocalGrid grid = GridSpaceHitResultFactory.getGridFromHitResult(vanillaHit);
        
        SLogger.log("GridSpaceHitResultTest", "Vanilla hit result detection:");
        SLogger.log("GridSpaceHitResultTest", "  isGridSpace: " + isGridSpace);
        SLogger.log("GridSpaceHitResultTest", "  isGridBlock: " + isGridBlock);
        SLogger.log("GridSpaceHitResultTest", "  grid: " + (grid != null ? grid.getGridId() : "null"));
        
        // Try to convert and test again
        HitResult converted = GridSpaceHitResultFactory.convertToGridSpaceHitResult(vanillaHit, world);
        if (converted != vanillaHit) {
            SLogger.log("GridSpaceHitResultTest", "GridSpace hit result detection:");
            SLogger.log("GridSpaceHitResultTest", "  isGridSpace: " + GridSpaceHitResultFactory.isGridSpaceHitResult(converted));
            SLogger.log("GridSpaceHitResultTest", "  isGridBlock: " + GridSpaceHitResultFactory.isGridSpaceBlockHitResult(converted));
            SLogger.log("GridSpaceHitResultTest", "  grid: " + GridSpaceHitResultFactory.getGridFromHitResult(converted));
        }
    }
    
    private static void testCoordinateExtraction(ServerWorld world) {
        SLogger.log("GridSpaceHitResultTest", "Test 3: Coordinate extraction...");
        
        Vec3d testPos = new Vec3d(75, 64, 75);
        BlockHitResult vanillaHit = new BlockHitResult(testPos, Direction.UP, BlockPos.ofFloored(testPos), false);
        
        // Test coordinate extraction from vanilla hit
        Vec3d worldCoords = GridSpaceHitResultFactory.getWorldCoordinates(vanillaHit);
        Vec3d gridSpaceCoords = GridSpaceHitResultFactory.getGridSpaceCoordinates(vanillaHit);
        
        SLogger.log("GridSpaceHitResultTest", "Vanilla hit coordinates:");
        SLogger.log("GridSpaceHitResultTest", "  World: " + worldCoords);
        SLogger.log("GridSpaceHitResultTest", "  GridSpace: " + gridSpaceCoords);
        
        // Convert and test coordinate extraction from GridSpace hit
        HitResult converted = GridSpaceHitResultFactory.convertToGridSpaceHitResult(vanillaHit, world);
        if (converted != vanillaHit) {
            Vec3d convertedWorldCoords = GridSpaceHitResultFactory.getWorldCoordinates(converted);
            Vec3d convertedGridSpaceCoords = GridSpaceHitResultFactory.getGridSpaceCoordinates(converted);
            Vec3d blockCoords = GridSpaceHitResultFactory.getGridSpaceBlockCoordinates(converted);
            
            SLogger.log("GridSpaceHitResultTest", "GridSpace hit coordinates:");
            SLogger.log("GridSpaceHitResultTest", "  World: " + convertedWorldCoords);
            SLogger.log("GridSpaceHitResultTest", "  GridSpace: " + convertedGridSpaceCoords);
            SLogger.log("GridSpaceHitResultTest", "  GridSpace Block: " + blockCoords);
        }
    }
    
    /**
     * Test with a specific grid to verify the system works with known grids.
     */
    public static void testWithSpecificGrid(LocalGrid grid, Vec3d worldPos) {
        SLogger.log("GridSpaceHitResultTest", "=== TESTING WITH SPECIFIC GRID ===");
        SLogger.log("GridSpaceHitResultTest", "Grid: " + grid.getGridId());
        SLogger.log("GridSpaceHitResultTest", "World position: " + worldPos);
        
        // Create a hit result at the world position
        BlockHitResult vanillaHit = new BlockHitResult(
            worldPos, 
            Direction.UP, 
            BlockPos.ofFloored(worldPos), 
            false
        );
        
        // Convert to GridSpace
        HitResult converted = GridSpaceHitResultFactory.convertToGridSpaceHitResult(vanillaHit, grid.getWorld());
        
        if (GridSpaceHitResultFactory.isGridSpaceHitResult(converted)) {
            SLogger.log("GridSpaceHitResultTest", "✅ Successfully created GridSpace hit result");
            
            LocalGrid extractedGrid = GridSpaceHitResultFactory.getGridFromHitResult(converted);
            if (extractedGrid != null && extractedGrid.equals(grid)) {
                SLogger.log("GridSpaceHitResultTest", "✅ Grid extraction successful");
            } else {
                SLogger.log("GridSpaceHitResultTest", "❌ Grid extraction failed");
            }
            
            // Test coordinate accuracy
            Vec3d worldCoords = GridSpaceHitResultFactory.getWorldCoordinates(converted);
            double error = worldPos.distanceTo(worldCoords);
            
            SLogger.log("GridSpaceHitResultTest", "Coordinate accuracy: " + error + " blocks error");
            if (error < 0.1) {
                SLogger.log("GridSpaceHitResultTest", "✅ Coordinate accuracy test PASSED");
            } else {
                SLogger.log("GridSpaceHitResultTest", "❌ Coordinate accuracy test FAILED");
            }
            
        } else {
            SLogger.log("GridSpaceHitResultTest", "❌ Failed to convert to GridSpace hit result");
        }
        
        SLogger.log("GridSpaceHitResultTest", "=== SPECIFIC GRID TEST COMPLETE ===");
    }
}