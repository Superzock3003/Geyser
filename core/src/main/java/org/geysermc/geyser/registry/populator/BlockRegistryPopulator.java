/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.registry.populator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableMap;
import com.nukkitx.nbt.*;
import com.nukkitx.protocol.bedrock.data.BlockPropertyData;
import com.nukkitx.protocol.bedrock.v527.Bedrock_v527;
import com.nukkitx.protocol.bedrock.v534.Bedrock_v534;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import com.nukkitx.protocol.bedrock.v544.Bedrock_v544;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.*;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.block.custom.CustomBlockPermutation;
import org.geysermc.geyser.api.block.custom.CustomBlockState;
import org.geysermc.geyser.api.block.custom.component.BoxComponent;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.block.custom.property.CustomBlockProperty;
import org.geysermc.geyser.api.block.custom.property.PropertyType;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.level.block.BlockStateValues;
import org.geysermc.geyser.level.block.GeyserCustomBlockState;
import org.geysermc.geyser.level.physics.PistonBehavior;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.BlockMapping;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.registry.type.CustomSkull;
import org.geysermc.geyser.util.BlockUtils;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

/**
 * Populates the block registries.
 */
public class BlockRegistryPopulator {
    private static final ImmutableMap<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> BLOCK_MAPPERS;
    private static final BiFunction<String, NbtMapBuilder, String> EMPTY_MAPPER = (bedrockIdentifier, statesBuilder) -> null;

    static {
        ImmutableMap.Builder<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> stateMapperBuilder = ImmutableMap.<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>>builder()
                .put(ObjectIntPair.of("1_19_0", Bedrock_v527.V527_CODEC.getProtocolVersion()), EMPTY_MAPPER)
                .put(ObjectIntPair.of("1_19_0", Bedrock_v534.V534_CODEC.getProtocolVersion()), EMPTY_MAPPER)
                .put(ObjectIntPair.of("1_19_20", Bedrock_v544.V544_CODEC.getProtocolVersion()), EMPTY_MAPPER); // Block palette hasn't changed, but the custom block nbt format has changed

        BLOCK_MAPPERS = stateMapperBuilder.build();
    }

    private static final ImmutableMap<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> BLOCK_MAPPERS;
    private static final BiFunction<String, NbtMapBuilder, String> EMPTY_MAPPER = (bedrockIdentifier, statesBuilder) -> null;

    /**
     * Stores the raw blocks JSON until it is no longer needed.
     */
    private static JsonNode BLOCKS_JSON;

    public static void populate() {
        registerJavaBlocks();
        registerCustomBedrockBlocks();
        registerBedrockBlocks();

        BLOCKS_JSON = null;
    }

    private static void registerCustomBedrockBlocks() {
        if (!GeyserImpl.getInstance().getConfig().isAddCustomBlocks()) {
            return;
        }
        Set<String> customBlockNames = new HashSet<>();
        Set<CustomBlockData> customBlocks = new HashSet<>();
        Int2ObjectMap<CustomBlockState> blockStateOverrides = new Int2ObjectOpenHashMap<>();
        GeyserImpl.getInstance().getEventBus().fire(new GeyserDefineCustomBlocksEvent() {
            @Override
            public void registerCustomBlock(@NonNull CustomBlockData customBlockData) {
                if (!customBlockNames.add(customBlockData.name())) {
                    throw new IllegalArgumentException("Another custom block was already registered under the name: " + customBlockData.name());
                }
                // TODO validate collision+selection box bounds
                // TODO validate names
                customBlocks.add(customBlockData);
            }

            @Override
            public void registerBlockStateOverride(@NonNull String javaIdentifier, @NonNull CustomBlockState customBlockState) {
                int id = BlockRegistries.JAVA_IDENTIFIERS.getOrDefault(javaIdentifier, -1);
                if (id == -1) {
                    throw new IllegalArgumentException("Unknown Java block state. Identifier: " + javaIdentifier);
                }
                if (!customBlocks.contains(customBlockState.block())) {
                    throw new IllegalArgumentException("Custom block is unregistered. Name: " + customBlockState.name());
                }
                CustomBlockState oldBlockState = blockStateOverrides.put(id, customBlockState);
                if (oldBlockState != null) {
                    // TODO should this be an error? Allow extensions to query block state overrides?
                    GeyserImpl.getInstance().getLogger().debug("Duplicate block state override for Java Identifier: " +
                            javaIdentifier + " Old override: " + oldBlockState.name() + " New override: " + customBlockState.name());
                }
            }
        });

        for (CustomSkull customSkull : BlockRegistries.CUSTOM_SKULLS.get().values()) {
            customBlocks.add(customSkull.getCustomBlockData());
        }

        BlockRegistries.CUSTOM_BLOCKS.set(customBlocks.toArray(new CustomBlockData[0]));
        GeyserImpl.getInstance().getLogger().debug("Registered " + customBlocks.size() + " custom blocks.");

        BlockRegistries.CUSTOM_BLOCK_STATE_OVERRIDES.set(blockStateOverrides);
        GeyserImpl.getInstance().getLogger().debug("Registered " + blockStateOverrides.size() + " custom block overrides.");
    }

    private static void generateCustomBlockStates(CustomBlockData customBlock, List<NbtMap> blockStates, List<CustomBlockState> customExtBlockStates, int stateVersion) {
        int totalPermutations = 1;
        for (CustomBlockProperty<?> property : customBlock.properties().values()) {
            totalPermutations *= property.values().size();
        }

        for (int i = 0; i < totalPermutations; i++) {
            NbtMapBuilder statesBuilder = NbtMap.builder();
            int permIndex = i;
            for (CustomBlockProperty<?> property : customBlock.properties().values()) {
                statesBuilder.put(property.name(), property.values().get(permIndex % property.values().size()));
                permIndex /= property.values().size();
            }
            NbtMap states = statesBuilder.build();

            blockStates.add(NbtMap.builder()
                    .putString("name", customBlock.identifier())
                    .putInt("version", stateVersion)
                    .putCompound("states", states)
                    .build());
            customExtBlockStates.add(new GeyserCustomBlockState(customBlock, states));
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockPropertyData generateBlockPropertyData(CustomBlockData customBlock, int protocolVersion) {
        List<NbtMap> permutations = new ArrayList<>();
        for (CustomBlockPermutation permutation : customBlock.permutations()) {
            permutations.add(NbtMap.builder()
                    .putCompound("components", convertComponents(permutation.components(), protocolVersion))
                    .putString("condition", permutation.condition())
                    .build());
        }

        // The order that properties are defined influences the order that block states are generated
        List<NbtMap> properties = new ArrayList<>();
        for (CustomBlockProperty<?> property : customBlock.properties().values()) {
            NbtMapBuilder propertyBuilder = NbtMap.builder()
                    .putString("name", property.name());
            if (property.type() == PropertyType.BOOLEAN) {
                propertyBuilder.putList("enum", NbtType.BYTE, List.of((byte) 0, (byte) 1));
            } else if (property.type() == PropertyType.INTEGER) {
                propertyBuilder.putList("enum", NbtType.INT, (List<Integer>) property.values());
            } else if (property.type() == PropertyType.STRING) {
                propertyBuilder.putList("enum", NbtType.STRING, (List<String>) property.values());
            }
            properties.add(propertyBuilder.build());
        }

        NbtMap propertyTag = NbtMap.builder()
                .putCompound("components", convertComponents(customBlock.components(), protocolVersion))
                .putInt("molangVersion", 0)
                .putList("permutations", NbtType.COMPOUND, permutations)
                .putList("properties", NbtType.COMPOUND, properties)
                .build();
        return new BlockPropertyData(customBlock.identifier(), propertyTag);
    }

    private static void registerBedrockBlocks() {
        BiFunction<String, NbtMapBuilder, String> emptyMapper = (bedrockIdentifier, statesBuilder) -> null;
        ImmutableMap<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> blockMappers = ImmutableMap.<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>>builder()
                .put(ObjectIntPair.of("1_19_0", Bedrock_v527.V527_CODEC.getProtocolVersion()), (bedrockIdentifier, statesBuilder) -> {
                    if (bedrockIdentifier.equals("minecraft:muddy_mangrove_roots")) {
                        statesBuilder.remove("pillar_axis");
                    }
                    return null;
                })
                .put(ObjectIntPair.of("1_19_20", Bedrock_v544.V544_CODEC.getProtocolVersion()), emptyMapper).build();


        for (Map.Entry<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> palette : BLOCK_MAPPERS.entrySet()) {
            BiFunction<String, NbtMapBuilder, String> stateMapper = palette.getValue();
            int protocolVersion = palette.getKey().valueInt();
            NbtList<NbtMap> blocksTag;
            List<NbtMap> blockStates;
            try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource(String.format("bedrock/block_palette.%s.nbt", palette.getKey().key()));
                 NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)), true, true)) {
                NbtMap blockPalette = (NbtMap) nbtInputStream.readTag();
                blocksTag = (NbtList<NbtMap>) blockPalette.getList("blocks", NbtType.COMPOUND);
                blockStates = new ArrayList<>(blocksTag);
            } catch (Exception e) {
                throw new AssertionError("Unable to get blocks from runtime block states", e);
            }
            int stateVersion = blocksTag.get(0).getInt("version");

            List<BlockPropertyData> customBlockProperties = new ArrayList<>();
            List<NbtMap> customBlockStates = new ArrayList<>();
            List<CustomBlockState> customExtBlockStates = new ArrayList<>();
            int[] remappedVanillaIds = new int[0];
            if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
                for (CustomBlockData customBlock : BlockRegistries.CUSTOM_BLOCKS.get()) {
                    customBlockProperties.add(generateBlockPropertyData(customBlock, protocolVersion));
                    generateCustomBlockStates(customBlock, customBlockStates, customExtBlockStates, stateVersion);
                }
                blockStates.addAll(customBlockStates);
                GeyserImpl.getInstance().getLogger().debug("Added " + customBlockStates.size() + " custom block states to v" + protocolVersion + " palette.");

                // The palette is sorted by the FNV1 64-bit hash of the name
                blockStates.sort((a, b) -> Long.compareUnsigned(fnv164(a.getString("name")), fnv164(b.getString("name"))));
            }

            // New since 1.16.100 - find the block runtime ID by the order given to us in the block palette,
            // as we no longer send a block palette
            Object2IntMap<NbtMap> blockStateOrderedMap = new Object2IntOpenHashMap<>(blocksTag.size());

            int stateVersion = -1;
            for (int i = 0; i < blocksStates.size(); i++) {
                NbtMapBuilder builder = blocksStates.get(i).toBuilder();
                builder.remove("name_hash"); // Quick workaround - was added in 1.19.20
                NbtMap tag = builder.build();
                if (blockStateOrderedMap.containsKey(tag)) {
                    throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
                }
                blockStateOrderedMap.put(tag, i);
            }

            Object2IntMap<CustomBlockState> customBlockStateIds = Object2IntMaps.emptyMap();
            if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
                customBlockStateIds = new Object2IntOpenHashMap<>(customExtBlockStates.size());
                for (int i = 0; i < customExtBlockStates.size(); i++) {
                    NbtMap tag = customBlockStates.get(i);
                    CustomBlockState blockState = customExtBlockStates.get(i);
                    customBlockStateIds.put(blockState, blockStateOrderedMap.getInt(tag));
                }

                remappedVanillaIds = new int[blocksTag.size()];
                for (int i = 0; i < blocksTag.size(); i++) {
                    remappedVanillaIds[i] = blockStateOrderedMap.getInt(blocksTag.get(i));
                }
            }

            int airRuntimeId = -1;
            int commandBlockRuntimeId = -1;
            int javaRuntimeId = -1;
            int waterRuntimeId = -1;
            int movingBlockRuntimeId = -1;
            Iterator<Map.Entry<String, JsonNode>> blocksIterator = BLOCKS_JSON.fields();

            int[] javaToBedrockBlocks = new int[BLOCKS_JSON.size()];

            Map<String, NbtMap> flowerPotBlocks = new Object2ObjectOpenHashMap<>();
            Object2IntMap<NbtMap> itemFrames = new Object2IntOpenHashMap<>();

            IntSet jigsawStateIds = new IntOpenHashSet();

            BlockMappings.BlockMappingsBuilder builder = BlockMappings.builder();
            while (blocksIterator.hasNext()) {
                javaRuntimeId++;
                Map.Entry<String, JsonNode> entry = blocksIterator.next();
                String javaId = entry.getKey();

                int bedrockRuntimeId;
                CustomBlockState blockStateOverride = BlockRegistries.CUSTOM_BLOCK_STATE_OVERRIDES.get(javaRuntimeId);
                if (blockStateOverride == null) {
                    bedrockRuntimeId = blockStateOrderedMap.getOrDefault(buildBedrockState(entry.getValue(), stateVersion, stateMapper), -1);
                    if (bedrockRuntimeId == -1) {
                        throw new RuntimeException("Unable to find " + javaId + " Bedrock runtime ID! Built NBT tag: \n" +
                                buildBedrockState(entry.getValue(), stateVersion, stateMapper));
                    }
                } else {
                    bedrockRuntimeId = customBlockStateIds.getOrDefault(blockStateOverride, -1);
                    if (bedrockRuntimeId == -1) {
                        throw new RuntimeException("Unable to find " + javaId + " Bedrock runtime ID! Custom block override: \n" +
                            blockStateOverride);
                    }
                }

                switch (javaId) {
                    case "minecraft:air" -> airRuntimeId = bedrockRuntimeId;
                    case "minecraft:water[level=0]" -> waterRuntimeId = bedrockRuntimeId;
                    case "minecraft:command_block[conditional=false,facing=north]" -> commandBlockRuntimeId = bedrockRuntimeId;
                    case "minecraft:moving_piston[facing=north,type=normal]" -> movingBlockRuntimeId = bedrockRuntimeId;
                }

                if (javaId.contains("jigsaw")) {
                    jigsawStateIds.add(bedrockRuntimeId);
                }

                boolean waterlogged = entry.getKey().contains("waterlogged=true")
                        || javaId.contains("minecraft:bubble_column") || javaId.contains("minecraft:kelp") || javaId.contains("seagrass");

                if (waterlogged) {
                    int finalJavaRuntimeId = javaRuntimeId;
                    BlockRegistries.WATERLOGGED.register(set -> set.add(finalJavaRuntimeId));
                }

                String cleanJavaIdentifier = BlockUtils.getCleanIdentifier(entry.getKey());

                // Get the tag needed for non-empty flower pots
                if (entry.getValue().get("pottable") != null) {
                    flowerPotBlocks.put(cleanJavaIdentifier.intern(), blockStates.get(bedrockRuntimeId));
                }

                javaToBedrockBlocks[javaRuntimeId] = bedrockRuntimeId;
            }

            if (commandBlockRuntimeId == -1) {
                throw new AssertionError("Unable to find command block in palette");
            }
            builder.commandBlockRuntimeId(commandBlockRuntimeId);

            if (waterRuntimeId == -1) {
                throw new AssertionError("Unable to find water in palette");
            }
            builder.bedrockWaterId(waterRuntimeId);

            if (airRuntimeId == -1) {
                throw new AssertionError("Unable to find air in palette");
            }
            builder.bedrockAirId(airRuntimeId);

            if (movingBlockRuntimeId == -1) {
                throw new AssertionError("Unable to find moving block in palette");
            }
            builder.bedrockMovingBlockId(movingBlockRuntimeId);

            // Loop around again to find all item frame runtime IDs
            for (Object2IntMap.Entry<NbtMap> entry : blockStateOrderedMap.object2IntEntrySet()) {
                String name = entry.getKey().getString("name");
                if (name.equals("minecraft:frame") || name.equals("minecraft:glow_frame")) {
                    itemFrames.put(entry.getKey(), entry.getIntValue());
                }
            }
            builder.bedrockBlockStates(new NbtList<>(NbtType.COMPOUND, blockStates));

            BlockRegistries.BLOCKS.register(palette.getKey().valueInt(), builder.blockStateVersion(stateVersion)
                    .javaToBedrockBlocks(javaToBedrockBlocks)
                    .itemFrames(itemFrames)
                    .flowerPotBlocks(flowerPotBlocks)
                    .jigsawStateIds(jigsawStateIds)
                    .remappedVanillaIds(remappedVanillaIds)
                    .blockProperties(customBlockProperties)
                    .customBlockStateIds(customBlockStateIds)
                    .build());
        }
    }

    private static NbtMap convertComponents(CustomBlockComponents components, int protocolVersion) {
        if (components == null) {
            return NbtMap.EMPTY;
        }
        NbtMapBuilder builder = NbtMap.builder();
        if (components.selectionBox() != null) {
            builder.putCompound("minecraft:aim_collision", convertBox(components.selectionBox()));
        }
        if (components.collisionBox() != null) {
            String tagName = "minecraft:block_collision";
            if (protocolVersion >= Bedrock_v534.V534_CODEC.getProtocolVersion()) {
                tagName = "minecraft:collision_box";
            }
            builder.putCompound(tagName, convertBox(components.collisionBox()));
        }
        if (components.geometry() != null) {
            builder.putCompound("minecraft:geometry", NbtMap.builder()
                    .putString("value", components.geometry())
                    .build());
        }
        if (!components.materialInstances().isEmpty()) {
            NbtMapBuilder materialsBuilder = NbtMap.builder();
            for (Map.Entry<String, MaterialInstance> entry : components.materialInstances().entrySet()) {
                MaterialInstance materialInstance = entry.getValue();
                materialsBuilder.putCompound(entry.getKey(), NbtMap.builder()
                        .putString("texture", materialInstance.texture())
                        .putString("render_method", materialInstance.renderMethod())
                        .putBoolean("face_dimming", materialInstance.faceDimming())
                        .putBoolean("ambient_occlusion", materialInstance.faceDimming())
                        .build());
            }
            builder.putCompound("minecraft:material_instances", NbtMap.builder()
                    .putCompound("mappings", NbtMap.EMPTY)
                    .putCompound("materials", materialsBuilder.build())
                    .build());
        }
        if (components.destroyTime() != null) {
            builder.putCompound("minecraft:destroy_time", NbtMap.builder()
                    .putFloat("value", components.destroyTime())
                    .build());
        }
        if (components.friction() != null) {
            builder.putCompound("minecraft:friction", NbtMap.builder()
                    .putFloat("value", components.friction())
                    .build());
        }
        if (components.lightEmission() != null) {
            builder.putCompound("minecraft:block_light_emission", NbtMap.builder()
                    .putFloat("value", ((float) components.lightEmission()) / 15f)
                    .build());
        }
        if (components.lightDampening() != null) {
            builder.putCompound("minecraft:block_light_filter", NbtMap.builder()
                    .putByte("value", components.lightDampening().byteValue())
                    .build());
        }
        if (components.rotation() != null) {
            builder.putCompound("minecraft:rotation", NbtMap.builder()
                    .putFloat("x", components.rotation().x())
                    .putFloat("y", components.rotation().y())
                    .putFloat("z", components.rotation().z())
                    .build());
        }
        return builder.build();
    }

    private static NbtMap convertBox(BoxComponent boxComponent) {
        return NbtMap.builder()
                .putBoolean("enabled", !boxComponent.isEmpty())
                .putList("origin", NbtType.FLOAT, boxComponent.originX(), boxComponent.originY(), boxComponent.originZ())
                .putList("size", NbtType.FLOAT, boxComponent.sizeX(), boxComponent.sizeY(), boxComponent.sizeZ())
                .build();
    }

    private static void registerJavaBlocks() {
        JsonNode blocksJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource("mappings/blocks.json")) {
            blocksJson = GeyserImpl.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block mappings", e);
        }

        BlockRegistries.JAVA_BLOCKS.set(new BlockMapping[blocksJson.size()]); // Set array size to number of blockstates

        Deque<String> cleanIdentifiers = new ArrayDeque<>();

        int javaRuntimeId = -1;
        int bellBlockId = -1;
        int cobwebBlockId = -1;
        int furnaceRuntimeId = -1;
        int furnaceLitRuntimeId = -1;
        int honeyBlockRuntimeId = -1;
        int slimeBlockRuntimeId = -1;
        int spawnerRuntimeId = -1;
        int uniqueJavaId = -1;
        int waterRuntimeId = -1;
        Iterator<Map.Entry<String, JsonNode>> blocksIterator = blocksJson.fields();
        while (blocksIterator.hasNext()) {
            javaRuntimeId++;
            Map.Entry<String, JsonNode> entry = blocksIterator.next();
            String javaId = entry.getKey();

            // TODO fix this, (no block should have a null hardness)
            BlockMapping.BlockMappingBuilder builder = BlockMapping.builder();
            JsonNode hardnessNode = entry.getValue().get("block_hardness");
            if (hardnessNode != null) {
                builder.hardness(hardnessNode.doubleValue());
            }

            JsonNode canBreakWithHandNode = entry.getValue().get("can_break_with_hand");
            if (canBreakWithHandNode != null) {
                builder.canBreakWithHand(canBreakWithHandNode.booleanValue());
            } else {
                builder.canBreakWithHand(false);
            }

            JsonNode collisionIndexNode = entry.getValue().get("collision_index");
            if (hardnessNode != null) {
                builder.collisionIndex(collisionIndexNode.intValue());
            }

            JsonNode pickItemNode = entry.getValue().get("pick_item");
            if (pickItemNode != null) {
                builder.pickItem(pickItemNode.textValue().intern());
            }

            if (javaId.equals("minecraft:obsidian") || javaId.equals("minecraft:crying_obsidian") || javaId.startsWith("minecraft:respawn_anchor") || javaId.startsWith("minecraft:reinforced_deepslate")) {
                builder.pistonBehavior(PistonBehavior.BLOCK);
            } else {
                JsonNode pistonBehaviorNode = entry.getValue().get("piston_behavior");
                if (pistonBehaviorNode != null) {
                    builder.pistonBehavior(PistonBehavior.getByName(pistonBehaviorNode.textValue()));
                } else {
                    builder.pistonBehavior(PistonBehavior.NORMAL);
                }
            }

            JsonNode hasBlockEntityNode = entry.getValue().get("has_block_entity");
            if (hasBlockEntityNode != null) {
                builder.isBlockEntity(hasBlockEntityNode.booleanValue());
            } else {
                builder.isBlockEntity(false);
            }

            BlockStateValues.storeBlockStateValues(entry.getKey(), javaRuntimeId, entry.getValue());

            String cleanJavaIdentifier = BlockUtils.getCleanIdentifier(entry.getKey());
            String bedrockIdentifier = entry.getValue().get("bedrock_identifier").asText();

            if (!cleanJavaIdentifier.equals(cleanIdentifiers.peekLast())) {
                uniqueJavaId++;
                cleanIdentifiers.add(cleanJavaIdentifier.intern());
            }

            builder.javaIdentifier(javaId);
            builder.javaBlockId(uniqueJavaId);

            BlockRegistries.JAVA_IDENTIFIERS.register(javaId, javaRuntimeId);
            BlockRegistries.JAVA_BLOCKS.register(javaRuntimeId, builder.build());

            // Keeping this here since this is currently unchanged between versions
            // It's possible to only have this store differences in names, but the key set of all Java names is used in sending command suggestions
            BlockRegistries.JAVA_TO_BEDROCK_IDENTIFIERS.register(cleanJavaIdentifier.intern(), bedrockIdentifier.intern());

            if (javaId.startsWith("minecraft:bell[")) {
                bellBlockId = uniqueJavaId;

            } else if (javaId.contains("cobweb")) {
                cobwebBlockId = uniqueJavaId;

            } else if (javaId.startsWith("minecraft:furnace[facing=north")) {
                if (javaId.contains("lit=true")) {
                    furnaceLitRuntimeId = javaRuntimeId;
                } else {
                    furnaceRuntimeId = javaRuntimeId;
                }

            } else if (javaId.startsWith("minecraft:spawner")) {
                spawnerRuntimeId = javaRuntimeId;

            } else if ("minecraft:water[level=0]".equals(javaId)) {
                waterRuntimeId = javaRuntimeId;
            } else if (javaId.equals("minecraft:honey_block")) {
                honeyBlockRuntimeId = javaRuntimeId;
            } else if (javaId.equals("minecraft:slime_block")) {
                slimeBlockRuntimeId = javaRuntimeId;
            }
        }
        if (bellBlockId == -1) {
            throw new AssertionError("Unable to find bell in palette");
        }
        BlockStateValues.JAVA_BELL_ID = bellBlockId;

        if (cobwebBlockId == -1) {
            throw new AssertionError("Unable to find cobwebs in palette");
        }
        BlockStateValues.JAVA_COBWEB_ID = cobwebBlockId;

        if (furnaceRuntimeId == -1) {
            throw new AssertionError("Unable to find furnace in palette");
        }
        BlockStateValues.JAVA_FURNACE_ID = furnaceRuntimeId;

        if (furnaceLitRuntimeId == -1) {
            throw new AssertionError("Unable to find lit furnace in palette");
        }
        BlockStateValues.JAVA_FURNACE_LIT_ID = furnaceLitRuntimeId;

        if (honeyBlockRuntimeId == -1) {
            throw new AssertionError("Unable to find honey block in palette");
        }
        BlockStateValues.JAVA_HONEY_BLOCK_ID = honeyBlockRuntimeId;

        if (slimeBlockRuntimeId == -1) {
            throw new AssertionError("Unable to find slime block in palette");
        }
        BlockStateValues.JAVA_SLIME_BLOCK_ID = slimeBlockRuntimeId;

        if (spawnerRuntimeId == -1) {
            throw new AssertionError("Unable to find spawner in palette");
        }
        BlockStateValues.JAVA_SPAWNER_ID = spawnerRuntimeId;

        if (waterRuntimeId == -1) {
            throw new AssertionError("Unable to find Java water in palette");
        }
        BlockStateValues.JAVA_WATER_ID = waterRuntimeId;

        BlockRegistries.CLEAN_JAVA_IDENTIFIERS.set(cleanIdentifiers.toArray(new String[0]));

        BLOCKS_JSON = blocksJson;

        JsonNode blockInteractionsJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource("mappings/interactions.json")) {
            blockInteractionsJson = GeyserImpl.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block interaction mappings", e);
        }

        BlockRegistries.INTERACTIVE.set(toBlockStateSet((ArrayNode) blockInteractionsJson.get("always_consumes")));
        BlockRegistries.INTERACTIVE_MAY_BUILD.set(toBlockStateSet((ArrayNode) blockInteractionsJson.get("requires_may_build")));
    }

    private static IntSet toBlockStateSet(ArrayNode node) {
        IntSet blockStateSet = new IntOpenHashSet(node.size());
        for (JsonNode javaIdentifier : node) {
            blockStateSet.add(BlockRegistries.JAVA_IDENTIFIERS.get().getInt(javaIdentifier.textValue()));
        }
        return blockStateSet;
    }

    private static NbtMap buildBedrockState(JsonNode node, int blockStateVersion, BiFunction<String, NbtMapBuilder, String> statesMapper) {
        NbtMapBuilder tagBuilder = NbtMap.builder();
        String bedrockIdentifier = node.get("bedrock_identifier").textValue();
        tagBuilder.putString("name", bedrockIdentifier)
                .putInt("version", blockStateVersion);

        NbtMapBuilder statesBuilder = NbtMap.builder();

        // check for states
        if (node.has("bedrock_states")) {
            Iterator<Map.Entry<String, JsonNode>> statesIterator = node.get("bedrock_states").fields();

            while (statesIterator.hasNext()) {
                Map.Entry<String, JsonNode> stateEntry = statesIterator.next();
                JsonNode stateValue = stateEntry.getValue();
                switch (stateValue.getNodeType()) {
                    case BOOLEAN -> statesBuilder.putBoolean(stateEntry.getKey(), stateValue.booleanValue());
                    case STRING -> statesBuilder.putString(stateEntry.getKey(), stateValue.textValue());
                    case NUMBER -> statesBuilder.putInt(stateEntry.getKey(), stateValue.intValue());
                }
            }
        }
        String newIdentifier = statesMapper.apply(bedrockIdentifier, statesBuilder);
        if (newIdentifier != null) {
            tagBuilder.putString("name", newIdentifier);
        }
        tagBuilder.put("states", statesBuilder.build());
        return tagBuilder.build();
    }

    private static final long FNV1_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1_64_PRIME = 1099511628211L;

    private static long fnv164(String str) {
        long hash = FNV1_64_OFFSET_BASIS;
        for (byte b : str.getBytes(StandardCharsets.UTF_8)) {
            hash *= FNV1_64_PRIME;
            hash ^= b;
        }
        return hash;
    }
}
