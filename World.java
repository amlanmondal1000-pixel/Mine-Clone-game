package com.mygame;

import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import java.util.*;

public class World {

    public static final int RENDER_DISTANCE = 12;
    public static final int MIN_RENDER_DISTANCE = 2;

    private static final int PRELOAD_MARGIN = 2;
    private static final int CHUNKS_PER_FRAME = 2;

    private final Node rootNode;

    private final Material grassTopMaterial;
    private final Material grassSideMaterial;
    private final Material dirtMaterial;
    private final Material stoneMaterial;
    private final Material woodMaterial;
    private final Material woodTopMaterial;
    private final Material leavesMaterial;

    private final Map<Long, Chunk> loadedChunks = new HashMap<>();

    private final ArrayDeque<ChunkPosition> chunkQueue = new ArrayDeque<>();

    private int renderDistance = RENDER_DISTANCE;

    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;

    public World(
            Node rootNode,
            Material grassTopMaterial,
            Material grassSideMaterial,
            Material dirtMaterial,
            Material stoneMaterial,
            Material woodMaterial,
            Material woodTopMaterial,
            Material leavesMaterial
    ) {
        this.rootNode = rootNode;

        this.grassTopMaterial = grassTopMaterial;
        this.grassSideMaterial = grassSideMaterial;
        this.dirtMaterial = dirtMaterial;
        this.stoneMaterial = stoneMaterial;
        this.woodMaterial = woodMaterial;
        this.woodTopMaterial = woodTopMaterial;
        this.leavesMaterial = leavesMaterial;
    }

    public void update(Vector3f playerPosition, boolean force) {

        // Math.floor so negative coordinates land in the correct chunk.
        final int cx = Math.floorDiv((int) Math.floor(playerPosition.x), Chunk.SIZE);
        final int cz = Math.floorDiv((int) Math.floor(playerPosition.z), Chunk.SIZE);

        if (!force && cx == centerChunkX && cz == centerChunkZ) {
            generateChunks();
            return;
        }

        centerChunkX = cx;
        centerChunkZ = cz;

        chunkQueue.clear();

        int radius = renderDistance + PRELOAD_MARGIN;

        List<ChunkPosition> toLoad = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {

                if (x * x + z * z > radius * radius) {
                    continue;
                }

                int chunkX = cx + x;
                int chunkZ = cz + z;

                if (!loadedChunks.containsKey(getKey(chunkX, chunkZ))) {
                    toLoad.add(new ChunkPosition(chunkX, chunkZ));
                }
            }
        }

        // Nearest chunks first, so the chunk under the player loads immediately.
        toLoad.sort(Comparator.comparingInt(
                p -> (p.x - cx) * (p.x - cx) + (p.z - cz) * (p.z - cz)));
        chunkQueue.addAll(toLoad);

        generateChunks();

        unloadChunks();

        updateVisibility();
    }

    private void generateChunks() {
        int amount = 0;

        while (!chunkQueue.isEmpty() && amount < CHUNKS_PER_FRAME) {

            ChunkPosition pos = chunkQueue.poll();

            long key = getKey(pos.x, pos.z);

            if (loadedChunks.containsKey(key)) {
                continue;
            }

            Chunk chunk = new Chunk(
                    this,
                    grassTopMaterial,
                    grassSideMaterial,
                    dirtMaterial,
                    stoneMaterial,
                    woodMaterial,
                    woodTopMaterial,
                    leavesMaterial,
                    pos.x,
                    pos.z
            );

            loadedChunks.put(key, chunk);

            rootNode.attachChild(chunk.getNode());

            rebuildNeighbours(pos.x, pos.z);

            amount++;
        }

        if (amount > 0) {
            // Make sure freshly generated chunks respect the render distance.
            updateVisibility();
        }
    }

    /**
     * Relights this chunk and its 4 neighbours (light crosses chunk borders),
     * then rebuilds all their meshes. Center first so neighbours see its light.
     */
    private void rebuildNeighbours(int x, int z) {
        Chunk[] area = {
                loadedChunks.get(getKey(x, z)),
                loadedChunks.get(getKey(x - 1, z)),
                loadedChunks.get(getKey(x + 1, z)),
                loadedChunks.get(getKey(x, z - 1)),
                loadedChunks.get(getKey(x, z + 1))
        };

        for (Chunk chunk : area) {
            if (chunk != null) chunk.computeSkyLight();
        }

        for (Chunk chunk : area) {
            if (chunk != null) chunk.rebuild();
        }
    }

    private void unloadChunks() {
        int radius = renderDistance + PRELOAD_MARGIN;

        Iterator<Map.Entry<Long, Chunk>> iterator = loadedChunks.entrySet().iterator();

        while (iterator.hasNext()) {

            Chunk chunk = iterator.next().getValue();

            int dx = chunk.getChunkX() - centerChunkX;
            int dz = chunk.getChunkZ() - centerChunkZ;

            if (dx * dx + dz * dz > radius * radius) {
                rootNode.detachChild(chunk.getNode());
                iterator.remove();
            }
        }
    }

    private void updateVisibility() {
        if (centerChunkX == Integer.MIN_VALUE) {
            return;
        }

        int radiusSquared = renderDistance * renderDistance;

        for (Chunk chunk : loadedChunks.values()) {

            int dx = chunk.getChunkX() - centerChunkX;
            int dz = chunk.getChunkZ() - centerChunkZ;

            boolean visible = dx * dx + dz * dz <= radiusSquared;

            chunk.getNode().setCullHint(
                    visible ? Spatial.CullHint.Never : Spatial.CullHint.Always
            );
        }
    }

    /** True if the chunk containing this world X/Z position is loaded. */
    public boolean isLoadedAt(float worldX, float worldZ) {
        int cx = Math.floorDiv((int) Math.floor(worldX), Chunk.SIZE);
        int cz = Math.floorDiv((int) Math.floor(worldZ), Chunk.SIZE);
        return loadedChunks.containsKey(getKey(cx, cz));
    }

    /** Sky light (0..15) at a world position. Above the world = full sun, unloaded = 0. */
    public byte getSkyLight(int worldX, int worldY, int worldZ) {
        if (worldY >= Chunk.HEIGHT) {
            return Chunk.MAX_LIGHT;
        }
        if (worldY < 0) {
            return 0;
        }

        Chunk chunk = loadedChunks.get(getKey(
                Math.floorDiv(worldX, Chunk.SIZE),
                Math.floorDiv(worldZ, Chunk.SIZE)));

        if (chunk == null) {
            return 0;
        }

        return chunk.getSkyLight(
                Math.floorMod(worldX, Chunk.SIZE),
                worldY,
                Math.floorMod(worldZ, Chunk.SIZE));
    }

    public byte getBlock(int worldX, int worldY, int worldZ) {
        if (worldY < 0 || worldY >= Chunk.HEIGHT) {
            return Chunk.AIR;
        }

        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        int lx = Math.floorMod(worldX, Chunk.SIZE);
        int lz = Math.floorMod(worldZ, Chunk.SIZE);

        Chunk chunk = loadedChunks.get(getKey(cx, cz));

        if (chunk == null) {
            return Chunk.AIR;
        }

        return chunk.getBlock(lx, worldY, lz);
    }

    public void setBlock(int worldX, int worldY, int worldZ, byte block) {
        if (worldY < 0 || worldY >= Chunk.HEIGHT) {
            return;
        }

        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        int lx = Math.floorMod(worldX, Chunk.SIZE);
        int lz = Math.floorMod(worldZ, Chunk.SIZE);

        Chunk chunk = loadedChunks.get(getKey(cx, cz));

        if (chunk == null) {
            return;
        }

        if (chunk.getBlock(lx, worldY, lz) == block) {
            return;
        }

        chunk.setBlock(lx, worldY, lz, block);

        rebuildNeighbours(cx, cz);
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int distance) {
        renderDistance = Math.max(
                MIN_RENDER_DISTANCE,
                Math.min(RENDER_DISTANCE, distance)
        );

        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
    }

    public void refreshRenderDistance(Vector3f position) {
        if (position == null) {
            return;
        }

        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;

        update(position, true);
    }

    public void setVisible(boolean visible) {
        for (Chunk chunk : loadedChunks.values()) {
            chunk.getNode().setCullHint(
                    visible ? Spatial.CullHint.Never : Spatial.CullHint.Always
            );
        }

        if (visible) {
            updateVisibility();
        }
    }

    public boolean isVisible() {
        if (loadedChunks.isEmpty()) {
            return false;
        }

        Chunk chunk = loadedChunks.values().iterator().next();

        return chunk.getNode().getCullHint() != Spatial.CullHint.Always;
    }

    private long getKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static class ChunkPosition {

        final int x;
        final int z;

        ChunkPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}