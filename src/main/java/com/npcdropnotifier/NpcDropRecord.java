package com.npcdropnotifier;

import java.util.*;

public class NpcDropRecord {
   private final Map<Integer, Set<Integer>> itemDrops;

   public NpcDropRecord() {
       this(new HashMap<>());
   }

    public NpcDropRecord(Map<Integer, Set<Integer>> itemDrops) {
       this.itemDrops = itemDrops;
    }

    public void addDropRecord(Integer itemId, Integer quantity) {
      this.itemDrops.computeIfAbsent(itemId, k -> new HashSet<>()).add(quantity);
    }

    public Set<Integer> getItemId(Integer itemId) {
        return this.itemDrops.get(itemId);
    }
}
