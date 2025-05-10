package com.npcdropnotifier;

import com.google.gson.Gson;
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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

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

		for (ItemStack droppedItem : droppedItems) {
			final Integer droppedItemId = droppedItem.getId();
			final Integer droppedItemQuantity = droppedItem.getQuantity();

			if (!Objects.equals(npcDropRecord.getItemId(droppedItemId), droppedItemQuantity)) {
				npcDropRecord.addDropRecord(droppedItemId, droppedItemQuantity);
				String itemName = client.getItemDefinition(droppedItemId).getName();
				npcDropNotifierPopup.addNotificationToQueue(npcName + ": " + itemName);
			}
		}
	}

	private void saveNpcDropsToFile( String npcKey, NpcDropRecord npcDropRecord)
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "new-drop-notifier/" + npcKey);
		if (!dir.exists()) {
			dir.mkdirs();
		}

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
