/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
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

package org.geysermc.connector.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.nbt.stream.NBTInputStream;
import com.nukkitx.nbt.tag.CompoundTag;
import com.nukkitx.protocol.bedrock.packet.StartGamePacket;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.translators.item.ItemEntry;

import java.io.*;
import java.util.*;

public class Toolbox {

    public static final ObjectMapper JSON_MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES);
    public static final CompoundTag BIOMES;

    public static final List<StartGamePacket.ItemEntry> ITEMS = new ArrayList<>();

    public static final Int2ObjectMap<ItemEntry> ITEM_ENTRIES = new Int2ObjectOpenHashMap<>();

    public static final Map<String, Map<String, String>> LOCALE_MAPPINGS = new HashMap<>();

    static {
        /* Load biomes */
        InputStream biomestream = GeyserConnector.class.getClassLoader().getResourceAsStream("bedrock/biome_definitions.dat");
        if (biomestream == null) {
            throw new AssertionError("Unable to find bedrock/biome_definitions.dat");
        }

        CompoundTag biomesTag;

        try (NBTInputStream biomenbtInputStream = NbtUtils.createNetworkReader(biomestream)){
            biomesTag = (CompoundTag) biomenbtInputStream.readTag();
            BIOMES = biomesTag;
        } catch (Exception ex) {
            GeyserConnector.getInstance().getLogger().warning("Failed to get biomes from biome definitions, is there something wrong with the file?");
            throw new AssertionError(ex);
        }

        /* Load item palette */
        InputStream stream = getResource("bedrock/items.json");

        TypeReference<List<JsonNode>> itemEntriesType = new TypeReference<List<JsonNode>>() {
        };

        List<JsonNode> itemEntries;
        try {
            itemEntries = JSON_MAPPER.readValue(stream, itemEntriesType);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Bedrock runtime item IDs", e);
        }

        for (JsonNode entry : itemEntries) {
            ITEMS.add(new StartGamePacket.ItemEntry(entry.get("name").textValue(), (short) entry.get("id").intValue()));
        }

        stream = getResource("mappings/items.json");

        JsonNode items;
        try {
            items = JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java runtime item IDs", e);
        }

        int itemIndex = 0;
        Iterator<Map.Entry<String, JsonNode>> iterator = items.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            ITEM_ENTRIES.put(itemIndex, new ItemEntry(entry.getKey(), itemIndex,
                    entry.getValue().get("bedrock_id").intValue(), entry.getValue().get("bedrock_data").intValue()));
            itemIndex++;
        }
        
        // Load sound mappings
        SoundUtils.init();
        // Load the locale data
        LocaleUtils.init();
    }

    public static InputStream getResource(String resource) {
        InputStream stream = Toolbox.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new AssertionError("Unable to find resource: " + resource);
        }
        return stream;
    }

    public static void init() {
        // no-op
    }
}