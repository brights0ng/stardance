package net.starlight.stardance.physics;

import javax.vecmath.Vector3f;

public class WorldBlockMarker {
    private final Vector3f center;

    public WorldBlockMarker(Vector3f center) {
        this.center = center;
    }

    public Vector3f getCenter() {
        return center;
    }
}
