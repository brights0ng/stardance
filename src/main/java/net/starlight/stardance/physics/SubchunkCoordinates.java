package net.starlight.stardance.physics;

import java.util.Objects;

public class SubchunkCoordinates {
    public final int x;
    public final int y;
    public final int z;

    public SubchunkCoordinates(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // Override equals() and hashCode() for use in HashMaps and Sets
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SubchunkCoordinates)) return false;
        SubchunkCoordinates other = (SubchunkCoordinates) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }
}
