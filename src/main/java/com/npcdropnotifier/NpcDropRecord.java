package com.npcdropnotifier;

import java.util.HashMap;
import java.util.Map;

public class NpcDropRecord {
   private final Map<Integer, Integer> itemDrops;

   public NpcDropRecord() {
       this(new HashMap<>());
   }

    public NpcDropRecord(Map<Integer, Integer> itemDrops) {
       this.itemDrops = itemDrops;
    }

    public void addDropRecord(Integer itemId, Integer quantity) {
        this.itemDrops.put(itemId, quantity);
    }

    public Integer getItemId(Integer itemId) {
        return this.itemDrops.get(itemId);
    }
}
