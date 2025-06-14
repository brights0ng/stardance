package net.starlight.stardance.interaction;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Set;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Service for detecting grids that players are looking at.
 */
public class GridDetectionService implements ILoggingControl {
    
    private static final float playerReachDistance = 4.5f;

    public LocalGrid getGridPlayerIsLookingAt(PlayerEntity player) {
        Set<LocalGrid> grids = engineManager.getEngine(player.getWorld()).getGrids();

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        for (LocalGrid grid : grids) {
            if (gridIntersectsRay(grid, eyePos, reachPoint)) {
                SLogger.log(this, "Found grid in player's view: " + grid);
                return grid;
            }
        }
        return null;
    }

    private static boolean gridIntersectsRay(LocalGrid grid, Vec3d start, Vec3d end) {
        Vector3f minAabb = new Vector3f();
        Vector3f maxAabb = new Vector3f();
        grid.getAABB(minAabb, maxAabb);

        Box gridBox = new Box(minAabb.x, minAabb.y, minAabb.z,
                maxAabb.x, maxAabb.y, maxAabb.z);

        return gridBox.intersects(start, end);
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}