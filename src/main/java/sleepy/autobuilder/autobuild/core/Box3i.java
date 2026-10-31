package sleepy.autobuilder.autobuild.core;

/**
 * Immutable inclusive integer box. Pure Java so it can be unit tested without Minecraft.
 */
public record Box3i(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Box3i {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min must be <= max");
        }
    }

    public static Box3i of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box3i(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        );
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * (long) sizeY() * (long) sizeZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public Box3i intersect(Box3i other) {
        int x1 = Math.max(minX, other.minX);
        int y1 = Math.max(minY, other.minY);
        int z1 = Math.max(minZ, other.minZ);
        int x2 = Math.min(maxX, other.maxX);
        int y2 = Math.min(maxY, other.maxY);
        int z2 = Math.min(maxZ, other.maxZ);
        if (x1 > x2 || y1 > y2 || z1 > z2) return null;
        return new Box3i(x1, y1, z1, x2, y2, z2);
    }

    public Box3i withMinY(int y) {
        return new Box3i(minX, y, minZ, maxX, maxY, maxZ);
    }

    public Box3i withMaxY(int y) {
        return new Box3i(minX, minY, minZ, maxX, y, maxZ);
    }

    public Box3i offset(int dx, int dy, int dz) {
        return new Box3i(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
