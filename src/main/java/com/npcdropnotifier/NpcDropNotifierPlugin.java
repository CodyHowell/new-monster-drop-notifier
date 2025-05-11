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
    private final Set<String> monsterDataBeingLoaded = Collections.synchronizedSet(new HashSet<>());

    @Override
    protected void shutDown() throws Exception {
        saveNpcDropsToFile(currentNpcKey, npcDropRecord);
    }

    protected CompletableFuture<Void> runAsyncTask(Runnable task) {
        return CompletableFuture.runAsync(task);
    }

    /// Preload npc drop data in the background before it's needed when the npc dies
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        // Only process if it's an NPC and player-caused damage
        if (!(event.getActor() instanceof NPC) || event.getHitsplat().getHitsplatType() != HitsplatID.DAMAGE_ME) {
            return;
        }

        NPC npc = (NPC) event.getActor();
        final String npcName = npc.getName();
        final int npcLevel = npc.getCombatLevel();
        final int npcId = npc.getId();
        final String npcKey = npcName + "#Level" + npcLevel;

        // Check if the data is getting loaded or the same npc is getting attacked
        if (monsterDataBeingLoaded.contains(npcKey) || npcKey.equals(currentNpcKey)) {
            return;
        }

        // Mark that the monster data is being loaded
        monsterDataBeingLoaded.add(npcKey);

        // Start background download
        runAsyncTask(() -> {
            try {
                synchronized (this) {
                    downloadAndSaveMonsterDropJsonIfNeeded(npcId, npcKey);
                }
            } catch (IOException e) {
                log.warn("Could not download or save npc drop json for {}", npcKey, e);
            } finally {
                // Remove from loading set whether successful or not
                monsterDataBeingLoaded.remove(npcKey);
            }
        });
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


            try {
                downloadAndSaveMonsterDropJsonIfNeeded(npcId, npcKey);
                NpcDropData dropData = this.readNpcDropData(npcKey);
                currentNpcDropData = dropData.getDropsByItemIdAndQuantity();
            } catch (IOException e) {
                log.warn("Could not save npc drop json", e);
            }
        }

        for (ItemStack droppedItem : droppedItems) {
            final Integer droppedItemId = droppedItem.getId();
            final Integer droppedItemQuantity = droppedItem.getQuantity();

            // Skip processing if we don't have drop data
            if (currentNpcDropData == null || !currentNpcDropData.containsKey(droppedItemId)) {
                continue;
            }

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

    String getDropRateColor(Double rarity) {
        if (rarity == 1) {
            return ALWAYS;
        } else if (rarity >= 0.04) {
            return COMMON;
        } else if (rarity >= 0.01) {
            return UNCOMMON;
        } else if (rarity >= 0.001) {
            return RARE;
        } else {
            return SUPERRARE;
        }
    }

    String getPrettyDropRate(NpcDropData.Drop drop) {
        if (drop.rarity == 1) {
            return "<br><br><col=" + getDropRateColor(drop.rarity) + ">Always</col>";
        }

        String formattedDropRate = "";
        int n = (int) Math.round(1.0 / drop.rarity);
        if (drop.rolls != 1) {
            formattedDropRate = drop.rolls + " x " + "(1 " + "/ " + n + ")";
        } else {
            formattedDropRate = "1 " + "/ " + n;
        }

        return "<br><br><col=" + getDropRateColor(drop.rarity) + ">" + formattedDropRate + "</col>";
    }

    String getPrettyNotificationMessage(String npcName, Integer itemId, Integer quantity) {
        String itemName = client.getItemDefinition(itemId).getName();

        NpcDropData.Drop drop = findDrop(itemId, quantity);
        if (drop != null) {
            if (drop.maxQuantity > 1) {
                return npcName + ":<br>" + itemName + " (" + drop.quantity + ")" + this.getPrettyDropRate(drop);
            }
            return npcName + ":<br>" + itemName + this.getPrettyDropRate(drop);
        }
        return npcName + ":<br>" + itemName;
    }

    // File operations

    private File ensureNpcFolderExists(String npcKey) {
        File dir = new File(RuneLite.RUNELITE_DIR, "new-drop-notifier/" + npcKey);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    NpcDropData readNpcDropData(String npcKey) {
        File npcDataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "npc-data.json");
        try (FileReader reader = new FileReader(npcDataFile)) {
            return gson.fromJson(reader, NpcDropData.class);
        } catch (JsonIOException | IOException e) {
            log.warn("Could not load npc drop json");
            return new NpcDropData();
        }
    }

    void downloadAndSaveMonsterDropJsonIfNeeded(int monsterId, String npcKey) throws IOException {
        // Prepare the directory
        this.ensureNpcFolderExists(npcKey);

        // For now only download it once - figure out something to check if stale?
        File dataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "npc-data.json");
        if (dataFile.exists())
            return;

        String urlString = String.format(
                "https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/docs/monsters-json/%d.json",
                monsterId
        );

        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // Check if the request was successful
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log.warn("Failed to download monster data for {}. Response code: {}", npcKey, responseCode);
                return;
            }

            // Download and save the file
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void saveNpcDropsToFile(String npcKey, NpcDropRecord npcDropRecord) {
        File dir = this.ensureNpcFolderExists(npcKey);
        File dataFile = new File(dir, "drop-log.json");
        try (FileWriter writer = new FileWriter(dataFile)) {
            gson.toJson(npcDropRecord, writer);
        } catch (IOException e) {
            log.warn("Failed to save drop data", e);
        }
    }

    private NpcDropRecord loadNpcDropsFromFile(String npcKey) {
        File dataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "drop-log.json");
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
