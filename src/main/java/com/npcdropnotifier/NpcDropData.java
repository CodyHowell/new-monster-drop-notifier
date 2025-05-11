package com.npcdropnotifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NpcDropData {
    public List<Drop> drops;

    public Map<Integer, List<Drop>> getDropsByItemIdAndQuantity() {
        Map<Integer, List<Drop>> dropsByItemId = new HashMap<>();
        for (Drop drop : this.drops) {
            drop.parseQuantity();
            dropsByItemId.computeIfAbsent(drop.id, k -> new ArrayList<>()).add(drop);
        }
        return dropsByItemId;
    }

    public static class Drop {
        public int id;
        public String name;
        public boolean members;
        public String quantity;
        public boolean noted;
        public double rarity;
        public int rolls;

        public int minQuantity;
        public int maxQuantity;

        public boolean hasQuantityRange;

        public void parseQuantity() {
            if (quantity.contains("-")) {
                String[] parts = quantity.split("-");
                minQuantity = Integer.parseInt(parts[0].trim());
                maxQuantity = Integer.parseInt(parts[1].trim());
                hasQuantityRange = true;
            } else {
                minQuantity = maxQuantity = Integer.parseInt(quantity.trim());
            }
        }
    }
}
