package com.mygame;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class Chunk {

    public static final int SIZE = 16;
    public static final int HEIGHT = 96;

    public static final byte AIR = 0;
    public static final byte GRASS = 1;
    public static final byte DIRT = 2;
    public static final byte STONE = 3;
    public static final byte WOOD = 4;
    public static final byte LEAVES = 5;

    public static final int MAX_LIGHT = 15;

    // Side order everywhere: 0=+x, 1=-x, 2=+y, 3=-y, 4=+z, 5=-z
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private static final Vector3f[] NORMALS = {
            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
            new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
    };

    // Minecraft's directional face shading: top brightest, bottom darkest.
    private static final float[] SHADE = {0.6f, 0.6f, 1.0f, 0.5f, 0.8f, 0.8f};

    // Ambient occlusion multiplier for 0..3 (0 = tight corner, 3 = open).
    private static final float[] AO_FACTOR = {0.50f, 0.70f, 0.85f, 1.00f};

    // Light level (0..15) -> brightness, Minecraft-like curve (each level = 80%).
    private static final float AMBIENT_MIN = 0.04f;
    private static final float[] LIGHT_LUT = new float[16];

    static {
        for (int i = 0; i < 16; i++) {
            LIGHT_LUT[i] = Math.max(AMBIENT_MIN, (float) Math.pow(0.8, 15 - i));
        }
    }

    private static final float[][] UVS = {
            {1, 0, 1, 1, 0, 1, 0, 0},   // +x
            {1, 0, 1, 1, 0, 1, 0, 0},   // -x
            {0, 0, 0, 1, 1, 1, 1, 0},   // +y
            {0, 0, 0, 1, 1, 1, 1, 0},   // -y
            {0, 0, 1, 0, 1, 1, 0, 1},   // +z
            {1, 1, 0, 1, 0, 0, 1, 0}    // -z
    };

    // Scratch queue for light flood-fill (everything runs on the main thread).
    private static int[] lightQueue = new int[SIZE * HEIGHT * SIZE];
    private static int queueTail = 0;

    private final World world;

    private final int chunkX;
    private final int chunkZ;

    private final Node node;

    private final byte[][][] blocks = new byte[SIZE][HEIGHT][SIZE];

    // Sky light 0..15, flat array, index = (y * SIZE + z) * SIZE + x
    private final byte[] skyLight = new byte[SIZE * HEIGHT * SIZE];

    private final Material grassTopMaterial;
    private final Material grassSideMaterial;
    private final Material dirtMaterial;
    private final Material stoneMaterial;
    private final Material woodMaterial;
    private final Material woodTopMaterial;
    private final Material leavesMaterial;

    public Chunk(
            World world,
            Material grassTopMaterial,
            Material grassSideMaterial,
            Material dirtMaterial,
            Material stoneMaterial,
            Material woodMaterial,
            Material woodTopMaterial,
            Material leavesMaterial,
            int chunkX,
            int chunkZ
    ) {
        this.world = world;

        this.grassTopMaterial = grassTopMaterial;
        this.grassSideMaterial = grassSideMaterial;
        this.dirtMaterial = dirtMaterial;
        this.stoneMaterial = stoneMaterial;
        this.woodMaterial = woodMaterial;
        this.woodTopMaterial = woodTopMaterial;
        this.leavesMaterial = leavesMaterial;

        this.chunkX = chunkX;
        this.chunkZ = chunkZ;

        node = new Node("Chunk_" + chunkX + "_" + chunkZ);
        node.setLocalTranslation(chunkX * SIZE, 0, chunkZ * SIZE);

        generateTerrain();
    }

    // ==============================
    // TERRAIN
    // ==============================

    // Trees: one candidate per TREE_CELL x TREE_CELL area of the world, placed at a
    // jittered position inside the cell. This keeps trees spaced out (never clumped)
    // and lets a tree cross chunk borders without being cut off.
    private static final int TREE_CELL = 10;
    private static final int TREE_JITTER_MIN = 2;
    private static final int TREE_JITTER_RANGE = 6;     // offsets 2..7 inside the cell
    private static final float TREE_CHANCE = 0.6f;      // chance a cell has a tree
    private static final int TREE_MAX_GROUND = 45;      // no trees on high mountains

    /** Ground height for a world column (same formula everywhere, so trees can query it). */
    private static int terrainHeight(int worldX, int worldZ) {
        double noise = SimplexNoise.noise2D(worldX * 0.025, worldZ * 0.025);
        double mountain = Math.abs(SimplexNoise.noise2D(worldX * 0.008, worldZ * 0.008));

        int height = 18 + (int) (noise * 8) + (int) (mountain * 30);
        return Math.max(4, Math.min(HEIGHT - 8, height));
    }

    private void generateTerrain() {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {

                int height = terrainHeight(chunkX * SIZE + x, chunkZ * SIZE + z);

                for (int y = 0; y < HEIGHT; y++) {
                    if (y > height) {
                        blocks[x][y][z] = AIR;
                    } else if (y == height) {
                        blocks[x][y][z] = GRASS;
                    } else if (y > height - 4) {
                        blocks[x][y][z] = DIRT;
                    } else {
                        blocks[x][y][z] = STONE;
                    }
                }
            }
        }

        generateTrees();
    }

    private void generateTrees() {
        int baseX = chunkX * SIZE;
        int baseZ = chunkZ * SIZE;

        // Every cell whose tree could reach into this chunk (leaves extend 2 blocks).
        int minCellX = Math.floorDiv(baseX - 3, TREE_CELL);
        int maxCellX = Math.floorDiv(baseX + SIZE + 2, TREE_CELL);
        int minCellZ = Math.floorDiv(baseZ - 3, TREE_CELL);
        int maxCellZ = Math.floorDiv(baseZ + SIZE + 2, TREE_CELL);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {

                // Seeded per cell so every chunk agrees on the same tree.
                Random random = new Random(cellX * 341873128712L + cellZ * 132897987541L + 4242L);

                if (random.nextFloat() >= TREE_CHANCE) {
                    continue;
                }

                int treeX = cellX * TREE_CELL + TREE_JITTER_MIN + random.nextInt(TREE_JITTER_RANGE);
                int treeZ = cellZ * TREE_CELL + TREE_JITTER_MIN + random.nextInt(TREE_JITTER_RANGE);
                int trunkHeight = 4 + random.nextInt(3);          // 4..6 logs like Minecraft

                int ground = terrainHeight(treeX, treeZ);

                if (ground > TREE_MAX_GROUND || ground + trunkHeight + 2 >= HEIGHT) {
                    continue;
                }

                placeTree(treeX - baseX, ground, treeZ - baseZ, trunkHeight, random);
            }
        }
    }

    /**
     * Minecraft oak shape: a plain trunk, two 5x5 leaf layers around the top two logs
     * (corners randomly missing), then two 3x3 layers above (top layer is a "plus").
     * Coordinates are local to this chunk and may lie outside it; blocks outside are ignored.
     */
    private void placeTree(int lx, int groundY, int lz, int trunkHeight, Random random) {

        for (int i = 1; i <= trunkHeight; i++) {
            setBlock(lx, groundY + i, lz, WOOD);
        }

        int topLog = groundY + trunkHeight;

        for (int layer = -2; layer <= 1; layer++) {

            int dy = layer - 1;                 // -3 .. 0
            int radius = 1 - dy / 2;            // 2, 2, 1, 1
            int y = topLog + layer;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {

                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;

                    // Corners: always removed on the top layer, 50% removed on the others.
                    if (corner && (dy == 0 || random.nextInt(2) == 0)) {
                        continue;
                    }

                    placeLeaf(lx + dx, y, lz + dz);
                }
            }
        }
    }

    private void placeLeaf(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= HEIGHT || z < 0 || z >= SIZE) {
            return;
        }

        if (blocks[x][y][z] == AIR) {
            blocks[x][y][z] = LEAVES;
        }
    }

    // ==============================
    // BLOCK ACCESS
    // ==============================

    public byte getBlock(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= HEIGHT || z < 0 || z >= SIZE) {
            return AIR;
        }
        return blocks[x][y][z];
    }

    public void setBlock(int x, int y, int z, byte block) {
        if (x < 0 || x >= SIZE || y < 0 || y >= HEIGHT || z < 0 || z >= SIZE) {
            return;
        }
        blocks[x][y][z] = block;
    }

    private static boolean isOpaque(byte b) {
        return b != AIR && b != LEAVES;
    }

    // ==============================
    // SKY LIGHT (Minecraft style)
    // ==============================

    private static int lightIndex(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    /** Sky light at a local position inside this chunk (0..15). */
    public byte getSkyLight(int x, int y, int z) {
        if (y >= HEIGHT) return MAX_LIGHT;
        if (y < 0 || x < 0 || x >= SIZE || z < 0 || z >= SIZE) return 0;
        return skyLight[lightIndex(x, y, z)];
    }

    private static void push(int index) {
        if (queueTail == lightQueue.length) {
            lightQueue = Arrays.copyOf(lightQueue, lightQueue.length * 2);
        }
        lightQueue[queueTail++] = index;
    }

    /** Light after stepping into a neighbour cell. Straight-down sunlight does not fade. */
    private static int spread(int light, int dy, byte neighbourBlock) {
        if (dy == -1 && light == MAX_LIGHT && neighbourBlock == AIR) {
            return MAX_LIGHT;
        }
        return light - (neighbourBlock == LEAVES ? 2 : 1);
    }

    /**
     * Recomputes the sky light of the whole chunk:
     * 1) full sunlight straight down every column until something solid,
     * 2) light pulled in from already-loaded neighbour chunks,
     * 3) flood fill sideways / around corners, losing 1 level per block.
     * Caves stay dark, overhangs get soft shadows, leaves dim light.
     */
    public void computeSkyLight() {
        Arrays.fill(skyLight, (byte) 0);
        queueTail = 0;

        int maxY = 0;

        // 1) sunlight columns
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {

                int light = MAX_LIGHT;

                for (int y = HEIGHT - 1; y >= 0; y--) {
                    byte b = blocks[x][y][z];

                    if (b == AIR) {
                        skyLight[lightIndex(x, y, z)] = (byte) light;
                    } else if (b == LEAVES) {
                        light = Math.max(0, light - 1);
                        skyLight[lightIndex(x, y, z)] = (byte) light;
                        if (y > maxY) maxY = y;
                    } else {
                        light = 0;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }

        int top = Math.min(HEIGHT - 1, maxY + 1);

        // 2) light coming in from neighbouring chunks
        int baseX = chunkX * SIZE;
        int baseZ = chunkZ * SIZE;

        for (int y = 0; y <= top; y++) {
            for (int i = 0; i < SIZE; i++) {
                pullFromNeighbour(0, y, i, baseX - 1, baseZ + i);
                pullFromNeighbour(SIZE - 1, y, i, baseX + SIZE, baseZ + i);
                pullFromNeighbour(i, y, 0, baseX + i, baseZ - 1);
                pullFromNeighbour(i, y, SIZE - 1, baseX + i, baseZ + SIZE);
            }
        }

        // 3) seed the queue with every cell that can brighten a neighbour
        for (int y = 0; y <= top; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {

                    if (isOpaque(blocks[x][y][z])) continue;

                    int l = skyLight[lightIndex(x, y, z)];
                    if (l < 2) continue;

                    for (int d = 0; d < 6; d++) {
                        int nx = x + DX[d];
                        int ny = y + DY[d];
                        int nz = z + DZ[d];

                        if (nx < 0 || nx >= SIZE || ny < 0 || ny >= HEIGHT || nz < 0 || nz >= SIZE) continue;

                        byte nb = blocks[nx][ny][nz];
                        if (isOpaque(nb)) continue;

                        if (spread(l, DY[d], nb) > skyLight[lightIndex(nx, ny, nz)]) {
                            push(lightIndex(x, y, z));
                            break;
                        }
                    }
                }
            }
        }

        // flood fill
        int head = 0;

        while (head < queueTail) {
            int index = lightQueue[head++];

            int x = index % SIZE;
            int z = (index / SIZE) % SIZE;
            int y = index / (SIZE * SIZE);

            int l = skyLight[index];
            if (l < 2) continue;

            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                int nz = z + DZ[d];

                if (nx < 0 || nx >= SIZE || ny < 0 || ny >= HEIGHT || nz < 0 || nz >= SIZE) continue;

                byte nb = blocks[nx][ny][nz];
                if (isOpaque(nb)) continue;

                int nl = spread(l, DY[d], nb);
                int ni = lightIndex(nx, ny, nz);

                if (nl > skyLight[ni]) {
                    skyLight[ni] = (byte) nl;
                    push(ni);
                }
            }
        }
    }

    private void pullFromNeighbour(int x, int y, int z, int neighbourWorldX, int neighbourWorldZ) {
        byte b = blocks[x][y][z];
        if (isOpaque(b)) return;

        int nl = world.getSkyLight(neighbourWorldX, y, neighbourWorldZ) - (b == LEAVES ? 2 : 1);
        int index = lightIndex(x, y, z);

        if (nl > skyLight[index]) {
            skyLight[index] = (byte) nl;
            push(index);
        }
    }

    /** Light at a local position that may lie in a neighbouring chunk. */
    private int lightAt(int x, int y, int z) {
        if (y >= HEIGHT) return MAX_LIGHT;
        if (y < 0) return 0;

        if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
            return skyLight[lightIndex(x, y, z)];
        }

        return world.getSkyLight(chunkX * SIZE + x, y, chunkZ * SIZE + z);
    }

    private boolean opaqueAt(int x, int y, int z) {
        return isOpaque(getBlockOutside(x, y, z));
    }

    private static float lightToBrightness(float level) {
        if (level <= 0f) return LIGHT_LUT[0];
        if (level >= 15f) return LIGHT_LUT[15];

        int lo = (int) level;
        float f = level - lo;

        return LIGHT_LUT[lo] * (1f - f) + LIGHT_LUT[lo + 1] * f;
    }

    // ==============================
    // MESH BUILDING
    // ==============================

    public void rebuild() {
        node.detachAllChildren();

        MeshData grassTop = new MeshData();
        MeshData grassSide = new MeshData();
        MeshData dirt = new MeshData();
        MeshData stone = new MeshData();
        MeshData wood = new MeshData();
        MeshData woodTop = new MeshData();
        MeshData leaves = new MeshData();

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {

                    byte block = blocks[x][y][z];

                    if (block == AIR) {
                        continue;
                    }

                    for (int side = 0; side < 6; side++) {

                        if (isFaceVisible(block, x + DX[side], y + DY[side], z + DZ[side])) {
                            addBlockFace(block, side, x, y, z,
                                    grassTop, grassSide, dirt, stone, wood, woodTop, leaves);
                        }
                    }
                }
            }
        }

        createGeometry("grassTop", grassTop, grassTopMaterial);
        createGeometry("grassSide", grassSide, grassSideMaterial);
        createGeometry("dirt", dirt, dirtMaterial);
        createGeometry("stone", stone, stoneMaterial);
        createGeometry("wood", wood, woodMaterial);
        createGeometry("woodTop", woodTop, woodTopMaterial);
        createGeometry("leaves", leaves, leavesMaterial);
    }

    /**
     * Leaves only draw faces that touch air (no hidden faces between leaves or against
     * logs/ground). Every other block still draws its face when the neighbour is air or
     * leaves, because leaf textures have see-through gaps.
     */
    private boolean isFaceVisible(byte block, int x, int y, int z) {
        byte neighbour = getBlockOutside(x, y, z);

        if (block == LEAVES) {
            return neighbour == AIR;
        }

        return neighbour == AIR || neighbour == LEAVES;
    }

    private byte getBlockOutside(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) {
            return AIR;
        }

        if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
            return blocks[x][y][z];
        }

        return world.getBlock(chunkX * SIZE + x, y, chunkZ * SIZE + z);
    }

    private void addBlockFace(
            byte block,
            int side,
            int x,
            int y,
            int z,
            MeshData grassTop,
            MeshData grassSide,
            MeshData dirt,
            MeshData stone,
            MeshData wood,
            MeshData woodTop,
            MeshData leaves
    ) {
        MeshData target;

        if (block == GRASS) {
            target = side == 2 ? grassTop : grassSide;
        } else if (block == DIRT) {
            target = dirt;
        } else if (block == WOOD) {
            target = (side == 2 || side == 3) ? woodTop : wood;
        } else if (block == LEAVES) {
            target = leaves;
        } else {
            target = stone;
        }

        addFace(target, x, y, z, side);
    }

    private void addFace(MeshData data, int x, int y, int z, int side) {
        int start = data.positions.size();

        Vector3f[] vertices;

        switch (side) {
            case 0:
                vertices = new Vector3f[]{
                        new Vector3f(x + 1, y, z),
                        new Vector3f(x + 1, y + 1, z),
                        new Vector3f(x + 1, y + 1, z + 1),
                        new Vector3f(x + 1, y, z + 1)
                };
                break;

            case 1:
                vertices = new Vector3f[]{
                        new Vector3f(x, y, z + 1),
                        new Vector3f(x, y + 1, z + 1),
                        new Vector3f(x, y + 1, z),
                        new Vector3f(x, y, z)
                };
                break;

            case 2:
                vertices = new Vector3f[]{
                        new Vector3f(x, y + 1, z),
                        new Vector3f(x, y + 1, z + 1),
                        new Vector3f(x + 1, y + 1, z + 1),
                        new Vector3f(x + 1, y + 1, z)
                };
                break;

            case 3:
                vertices = new Vector3f[]{
                        new Vector3f(x, y, z + 1),
                        new Vector3f(x, y, z),
                        new Vector3f(x + 1, y, z),
                        new Vector3f(x + 1, y, z + 1)
                };
                break;

            case 4:
                vertices = new Vector3f[]{
                        new Vector3f(x, y, z + 1),
                        new Vector3f(x + 1, y, z + 1),
                        new Vector3f(x + 1, y + 1, z + 1),
                        new Vector3f(x, y + 1, z + 1)
                };
                break;

            default:
                vertices = new Vector3f[]{
                        new Vector3f(x, y + 1, z),
                        new Vector3f(x + 1, y + 1, z),
                        new Vector3f(x + 1, y, z),
                        new Vector3f(x, y, z)
                };
        }

        // The cell directly in front of this face (where the light comes from).
        int px = x + DX[side];
        int py = y + DY[side];
        int pz = z + DZ[side];

        float shade = SHADE[side];
        float[] vertexBrightness = new float[4];

        for (int i = 0; i < 4; i++) {
            Vector3f v = vertices[i];

            // Which corner of the block is this vertex? (0 or 1 on each axis)
            int ox = Math.round(v.x) - x;
            int oy = Math.round(v.y) - y;
            int oz = Math.round(v.z) - z;

            // Two tangent directions pointing from the face centre toward this corner.
            int a1x = 0, a1y = 0, a1z = 0;
            int a2x = 0, a2y = 0, a2z = 0;

            if (DX[side] != 0) {
                a1y = oy == 1 ? 1 : -1;
                a2z = oz == 1 ? 1 : -1;
            } else if (DY[side] != 0) {
                a1x = ox == 1 ? 1 : -1;
                a2z = oz == 1 ? 1 : -1;
            } else {
                a1x = ox == 1 ? 1 : -1;
                a2y = oy == 1 ? 1 : -1;
            }

            boolean side1Solid = opaqueAt(px + a1x, py + a1y, pz + a1z);
            boolean side2Solid = opaqueAt(px + a2x, py + a2y, pz + a2z);
            boolean cornerSolid = opaqueAt(px + a1x + a2x, py + a1y + a2y, pz + a1z + a2z);

            // Smooth lighting: average the light of the 4 cells touching this vertex.
            float sum = lightAt(px, py, pz);
            int count = 1;

            if (!side1Solid) {
                sum += lightAt(px + a1x, py + a1y, pz + a1z);
                count++;
            }
            if (!side2Solid) {
                sum += lightAt(px + a2x, py + a2y, pz + a2z);
                count++;
            }
            if (!(side1Solid && side2Solid) && !cornerSolid) {
                sum += lightAt(px + a1x + a2x, py + a1y + a2y, pz + a1z + a2z);
                count++;
            }

            float lightLevel = sum / count;

            // Ambient occlusion
            int ao;
            if (side1Solid && side2Solid) {
                ao = 0;
            } else {
                ao = 3 - ((side1Solid ? 1 : 0) + (side2Solid ? 1 : 0) + (cornerSolid ? 1 : 0));
            }

            float c = shade * lightToBrightness(lightLevel) * AO_FACTOR[ao];
            vertexBrightness[i] = c;

            data.positions.add(v);
            data.normals.add(NORMALS[side]);
            data.texCoords.add(new Vector2f(UVS[side][i * 2], UVS[side][i * 2 + 1]));
            data.colors.add(new ColorRGBA(c, c, c, 1f));
        }

        // Pick the quad diagonal that avoids ugly lighting seams.
        if (vertexBrightness[0] + vertexBrightness[2] >= vertexBrightness[1] + vertexBrightness[3]) {
            data.indices.add(start);
            data.indices.add(start + 1);
            data.indices.add(start + 2);

            data.indices.add(start);
            data.indices.add(start + 2);
            data.indices.add(start + 3);
        } else {
            data.indices.add(start + 1);
            data.indices.add(start + 2);
            data.indices.add(start + 3);

            data.indices.add(start + 1);
            data.indices.add(start + 3);
            data.indices.add(start);
        }
    }

    private void createGeometry(String name, MeshData data, Material material) {
        if (data.positions.isEmpty()) {
            return;
        }

        Mesh mesh = new Mesh();

        mesh.setBuffer(
                VertexBuffer.Type.Position,
                3,
                BufferUtils.createFloatBuffer(data.positions.toArray(new Vector3f[0]))
        );

        mesh.setBuffer(
                VertexBuffer.Type.Normal,
                3,
                BufferUtils.createFloatBuffer(data.normals.toArray(new Vector3f[0]))
        );

        mesh.setBuffer(
                VertexBuffer.Type.TexCoord,
                2,
                BufferUtils.createFloatBuffer(data.texCoords.toArray(new Vector2f[0]))
        );

        float[] colorArray = new float[data.colors.size() * 4];
        for (int i = 0; i < data.colors.size(); i++) {
            ColorRGBA c = data.colors.get(i);
            colorArray[i * 4] = c.r;
            colorArray[i * 4 + 1] = c.g;
            colorArray[i * 4 + 2] = c.b;
            colorArray[i * 4 + 3] = c.a;
        }
        mesh.setBuffer(
                VertexBuffer.Type.Color,
                4,
                BufferUtils.createFloatBuffer(colorArray)
        );

        mesh.setBuffer(
                VertexBuffer.Type.Index,
                3,
                BufferUtils.createIntBuffer(
                        data.indices.stream().mapToInt(i -> i).toArray()
                )
        );

        mesh.updateBound();

        Geometry geometry = new Geometry(name, mesh);
        geometry.setMaterial(material);

        node.attachChild(geometry);
    }

    // ==============================
    // GETTERS
    // ==============================

    public Node getNode() {
        return node;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    // ==============================
    // HELPERS
    // ==============================

    private static class MeshData {
        List<Vector3f> positions = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<Vector2f> texCoords = new ArrayList<>();
        List<ColorRGBA> colors = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
    }

    /** Smooth value noise, returns -1..1 (name kept for compatibility). */
    private static final class SimplexNoise {

        static double noise2D(double x, double z) {
            int x0 = (int) Math.floor(x);
            int z0 = (int) Math.floor(z);

            double fx = x - x0;
            double fz = z - z0;

            double u = fx * fx * (3 - 2 * fx);
            double v = fz * fz * (3 - 2 * fz);

            double a = hash(x0, z0);
            double b = hash(x0 + 1, z0);
            double c = hash(x0, z0 + 1);
            double d = hash(x0 + 1, z0 + 1);

            double top = a + (b - a) * u;
            double bottom = c + (d - c) * u;

            return top + (bottom - top) * v;
        }

        private static double hash(int x, int z) {
            long h = x * 374761393L + z * 668265263L + 1013904223L;
            h = (h ^ (h >>> 13)) * 1274126177L;
            h ^= (h >>> 16);
            return ((h & 0xFFFF) / 32767.5) - 1.0;
        }
    }
}