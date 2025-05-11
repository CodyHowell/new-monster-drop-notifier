package com.npcdropnotifier;

import java.util.*;

public class NpcDropRecord {
   private final Map<Integer, Set<String>> itemDrops;

   public NpcDropRecord() {
       this(new HashMap<>());
   }

    public NpcDropRecord(Map<Integer, Set<String>> itemDrops) {
       this.itemDrops = itemDrops;
    }

    public void addDropRecord(Integer itemId, String quantity) {
      this.itemDrops.computeIfAbsent(itemId, k -> new HashSet<>()).add(quantity);
    }

    public Set<String> getItemId(Integer itemId) {
        return this.itemDrops.get(itemId);
    }
}
