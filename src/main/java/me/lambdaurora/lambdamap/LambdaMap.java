/*
 * Copyright (c) 2020 LambdAurora <aurora42lambda@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lambdaurora.lambdamap;

import me.lambdaurora.lambdamap.gui.MapHud;
import me.lambdaurora.lambdamap.gui.WorldMapRenderer;
import me.lambdaurora.lambdamap.gui.WorldMapScreen;
import me.lambdaurora.lambdamap.map.MapChunk;
import me.lambdaurora.lambdamap.map.WorldMap;
import me.lambdaurora.lambdamap.map.marker.Marker;
import me.lambdaurora.lambdamap.map.marker.MarkerType;
import me.lambdaurora.lambdamap.mixin.PersistentStateManagerAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.util.NbtType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapIcon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * Represents the LambdaMap mod.
 *
 * @author LambdAurora
 * @version 1.0.0
 * @since 1.0.0
 */
public class LambdaMap implements ClientModInitializer {
    public static final String NAMESPACE = "lambdamap";
    public static final Identifier MAP_ICONS_TEXTURE = new Identifier("textures/map/map_icons.png");
    public static final RenderLayer MAP_ICONS = RenderLayer.getText(MAP_ICONS_TEXTURE);
    private static LambdaMap INSTANCE;
    private final KeyBinding hudKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding("lambdamap.keybind.hud", GLFW.GLFW_KEY_O, "key.categories.misc"));
    private final KeyBinding mapKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding("lambdamap.keybind.map", GLFW.GLFW_KEY_B, "key.categories.misc"));
    private final WorldMapRenderer renderer = new WorldMapRenderer(this);
    private WorldMap map = null;
    public MapHud hud = null;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            this.hud = new MapHud(client);
        });

        HudRenderCallback.EVENT.register((matrices, delta) -> {
            this.hud.render(matrices, LightmapTextureManager.pack(15, 15));
        });

        ClientTickEvents.START_WORLD_TICK.register(world -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (this.map.updatePlayerViewPos(client.player.getBlockX(), client.player.getBlockZ())) {
                this.hud.markDirty();
            }
            this.map.tick();
            this.updateChunks(world, client.player);

            if (this.hudKeybind.wasPressed()) {
                this.hud.setVisible(!this.hud.isVisible());
            }

            if (this.mapKeybind.wasPressed()) {
                client.openScreen(new WorldMapScreen());
            }

            this.hud.updateTexture(this.getMap());

            // @TODO move this to marker manager directly
            ItemStack stack = client.player.getMainHandStack();
            if (!stack.isEmpty() && stack.isOf(Items.FILLED_MAP) && stack.hasTag()) {
                CompoundTag tag = stack.getTag();
                tag.getList("Decorations", NbtType.COMPOUND).stream().map(decoration -> ((CompoundTag) decoration)).forEach(decoration -> {
                    MapIcon.Type type = MapIcon.Type.byId(decoration.getByte("type"));
                    if (type.isAlwaysRendered()) {
                        this.map.getMarkerManager().addMarker(new Marker(MarkerType.getVanillaMarkerType(type),
                                (int) decoration.getDouble("x"), 64, (int) decoration.getDouble("z"),
                                decoration.getFloat("rot"), null));
                    }
                });
            }
        });
    }

    public WorldMap getMap() {
        return this.map;
    }

    public WorldMapRenderer getRenderer() {
        return this.renderer;
    }

    public void loadMap(MinecraftClient client, RegistryKey<World> worldKey) {
        File directory;
        if (client.getServer() != null) {
            directory = getWorldMapDirectorySP(client, worldKey);
        } else {
            directory = getWorldMapDirectoryMP(client, worldKey);
        }
        this.map = new WorldMap(directory);
        this.renderer.setWorldMap(this.map);
    }

    public void unloadMap() {
        this.map.unload();
        this.map = null;
    }

    public void updateChunks(World world, PlayerEntity entity) {
        ChunkPos pos = entity.getChunkPos();
        for (int x = pos.x - 4; x < pos.x + 5; x++) {
            for (int z = pos.z - 4; z < pos.z + 5; z++) {
                this.updateChunk(world, x, z);
            }
        }
    }

    public void updateChunk(World world, int chunkX, int chunkZ) {
        MapChunk mapChunk = this.map.getChunkOrCreate(chunkX >> 3, chunkZ >> 3);
        int chunkStartX = ChunkSectionPos.getBlockCoord(chunkX);
        int chunkStartZ = ChunkSectionPos.getBlockCoord(chunkZ);
        int mapChunkStartX = chunkStartX & 127;
        int mapChunkStartZ = chunkStartZ & 127;

        // Big thanks to comp500 for this piece of code
        // https://github.com/comp500/tinymap/blob/master/src/main/java/link/infra/tinymap/TileGenerator.java#L103
        BlockSearcher searcher = new BlockSearcher(world);
        boolean hasCeiling = world.getDimension().hasCeiling();

        WorldChunk chunkBefore = (WorldChunk) world.getChunk(chunkX, chunkZ - 1, ChunkStatus.SURFACE, false);
        ChunkPos chunkPosBefore = new ChunkPos(chunkX, chunkZ - 1);
        Heightmap chunkBeforeHeightmap;
        if (chunkBefore != null) chunkBeforeHeightmap = chunkBefore.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        else return;

        int[] lastHeights = new int[16];

        Chunk chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.SURFACE, false);
        if (chunk == null)
            return;
        Heightmap chunkHeightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);

        for (int xOffset = 0; xOffset < 16; xOffset++) {
            if (!chunkBefore.isEmpty()) {
                // Get first line, to calculate proper shade
                if (hasCeiling) {
                    searcher.searchForBlockCeil(chunkBefore, xOffset, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
                } else {
                    searcher.searchForBlock(chunkBefore, chunkBeforeHeightmap, xOffset, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
                }
                lastHeights[xOffset] = searcher.getHeight();
            }

            for (int zOffset = 0; zOffset < 16; zOffset++) {
                if (hasCeiling) {
                    searcher.searchForBlockCeil(chunk, xOffset, zOffset, chunkStartX, chunkStartZ);
                } else {
                    searcher.searchForBlock(chunk, chunkHeightmap, xOffset, zOffset, chunkStartX, chunkStartZ);
                }

                if (searcher.getHeight() > 0 && !searcher.getState().getFluidState().isEmpty()) {
                    searcher.calcWaterDepth(chunk);
                }

                MapColor mapColor = searcher.getState().getMapColor(world, searcher.pos);
                int shade;

                if (mapColor == MapColor.WATER_BLUE) {
                    double shadeTest = (double) searcher.getWaterDepth() * 0.1D + (double) (xOffset + zOffset & 1) * 0.2D;
                    shade = 1;
                    if (shadeTest < 0.5D) {
                        shade = 2;
                    }

                    if (shadeTest > 0.9D) {
                        shade = 0;
                    }
                } else {
                    double shadeTest = (searcher.getHeight() - lastHeights[xOffset]) * 4.0D / 5.0D + ((double) (xOffset + zOffset & 1) - 0.5D) * 0.4D;
                    shade = 1;
                    if (shadeTest > 0.6D) {
                        shade = 2;
                    }
                    if (shadeTest < -0.6D) {
                        shade = 0;
                    }
                }

                lastHeights[xOffset] = searcher.getHeight();
                if (mapChunk.putColor(mapChunkStartX + xOffset, mapChunkStartZ + zOffset, (byte) (mapColor.id * 4 + shade))) {
                    this.hud.markDirty();
                }
            }
        }
    }

    public static File getWorldMapDirectorySP(MinecraftClient client, RegistryKey<World> worldKey) {
        ServerWorld world = client.getServer().getWorld(worldKey);
        if (world == null) {
            world = client.getServer().getOverworld();
        }
        File worldDirectory = ((PersistentStateManagerAccessor) world.getPersistentStateManager()).getDirectory().getParentFile();
        File mapDirectory = new File(worldDirectory, "lambdamap");
        mapDirectory.mkdirs();
        return mapDirectory;
    }

    public static File getWorldMapDirectoryMP(MinecraftClient client, RegistryKey<World> worldKey) {
        ServerInfo serverInfo = client.getCurrentServerEntry();
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File lambdaMapDir = new File(gameDir, "lambdamap");
        File serverDir = new File(lambdaMapDir, serverInfo.name + "_" + serverInfo.address);
        File worldDir = new File(serverDir, worldKey.getValue().getNamespace() + "/" + worldKey.getValue().getPath());
        if (!worldDir.exists())
            worldDir.mkdirs();
        return worldDir;
    }

    public static Identifier id(String path) {
        return new Identifier(NAMESPACE, path);
    }

    public static LambdaMap get() {
        return INSTANCE;
    }
}
