package net.starlight.stardance.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit testing utilities for coordinate transformation validation.
 * Helps ensure our transformation math is correct before in-game testing.
 */
public class TransformationTestUtils implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Runs a comprehensive test suite for coordinate transformations.
     */
    public static void runTransformationTests(LocalGrid testGrid) {
        SLogger.log("TransformationTestUtils", "=== STARTING COORDINATE TRANSFORMATION TESTS ===");
        
        List<TestResult> results = new ArrayList<>();
        
        // Test 1: Basic coordinate transformation
        results.add(testBasicTransformation(testGrid));
        
        // Test 2: Round-trip transformation accuracy
        results.add(testRoundTripAccuracy(testGrid));
        
        // Test 3: Edge cases and boundaries
        results.add(testEdgeCases(testGrid));
        
        // Test 4: Performance under load
        results.add(testPerformance(testGrid));
        
        // Print summary
        printTestSummary(results);
        
        SLogger.log("TransformationTestUtils", "=== TRANSFORMATION TESTS COMPLETE ===");
    }

    /**
     * Tests basic coordinate transformation functionality.
     */
    private static TestResult testBasicTransformation(LocalGrid grid) {
        try {
            // Test known positions
            Vec3[] testPositions = {
                new Vec3(0, 0, 0),           // Origin
                new Vec3(1, 1, 1),           // Positive
                new Vec3(-1, -1, -1),        // Negative
                new Vec3(0.5, 0.5, 0.5),     // Fractional
            };
            
            for (Vec3 worldPos : testPositions) {
                // Test world -> GridSpace transformation
                var transform = TransformationAPI.getInstance().worldToGridSpace(worldPos, grid.getWorld());
                
                if (transform.isPresent()) {
                    var result = transform.get();
                    
                    // Validate result structure
                    if (result.grid == null || result.gridSpacePos == null || result.gridSpaceVec == null) {
                        return new TestResult("Basic Transformation", false, 
                            "Null values in transformation result for " + worldPos);
                    }
                    
                    // Check that GridSpace coordinates are reasonable
                    if (!isReasonableGridSpaceCoordinate(result.gridSpacePos)) {
                        return new TestResult("Basic Transformation", false,
                            "Unreasonable GridSpace coordinates: " + result.gridSpacePos);
                    }
                }
            }
            
            return new TestResult("Basic Transformation", true, "All basic transformations successful");
            
        } catch (Exception e) {
            return new TestResult("Basic Transformation", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Tests round-trip transformation accuracy (world -> GridSpace -> world).
     */
    private static TestResult testRoundTripAccuracy(LocalGrid grid) {
        try {
            Vec3[] testPositions = {
                new Vec3(0, 64, 0),
                new Vec3(10, 70, 5),
                new Vec3(-5, 60, -10),
            };
            
            double maxError = 0.0;
            double errorThreshold = 0.001; // 1mm tolerance
            
            for (Vec3 originalPos : testPositions) {
                // World -> GridSpace
                var transform = TransformationAPI.getInstance().worldToGridSpace(originalPos, grid.getWorld());
                
                if (transform.isPresent()) {
                    var result = transform.get();
                    
                    // GridSpace -> World  
                    Vec3 backToWorld = TransformationAPI.getInstance().gridSpaceToWorld(result.gridSpacePos, result.grid);
                    
                    // Calculate error
                    double error = originalPos.distanceTo(backToWorld);
                    maxError = Math.max(maxError, error);
                    
                    if (error > errorThreshold) {
                        return new TestResult("Round-trip Accuracy", false,
                            String.format("High error: %.6f blocks (threshold: %.6f) for position %s", 
                                error, errorThreshold, originalPos));
                    }
                }
            }
            
            return new TestResult("Round-trip Accuracy", true,
                String.format("Max error: %.6f blocks (threshold: %.6f)", maxError, errorThreshold));
                
        } catch (Exception e) {
            return new TestResult("Round-trip Accuracy", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Tests edge cases and boundary conditions.
     */
    private static TestResult testEdgeCases(LocalGrid grid) {
        try {
            // Test positions that might cause issues
            Vec3[] edgeCases = {
                new Vec3(Double.MAX_VALUE, 0, 0),           // Extreme values
                new Vec3(0, Double.MAX_VALUE, 0),
                new Vec3(0, 0, Double.MAX_VALUE),
                new Vec3(Double.NaN, 0, 0),                 // NaN values
                new Vec3(0, Double.NaN, 0),
                new Vec3(Double.POSITIVE_INFINITY, 0, 0),   // Infinity values
            };
            
            for (Vec3 edgeCase : edgeCases) {
                try {
                    var transform = TransformationAPI.getInstance().worldToGridSpace(edgeCase, grid.getWorld());
                    // Should either work or gracefully fail, not crash
                } catch (Exception e) {
                    // Some edge cases are expected to fail gracefully
                    if (!(e instanceof IllegalArgumentException || e instanceof ArithmeticException)) {
                        return new TestResult("Edge Cases", false, 
                            "Unexpected exception for " + edgeCase + ": " + e.getMessage());
                    }
                }
            }
            
            return new TestResult("Edge Cases", true, "All edge cases handled gracefully");
            
        } catch (Exception e) {
            return new TestResult("Edge Cases", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Tests performance under load.
     */
    private static TestResult testPerformance(LocalGrid grid) {
        try {
            int iterations = 1000;
            Vec3 testPos = new Vec3(5, 65, 5);
            
            long startTime = System.nanoTime();
            
            for (int i = 0; i < iterations; i++) {
                TransformationAPI.getInstance().worldToGridSpace(testPos, grid.getWorld());
            }
            
            long endTime = System.nanoTime();
            double avgTimeMs = (endTime - startTime) / 1_000_000.0 / iterations;
            
            // Should be under 1ms per transformation
            double threshold = 1.0;
            
            if (avgTimeMs > threshold) {
                return new TestResult("Performance", false,
                    String.format("Too slow: %.3f ms/transformation (threshold: %.1f ms)", avgTimeMs, threshold));
            }
            
            return new TestResult("Performance", true,
                String.format("Average time: %.3f ms/transformation", avgTimeMs));
                
        } catch (Exception e) {
            return new TestResult("Performance", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Checks if GridSpace coordinates are within reasonable bounds.
     */
    private static boolean isReasonableGridSpaceCoordinate(BlockPos pos) {
        // GridSpace should be around 25M+ coordinates
        int minReasonable = 20_000_000;
        int maxReasonable = 30_000_000;
        
        return pos.getX() >= minReasonable && pos.getX() <= maxReasonable &&
               pos.getZ() >= minReasonable && pos.getZ() <= maxReasonable &&
               pos.getY() >= 0 && pos.getY() <= 512;
    }

    /**
     * Prints a summary of all test results.
     */
    private static void printTestSummary(List<TestResult> results) {
        SLogger.log("TransformationTestUtils", "=== TEST SUMMARY ===");
        
        int passed = 0;
        int failed = 0;
        
        for (TestResult result : results) {
            String status = result.passed ? "§a✓ PASS" : "§c✗ FAIL";
            SLogger.log("TransformationTestUtils", status + " §f" + result.testName + ": " + result.message);
            
            if (result.passed) {
                passed++;
            } else {
                failed++;
            }
        }
        
        SLogger.log("TransformationTestUtils", String.format("Results: %d passed, %d failed", passed, failed));
        
        if (failed > 0) {
            SLogger.log("TransformationTestUtils", "§c⚠ Some tests failed! Check coordinate transformation implementation.");
        } else {
            SLogger.log("TransformationTestUtils", "§a✓ All tests passed! Coordinate transformation is working correctly.");
        }
    }

    /**
     * Container for test results.
     */
    private static class TestResult {
        final String testName;
        final boolean passed;
        final String message;

        TestResult(String testName, boolean passed, String message) {
            this.testName = testName;
            this.passed = passed;
            this.message = message;
        }
    }
}