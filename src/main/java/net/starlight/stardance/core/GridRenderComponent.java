package net.starlight.stardance.core;

import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * Manages rendering state for a LocalGrid.
 * Handles position and rotation history for smooth rendering interpolation.
 * This class is package-private - external code should use LocalGrid instead.
 */
class GridRenderComponent {
    // ----------------------------------------------
    // RENDER STATE
    // ----------------------------------------------
    private Vector3f renderPrevPosition = new Vector3f();
    private Vector3f renderCurrentPosition = new Vector3f();
    private Quat4f renderPrevRotation = new Quat4f(0, 0, 0, 1);
    private Quat4f renderCurrentRotation = new Quat4f(0, 0, 0, 1);

    // Used to track when we've had a physics update that should trigger a render state update
    private boolean physicsUpdatedSinceLastRender = false;
    private final LocalGrid grid;

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new GridRenderComponent.
     */
    GridRenderComponent(LocalGrid grid) {
        this.grid = grid;

        // Initialize with identity rotation
        renderPrevRotation.set(0, 0, 0, 1);
        renderCurrentRotation.set(0, 0, 0, 1);
    }

    // ----------------------------------------------
    // PUBLIC METHODS
    // ----------------------------------------------
    /**
     * Updates the render state from physics data.
     * This should be called whenever the physics state changes.
     *
     * @param rigidBody The rigid body to get transform from
     */
    public void updateRenderState(RigidBody rigidBody) {
        if (rigidBody == null) return;

        // Get current transform from physics
        Vector3f newPos = new Vector3f();
        Quat4f newRot = new Quat4f();

        rigidBody.getCenterOfMassPosition(newPos);
        rigidBody.getOrientation(newRot);

        // Check if position has changed significantly enough to update
        if (hasPositionChanged(renderCurrentPosition, newPos) || hasRotationChanged(renderCurrentRotation, newRot)) {
            // Shift current → previous
            renderPrevPosition.set(renderCurrentPosition);
            renderPrevRotation.set(renderCurrentRotation);

            // Update current with new values
            renderCurrentPosition.set(newPos);
            renderCurrentRotation.set(newRot);

            physicsUpdatedSinceLastRender = true;

            SLogger.log(grid, "Physics updated - prev: " + renderPrevPosition +
                    ", current: " + renderCurrentPosition);
        }
    }

    /**
     * Called after rendering to acknowledge that the updated state has been used.
     * This ensures proper synchronization between physics and rendering.
     */
    public void acknowledgeRenderUpdate() {
        physicsUpdatedSinceLastRender = false;
    }

    /**
     * Checks if physics has been updated since the last render.
     */
    public boolean hasPhysicsUpdatedSinceLastRender() {
        return physicsUpdatedSinceLastRender;
    }

    /**
     * Gets the interpolated transform for rendering.
     *
     * @param outTransform Transform to store the result
     * @param partialTicks Interpolation factor (0-1)
     */
    public void getInterpolatedTransform(Transform outTransform, float partialTicks) {
        // Interpolate position
        Vector3f interpolatedPos = new Vector3f();
        interpolatedPos.x = renderPrevPosition.x + (renderCurrentPosition.x - renderPrevPosition.x) * partialTicks;
        interpolatedPos.y = renderPrevPosition.y + (renderCurrentPosition.y - renderPrevPosition.y) * partialTicks;
        interpolatedPos.z = renderPrevPosition.z + (renderCurrentPosition.z - renderPrevPosition.z) * partialTicks;
        outTransform.origin.set(interpolatedPos);

        // Interpolate rotation
        Quat4f interpolatedRot = interpolateQuaternions(renderPrevRotation, renderCurrentRotation, partialTicks);
        outTransform.setRotation(interpolatedRot);
    }

    // ----------------------------------------------
    // PRIVATE METHODS
    // ----------------------------------------------

    /**
     * Interpolates between two quaternions.
     */
    private Quat4f interpolateQuaternions(Quat4f prevRot, Quat4f currRot, float alpha) {
        Quat4f out = new Quat4f();
        out.interpolate(prevRot, currRot, alpha);
        return out;
    }

    /**
     * Checks if position has changed significantly.
     */
    private boolean hasPositionChanged(Vector3f oldPos, Vector3f newPos) {
        float epsilon = 0.0001f;
        return Math.abs(oldPos.x - newPos.x) > epsilon ||
                Math.abs(oldPos.y - newPos.y) > epsilon ||
                Math.abs(oldPos.z - newPos.z) > epsilon;
    }

    /**
     * Checks if rotation has changed significantly.
     */
    private boolean hasRotationChanged(Quat4f oldRot, Quat4f newRot) {
        float epsilon = 0.0001f;
        return Math.abs(oldRot.x - newRot.x) > epsilon ||
                Math.abs(oldRot.y - newRot.y) > epsilon ||
                Math.abs(oldRot.z - newRot.z) > epsilon ||
                Math.abs(oldRot.w - newRot.w) > epsilon;
    }

    // ----------------------------------------------
    // GETTERS
    // ----------------------------------------------
    /**
     * Gets the previous position for rendering.
     */
    public Vector3f getRenderPrevPosition() {
        return new Vector3f(renderPrevPosition);  // Return a copy to prevent external modification
    }

    /**
     * Gets the current position for rendering.
     */
    public Vector3f getRenderCurrentPosition() {
        return new Vector3f(renderCurrentPosition);  // Return a copy to prevent external modification
    }

    /**
     * Gets the previous rotation for rendering.
     */
    public Quat4f getRenderPrevRotation() {
        Quat4f copy = new Quat4f();
        copy.set(renderPrevRotation);
        return copy;  // Return a copy to prevent external modification
    }

    /**
     * Gets the current rotation for rendering.
     */
    public Quat4f getRenderCurrentRotation() {
        Quat4f copy = new Quat4f();
        copy.set(renderCurrentRotation);
        return copy;  // Return a copy to prevent external modification
    }
}