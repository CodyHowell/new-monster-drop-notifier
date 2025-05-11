package com.npcdropnotifier;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static com.npcdropnotifier.DropRateColorConstants.*;

@Slf4j
@PluginDescriptor(
	name = "NPC Drop Notifier"
)
public class NpcDropNotifierPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject private NpcDropNotifierPopup npcDropNotifierPopup;

	private static final String CONFIG_GROUP = "newdropnotifier";
	private static final String CONFIG_KEY = "monsterDrops";

	private final Gson gson = new Gson();

	// Map of npcName ->  dropRecord
	public String currentNpcKey = "";
	public NpcDropRecord npcDropRecord = null;
	// itemId -> quantity -> Drop
	public Map<Integer, List<NpcDropData.Drop>> currentNpcDropData = null;

	@Override
	protected void shutDown() throws Exception
	{
		saveNpcDropsToFile(currentNpcKey, npcDropRecord);
	}

	@Subscribe
	public void onNpcLootReceived(final NpcLootReceived npcLootReceived)
	{
		final NPC npc = npcLootReceived.getNpc();
		final Collection<ItemStack> droppedItems = npcLootReceived.getItems();
		final String npcName = npc.getName();
		final int npcLevel = npc.getCombatLevel();
		final int npcId = npc.getId();
		final String npcKey = npcName + "#Level" + npcLevel;

        if (!Objects.equals(currentNpcKey, npcKey)) {
			// Save drop record for previous npc
			if(!Objects.equals(currentNpcKey, "")) {
				saveNpcDropsToFile(currentNpcKey, npcDropRecord);
			}

			currentNpcKey =	npcKey;

			// Load new npc drop record
			npcDropRecord = loadNpcDropsFromFile(npcKey);
		}

		try {
			this.downloadAndSaveMonsterDropJson(npcId, npcKey);
		} catch (IOException e) {
			log.warn("Could not save npc drop json");
		}

		if (currentNpcDropData == null) {
			NpcDropData dropData = this.readNpcDropData(npcKey);
			currentNpcDropData = dropData.getDropsByItemIdAndQuantity();
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
		if (!currentNpcDropData.containsKey(itemId)) {
			return null;
		}

		for (NpcDropData.Drop drop : currentNpcDropData.get(itemId)) {
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
			formattedDropRate = drop.rolls + " x " + "1 " + "/ " + n;
		} else {
			formattedDropRate = "1 " + "/ " + n;
		}

		return "<br><br><col=" + getDropRateColor(drop.rarity) + ">( " + formattedDropRate + " )</col>";
	}

	String getPrettyNotificationMessage(String npcName, Integer itemId, Integer quantity) {
		String itemName = client.getItemDefinition(itemId).getName();

		NpcDropData.Drop drop = findDrop(itemId, quantity);
		if(drop != null) {
			if (drop.maxQuantity > 1) {
				return npcName + ": " + itemName + " (" + drop.quantity + ")" + this.getPrettyDropRate(drop);
			}
			return npcName + ": " + itemName + this.getPrettyDropRate(drop);
		}
		return npcName + ": " + itemName;
	}

	// File operations

	private File ensureMonsterFolderExists(String npcKey) {
		File dir = new File(RuneLite.RUNELITE_DIR, "new-drop-notifier/" + npcKey);
		if (!dir.exists()) {
			dir.mkdirs();
		}

		return dir;
	}

	private NpcDropData readNpcDropData(String npcKey) {
		File npcDataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "npc-data.json");
		try (FileReader reader = new FileReader(npcDataFile)) {
			return gson.fromJson(reader, NpcDropData.class);
		} catch (JsonIOException | IOException e) {
			log.warn("Could not load npc drop json");
			return new NpcDropData();
		}
    }

	private void downloadAndSaveMonsterDropJson(int monsterId, String npcKey) throws IOException {
		String urlString = String.format(
				"https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/docs/monsters-json/%d.json",
				monsterId
		);
		URL url = new URL(urlString);

		// Prepare the directory
		File dir = this.ensureMonsterFolderExists(npcKey);

		// For now only download it once - figure out something to check a last updated data and pull every week?
		File dataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "npc-data.json");
		if (dataFile.exists())
			return;

		// Prepare the output file
		File outFile = new File(dir, "npc-data.json");
		Path outPath = outFile.toPath();

		// Open connection and download
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestProperty("User-Agent", "Mozilla/5.0"); // Some servers require this

		try (InputStream in = connection.getInputStream()) {
			Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			connection.disconnect();
		}
	}

	private void saveNpcDropsToFile( String npcKey, NpcDropRecord npcDropRecord)
	{
		File dir = this.ensureMonsterFolderExists(npcKey);
		File dataFile = new File(dir, "drop-log.json");
		try (FileWriter writer = new FileWriter(dataFile))
		{
			gson.toJson(npcDropRecord, writer);
		}
		catch (IOException e)
		{
			log.warn("Failed to save drop data", e);
		}
	}

	private NpcDropRecord loadNpcDropsFromFile(String npcKey)
	{
		File dataFile = new File(RuneLite.RUNELITE_DIR + "/new-drop-notifier/" + npcKey, "drop-log.json");
		if (!dataFile.exists())
			return new NpcDropRecord();

		Type type = new TypeToken<NpcDropRecord>(){}.getType();
		try (FileReader reader = new FileReader(dataFile))
		{
			NpcDropRecord loaded = gson.fromJson(reader, type);
			return loaded != null ? loaded : new NpcDropRecord();
		}
		catch (IOException e)
		{
			log.warn("Failed to load drop data", e);
			return new NpcDropRecord();
		}
	}
}
