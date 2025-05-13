package com.npcdropnotifier;

import com.google.gson.Gson;
import net.runelite.api.*;
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

        NpcDropData.Drop alwaysDrop = new NpcDropData.Drop();
        alwaysDrop.itemId = 554;
        alwaysDrop.name = "Always Item";
        alwaysDrop.quantity = "1";
        alwaysDrop.rarity = "Always";

        NpcDropData.Drop commonDrop = new NpcDropData.Drop();
        commonDrop.itemId = 555;
        commonDrop.name = "Common Item";
        commonDrop.quantity = "1";
        commonDrop.rarity = "1/10";
        commonDrop.minQuantity = 1;
        commonDrop.maxQuantity = 1;

        NpcDropData.Drop variableQuantityDrop = new NpcDropData.Drop();
        variableQuantityDrop.itemId = 557;
        variableQuantityDrop.name = "Variable Item";
        variableQuantityDrop.quantity = "5–10";
        variableQuantityDrop.rarity = "1/10";
        variableQuantityDrop.minQuantity = 5;
        variableQuantityDrop.maxQuantity = 10;

        NpcDropData.Drop multiRollQuantityDrop = new NpcDropData.Drop();
        multiRollQuantityDrop.itemId = 558;
        multiRollQuantityDrop.name = "Variable Item";
        multiRollQuantityDrop.quantity = "5";
        multiRollQuantityDrop.rarity = "2 × 2/10";

        NpcDropData.Drop sameItemQuantity1Drop = new NpcDropData.Drop();
        sameItemQuantity1Drop.itemId = 559;
        sameItemQuantity1Drop.name = "Gold 1";
        sameItemQuantity1Drop.quantity = "5";
        sameItemQuantity1Drop.rarity = "1/10";
        sameItemQuantity1Drop.minQuantity = 5;
        sameItemQuantity1Drop.maxQuantity = 5;

        NpcDropData.Drop sameItemQuantity2Drop = new NpcDropData.Drop();
        sameItemQuantity2Drop.itemId = 559;
        sameItemQuantity2Drop.name = "Gold 2";
        sameItemQuantity2Drop.quantity = "6";
        sameItemQuantity2Drop.rarity = "1/15";
        sameItemQuantity2Drop.minQuantity = 6;
        sameItemQuantity2Drop.maxQuantity = 6;

        dropList = new ArrayList<>();
        dropList.add(alwaysDrop);
        testDrops.put(554, dropList);

        dropList = new ArrayList<>();
        dropList.add(commonDrop);
        testDrops.put(555, dropList);

        dropList = new ArrayList<>();
        dropList.add(variableQuantityDrop);
        testDrops.put(557, dropList);

        dropList = new ArrayList<>();
        dropList.add(multiRollQuantityDrop);
        testDrops.put(558, dropList);

        dropList = new ArrayList<>();
        dropList.add(sameItemQuantity1Drop);
        dropList.add(sameItemQuantity2Drop);
        testDrops.put(559, dropList);

        plugin.currentNpcKey = mockNpc.getName() + "#Level" + mockNpc.getCombatLevel();
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

        // Test finding one of many of the same items with different quantities
        foundDrop = plugin.findDrop(559, 5);
        assertEquals("Gold 1", foundDrop.name);

        foundDrop = plugin.findDrop(559, 6);
        assertEquals("Gold 2", foundDrop.name);

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
    public void testNpcDropDataParseQuantity() {
        NpcDropData.Drop alwaysDrop = plugin.currentNpcDropData.get(554).get(0);
        NpcDropData.Drop variableQuantityDrop = plugin.currentNpcDropData.get(557).get(0);

        // Execute
        alwaysDrop.parseQuantity();
        variableQuantityDrop.parseQuantity();


        // Verify
        assertEquals(1, alwaysDrop.minQuantity);
        assertEquals(1, alwaysDrop.maxQuantity);

        assertEquals(5, variableQuantityDrop.minQuantity);
        assertEquals(10, variableQuantityDrop.maxQuantity);
    }

    @Test
    public void testNpcDropDataParseRarity() {
        NpcDropData.Drop alwaysDrop = plugin.currentNpcDropData.get(554).get(0);
        NpcDropData.Drop regularDrop = plugin.currentNpcDropData.get(555).get(0);
        NpcDropData.Drop multiRollQuantityDrop = plugin.currentNpcDropData.get(558).get(0);

        // Execute
        alwaysDrop.parseRarity();
        regularDrop.parseRarity();
        multiRollQuantityDrop.parseRarity();

        // Verify
        assertEquals(0, alwaysDrop.simplifiedDenominator);
        assertEquals(10, regularDrop.simplifiedDenominator);
        assertEquals(5, multiRollQuantityDrop.simplifiedDenominator);
    }

    @Test
    public void testOnNpcLootReceivedAlreadyDropped() {
        // Setup - add the drop to the record first
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
        NpcDropNotifierPlugin pluginSpy = spy(plugin);

        // Create a mock for NpcDropData
        NpcDropData mockDropData = mock(NpcDropData.class);

        doReturn(mockDropData).when(pluginSpy).readNpcDropData(anyInt(), anyString());
        Map<Integer, List<NpcDropData.Drop>> dropData = new HashMap<>();
        List<NpcDropData.Drop> drops = new ArrayList<>();
        NpcDropData.Drop drop = new NpcDropData.Drop();
        drop.minQuantity = 1;
        drop.maxQuantity = 1;
        drops.add(new NpcDropData.Drop());
        dropData.put(555, drops);
        doReturn(dropData).when(mockDropData).getDropsByItemIdAndQuantity();
        pluginSpy.currentNpcKey = "DifferentMonster#Level50";
        Collection<ItemStack> items = Collections.singletonList(new ItemStack(555, 1));
        NpcLootReceived event = new NpcLootReceived(mockNpc, items);

        // Execute
        pluginSpy.onNpcLootReceived(event);

        // Verify that old record was saved and created a new one
        assertTrue(pluginSpy.npcDropRecord.getItemId(555).contains("1"));
        assertEquals("TestMonster#Level100", plugin.currentNpcKey);
    }

    @Test
    public void testGetDropRateColor() {
        // Test different rarity levels
        assertEquals(DropRateColorConstants.ALWAYS, plugin.getDropRateColor("Always"));
        assertEquals(DropRateColorConstants.COMMON, plugin.getDropRateColor("1/2"));
        assertEquals(DropRateColorConstants.UNCOMMON, plugin.getDropRateColor("1/30"));
        assertEquals(DropRateColorConstants.RARE, plugin.getDropRateColor("1/150"));
        assertEquals(DropRateColorConstants.SUPERRARE, plugin.getDropRateColor("1/2500"));
    }

    @Test
    public void testGetPrettyDropRate() {
        // Test "Always" drop
        NpcDropData.Drop commonDrop = new NpcDropData.Drop();
        commonDrop.rarity = "1/5";
        commonDrop.simplifiedDenominator = 5;
        assertEquals("<br><br><col=" + DropRateColorConstants.COMMON + ">1 / 5</col>",
                plugin.getPrettyDropRate(commonDrop));
    }

    @Test
    public void testGetPrettyNotificationMessage() {
        // Test normal item
        String message = plugin.getPrettyNotificationMessage("TestMonster", 555, 1);
        assertTrue(message.contains("TestMonster:<br><col=ffffff>Test Item</col>"));
        assertTrue(message.contains("1/10"));

        // Test item with quantity
        message = plugin.getPrettyNotificationMessage("TestMonster", 557, 7);
        assertTrue(message.contains("TestMonster:<br><col=ffffff>Test Item (5–10)</col>"));

        // Test item not in drop table
        message = plugin.getPrettyNotificationMessage("TestMonster", 999, 1);
        assertTrue(message.contains("TestMonster:<br><col=ffffff>Test Item</col>"));
    }

    @Test
    public void testSaveAndLoadNpcDrops() throws IOException {
        // Create a temporary directory for testing
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "runelite-test");
        tempDir.mkdirs();

        // Create a test record
        NpcDropRecord testRecord = new NpcDropRecord();
        testRecord.addDropRecord(555, "1");
        testRecord.addDropRecord(557, "1");

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
        assertTrue(loadedRecord.getItemId(557).contains("1"));

        // Clean up
        dataFile.delete();
        tempDir.delete();
    }

    @Test
    public void testReadNpcDropData() throws IOException {
        Integer testNpcId = 555555;
        String testNpcName = "";
        NpcDropData dropData = plugin.readNpcDropData(testNpcId, testNpcName);
        Map<Integer, List<NpcDropData.Drop>> dropMap = dropData.getDropsByItemIdAndQuantity();

        // Verify
        assertNotNull(dropData);
        NpcDropData.Drop bones = dropMap.get(526).get(0);
        assertEquals("Bones", bones.name);
    }
}