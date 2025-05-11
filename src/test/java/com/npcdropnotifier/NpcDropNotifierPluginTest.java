package com.npcdropnotifier;

import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class NpcDropNotifierPluginTest {

    @Mock
    private Client client;

    @Mock
    private Notifier notifier;

    @Mock
    private ConfigManager configManager;

    @Mock
    private NpcDropNotifierPopup npcDropNotifierPopup;

    @InjectMocks
    private NpcDropNotifierPlugin plugin;

    @Mock
    private NPC mockNpc;

    @Mock
    private ItemComposition mockItemComposition;

    private final Gson gson = new Gson();

    @Before
    public void setUp() {
        // Setup common mocks
        when(mockNpc.getName()).thenReturn("TestMonster");
        when(mockNpc.getCombatLevel()).thenReturn(100);
        when(mockNpc.getId()).thenReturn(1234);

        when(client.getItemDefinition(anyInt())).thenReturn(mockItemComposition);
        when(mockItemComposition.getName()).thenReturn("Test Item");

        // Create a test drop data structure
        Map<Integer, List<NpcDropData.Drop>> testDrops = new HashMap<>();
        List<NpcDropData.Drop> dropList = new ArrayList<>();

        NpcDropData.Drop commonDrop = new NpcDropData.Drop();
        commonDrop.id = 555;
        commonDrop.name = "Common Item";
        commonDrop.quantity = "1";
        commonDrop.rarity = 0.1;
        commonDrop.rolls = 1;
        commonDrop.minQuantity = 1;
        commonDrop.maxQuantity = 1;

        NpcDropData.Drop rareDrop = new NpcDropData.Drop();
        rareDrop.id = 556;
        rareDrop.name = "Rare Item";
        rareDrop.quantity = "1";
        rareDrop.rarity = 0.001;
        rareDrop.rolls = 1;
        rareDrop.minQuantity = 1;
        rareDrop.maxQuantity = 1;

        NpcDropData.Drop variableQuantityDrop = new NpcDropData.Drop();
        variableQuantityDrop.id = 557;
        variableQuantityDrop.name = "Variable Item";
        variableQuantityDrop.quantity = "5-10";
        variableQuantityDrop.rarity = 0.05;
        variableQuantityDrop.rolls = 1;
        variableQuantityDrop.minQuantity = 5;
        variableQuantityDrop.maxQuantity = 10;

        dropList.add(commonDrop);
        testDrops.put(555, dropList);

        dropList = new ArrayList<>();
        dropList.add(rareDrop);
        testDrops.put(556, dropList);

        dropList = new ArrayList<>();
        dropList.add(variableQuantityDrop);
        testDrops.put(557, dropList);

        plugin.currentNpcDropData = testDrops;
        plugin.npcDropRecord = new NpcDropRecord();
    }

    @Test
    public void testFindDrop() {
        // Test finding a drop with exact quantity
        NpcDropData.Drop foundDrop = plugin.findDrop(555, 1);
        assertNotNull("Should find drop with exact quantity", foundDrop);
        assertEquals("Common Item", foundDrop.name);

        // Test finding a drop with variable quantity
        foundDrop = plugin.findDrop(557, 7);
        assertNotNull("Should find drop with quantity in range", foundDrop);
        assertEquals("Variable Item", foundDrop.name);

        // Test not finding a drop with out-of-range quantity
        foundDrop = plugin.findDrop(557, 11);
        assertNull("Should not find drop with quantity out of range", foundDrop);

        // Test not finding a drop with non-existent item ID
        foundDrop = plugin.findDrop(999, 1);
        assertNull("Should not find drop with non-existent item ID", foundDrop);
    }

    @Test
    public void testOnNpcLootReceivedNewDrop() {
        // Setup
        Collection<ItemStack> items = Collections.singletonList(new ItemStack(555, 1));
        NpcLootReceived event = new NpcLootReceived(mockNpc, items);

        // Execute
        plugin.onNpcLootReceived(event);

        // Verify
        verify(npcDropNotifierPopup).addNotificationToQueue(anyString());
        assertTrue(plugin.npcDropRecord.getItemId(555).contains("1"));
    }

    @Test
    public void testOnNpcLootReceivedAlreadyDropped() {
        // Setup - add the drop to the record first
        plugin.currentNpcKey = mockNpc.getName() + "#Level" + mockNpc.getCombatLevel();
        plugin.npcDropRecord.addDropRecord(555, "1");
        Collection<ItemStack> items = Collections.singletonList(new ItemStack(555, 1));
        NpcLootReceived event = new NpcLootReceived(mockNpc, items);

        // Execute
        plugin.onNpcLootReceived(event);

        // Verify - should not add notification for already seen drop
        verify(npcDropNotifierPopup, never()).addNotificationToQueue(anyString());
    }

    @Test
    public void testOnNpcLootReceivedDifferentNpc() {
        // Setup
        plugin.currentNpcKey = "DifferentMonster#Level50";
        // plugin.npcDropRecord = mock(NpcDropRecord.class);
        Collection<ItemStack> items = Collections.singletonList(new ItemStack(555, 1));
        NpcLootReceived event = new NpcLootReceived(mockNpc, items);

        // Execute
        plugin.onNpcLootReceived(event);

        // Verify that we saved the old record and created a new one
        assertTrue(plugin.npcDropRecord.getItemId(555).contains("1"));
        assertEquals("TestMonster#Level100", plugin.currentNpcKey);
    }

    @Test
    public void testGetDropRateColor() {
        // Test different rarity levels
        assertEquals(DropRateColorConstants.ALWAYS, plugin.getDropRateColor(1.0));
        assertEquals(DropRateColorConstants.COMMON, plugin.getDropRateColor(0.05));
        assertEquals(DropRateColorConstants.UNCOMMON, plugin.getDropRateColor(0.02));
        assertEquals(DropRateColorConstants.RARE, plugin.getDropRateColor(0.005));
        assertEquals(DropRateColorConstants.SUPERRARE, plugin.getDropRateColor(0.0005));
    }

    @Test
    public void testGetPrettyDropRate() {
        // Test "Always" drop
        NpcDropData.Drop alwaysDrop = new NpcDropData.Drop();
        alwaysDrop.rarity = 1.0;
        alwaysDrop.rolls = 1;
        assertEquals("<br><br><col=" + DropRateColorConstants.ALWAYS + ">Always</col>",
                plugin.getPrettyDropRate(alwaysDrop));

        // Test normal drop
        NpcDropData.Drop normalDrop = new NpcDropData.Drop();
        normalDrop.rarity = 0.1;
        normalDrop.rolls = 1;
        assertEquals("<br><br><col=" + DropRateColorConstants.COMMON + ">( 1 / 10 )</col>",
                plugin.getPrettyDropRate(normalDrop));

        // Test multiple rolls
        NpcDropData.Drop multiRollDrop = new NpcDropData.Drop();
        multiRollDrop.rarity = 0.01;
        multiRollDrop.rolls = 3;
        assertEquals("<br><br><col=" + DropRateColorConstants.UNCOMMON + ">( 3 x 1 / 100 )</col>",
                plugin.getPrettyDropRate(multiRollDrop));
    }

    @Test
    public void testGetPrettyNotificationMessage() {
        // Test normal item
        String message = plugin.getPrettyNotificationMessage("TestMonster", 555, 1);
        assertTrue(message.contains("TestMonster: Test Item"));
        assertTrue(message.contains("1 / 10"));

        // Test item with quantity
        message = plugin.getPrettyNotificationMessage("TestMonster", 557, 7);
        assertTrue(message.contains("TestMonster: Test Item (5-10)"));

        // Test item not in drop table
        message = plugin.getPrettyNotificationMessage("TestMonster", 999, 1);
        assertEquals("TestMonster: Test Item", message);
    }

    @Test
    public void testSaveAndLoadNpcDrops() throws IOException {
        // Create a temporary directory for testing
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "runelite-test");
        tempDir.mkdirs();

        // Create a test record
        NpcDropRecord testRecord = new NpcDropRecord();
        testRecord.addDropRecord(555, "1");
        testRecord.addDropRecord(556, "1");

        // Save the record
        File dataFile = new File(tempDir, "drop-log.json");
        try (FileWriter writer = new FileWriter(dataFile)) {
            gson.toJson(testRecord, writer);
        }

        // Load the record
        NpcDropRecord loadedRecord;
        try (FileReader reader = new FileReader(dataFile)) {
            loadedRecord = gson.fromJson(reader, NpcDropRecord.class);
        }

        // Verify
        assertNotNull(loadedRecord);
        assertTrue(loadedRecord.getItemId(555).contains("1"));
        assertTrue(loadedRecord.getItemId(556).contains("1"));

        // Clean up
        dataFile.delete();
        tempDir.delete();
    }
}
