package com.npcdropnotifier;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.npcdropnotifier.DropRateColorConstants.*;

@Slf4j
@PluginDescriptor(
        name = "NPC Drop Notifier"
)
public class NpcDropNotifierPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private Notifier notifier;

    @Inject
    private ConfigManager configManager;

    @Inject
    private NpcDropNotifierPopup npcDropNotifierPopup;

    private static final String CONFIG_GROUP = "newdropnotifier";
    private static final String CONFIG_KEY = "monsterDrops";

    private final Gson gson = new Gson();

    // Map of npcName ->  dropRecord
    public String currentNpcKey = "";
    public NpcDropRecord npcDropRecord = null;
    // itemId -> quantity -> Drop
    public Map<Integer, List<NpcDropData.Drop>> currentNpcDropData = null;

    @Override
    protected void shutDown() throws Exception {
        saveNpcDropsToFile(currentNpcKey, npcDropRecord);
    }

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) {
        final NPC npc = npcLootReceived.getNpc();
        final Collection<ItemStack> droppedItems = npcLootReceived.getItems();
        final String npcName = npc.getName();
        final int npcLevel = npc.getCombatLevel();
        final int npcId = npc.getId();
        final String npcKey = npcName + "#Level" + npcLevel;

        if (!Objects.equals(currentNpcKey, npcKey)) {
            // Save drop record for previous npc
            if (!Objects.equals(currentNpcKey, "")) {
                saveNpcDropsToFile(currentNpcKey, npcDropRecord);
            }

            currentNpcKey = npcKey;

            // Load new npc drop record
            npcDropRecord = loadNpcDropsFromFile(npcKey);

            NpcDropData dropData = this.readNpcDropData(npcId, npcName);
            currentNpcDropData = null;

            if (dropData != null) {
                currentNpcDropData = dropData.getDropsByItemIdAndQuantity();
            }
        }

        for (ItemStack droppedItem : droppedItems) {
            final Integer droppedItemId = droppedItem.getId();
            final Integer droppedItemQuantity = droppedItem.getQuantity();

            NpcDropData.Drop drop = findDrop(droppedItemId, droppedItemQuantity);
            Set<String> previouslyDroppedQuantities = npcDropRecord.getItemId(droppedItemId);
            String safeDropQuantity = drop != null ? drop.quantity : String.valueOf(droppedItemQuantity);
            if (previouslyDroppedQuantities == null || !previouslyDroppedQuantities.contains(safeDropQuantity)) {
                npcDropRecord.addDropRecord(droppedItemId, safeDropQuantity);
                npcDropNotifierPopup.addNotificationToQueue(this.getPrettyNotificationMessage(npcName, droppedItemId, droppedItemQuantity));
            }
        }
    }

    NpcDropData.Drop findDrop(Integer itemId, Integer quantity) {
        if (currentNpcDropData == null) {
            return null;
        }

        if (!currentNpcDropData.containsKey(itemId)) {
            return null;
        }

        List<NpcDropData.Drop> drops = currentNpcDropData.get(itemId);
        if (drops == null || drops.isEmpty()) {
            return null;
        }

        for (NpcDropData.Drop drop : drops) {
            if (quantity >= drop.minQuantity && quantity <= drop.maxQuantity) {
                return drop;
            }
        }
        return null;
    }

    // Notification formatting

    String getDropRateColor(String rarity) {
        if (Objects.equals(rarity, "Always")) {
            return ALWAYS;
        }

        String[] parts = rarity.split("/");
        double numerator = Double.parseDouble(parts[0].trim());
        double denominator = Double.parseDouble(parts[1].trim());

        double rarityDouble = numerator / denominator;

        if (rarityDouble >= 0.04) {
            return COMMON;
        } else if (rarityDouble >= 0.01) {
            return UNCOMMON;
        } else if (rarityDouble >= 0.001) {
            return RARE;
        } else {
            return SUPERRARE;
        }
    }

    String getPrettyDropRate(NpcDropData.Drop drop) {
        if (drop == null || drop.rarity == null) {
            return "<br><br>";
        }

        String prettyRarity = drop.rarity;

        if (drop.simplifiedDenominator != 0) {
            prettyRarity =  "1 / " + drop.simplifiedDenominator;
        }

        return "<br><br><col=" + getDropRateColor(drop.rarity) + ">" + prettyRarity + "</col>";
    }

    String getPrettyNotificationMessage(String npcName, Integer itemId, Integer quantity) {
        String itemName = client.getItemDefinition(itemId).getName();

        NpcDropData.Drop drop = findDrop(itemId, quantity);
        if (drop != null && drop.maxQuantity > 1) {
            return npcName + ":<br><col=ffffff>" + itemName + " (" + drop.quantity + ")</col>" + this.getPrettyDropRate(drop);
        }
        return npcName + ":<br><col=ffffff>" + itemName + "</col>" + this.getPrettyDropRate(drop);
    }

    // File operations

    private File createOrGetNpcFolder(String npcKey) {
        File dir = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + this.client.getAccountHash() + "/", npcKey);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }


    private NpcDropData tryGetVariantNpcData(Integer npcId, String npcName) {
        File potentialVariantNpcFile = findClosestLowerFile(npcId);
        if (potentialVariantNpcFile == null) {
            return null;
        }

        try (FileReader reader = new FileReader(potentialVariantNpcFile)) {
            NpcDropData potentialVariantNpcDropData = gson.fromJson(reader, NpcDropData.class);
            if (Objects.equals(potentialVariantNpcDropData.name, npcName)) {
                return potentialVariantNpcDropData;
            } else {
                log.warn("Could not find base monster data file");
                return null;
            }
        } catch (JsonIOException | IOException e) {
            log.warn("Could not load npc drop data");
            return null;
        }
    }

    public static final File MONSTER_DATA_DIR = new File(System.getProperty("user.dir"), "monster_data");

    // Since the monster_data folder is sorted use binary search - O(log(n))
    private static File findClosestLowerFile(int targetNumber) {
        File[] files = MONSTER_DATA_DIR.listFiles();

        Optional<File> closestLowerFile = Arrays.stream(files)
                .filter(file -> {
                    try {
                        int fileNumber = Integer.parseInt(file.getName().replaceAll("\\.[^.]+$", ""));
                        return fileNumber <= targetNumber;
                    } catch (NumberFormatException e) {
                        return false; // Skip files that don't have numeric names
                    }
                })
                .max((file1, file2) -> {
                    int num1 = Integer.parseInt(file1.getName().replaceAll("\\.[^.]+$", ""));
                    int num2 = Integer.parseInt(file2.getName().replaceAll("\\.[^.]+$", ""));
                    return Integer.compare(num1, num2);
                });

        if (closestLowerFile.isPresent()) {
            return closestLowerFile.get();
        } else {
            System.out.println("No file found with a number lower than " + targetNumber);
        }

        return null;
    }

    NpcDropData readNpcDropData(Integer npcId, String npcName) {
        File npcDataFile = new File(MONSTER_DATA_DIR, npcId + ".json");
        log.info("Loading file for npcId {}", npcId);
        try (FileReader reader = new FileReader(npcDataFile)) {
            return gson.fromJson(reader, NpcDropData.class);
        } catch (JsonIOException | IOException e) {
            log.info("Could not load npc drop data for npcId {}, trying to find base monster file", npcId);
            return tryGetVariantNpcData(npcId, npcName);
        }
    }

    private void saveNpcDropsToFile(String npcKey, NpcDropRecord npcDropRecord) {
        File dir = this.createOrGetNpcFolder(npcKey);
        File dataFile = new File(dir, "drop-log.json");
        try (FileWriter writer = new FileWriter(dataFile)) {
            gson.toJson(npcDropRecord, writer);
        } catch (IOException e) {
            log.warn("Failed to save drop data", e);
        }
    }

    private NpcDropRecord loadNpcDropsFromFile(String npcKey) {
        File dataFile = new File(createOrGetNpcFolder(npcKey), "drop-log.json");
        if (!dataFile.exists())
            return new NpcDropRecord();

        Type type = new TypeToken<NpcDropRecord>() {
        }.getType();
        try (FileReader reader = new FileReader(dataFile)) {
            NpcDropRecord loaded = gson.fromJson(reader, type);
            return loaded != null ? loaded : new NpcDropRecord();
        } catch (IOException e) {
            log.warn("Failed to load drop data", e);
            return new NpcDropRecord();
        }
    }
}
