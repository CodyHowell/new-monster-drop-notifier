package com.npcdropnotifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NpcDropData {
    public List<Drop> drops;

    public Map<Integer, Map<String, Drop>> getDropsByItemIdAndQuantity() {
        Map<Integer, Map<String, Drop>> map = new HashMap<>();
        if (drops == null) {
            return map;
        }
        for (Drop drop : drops) {
            map
                .computeIfAbsent(drop.id, k -> new HashMap<>())
                .put(drop.quantity, drop);
        }
        return map;
    }

    public static class Drop {
        public int id;
        public String name;
        public boolean members;
        public String quantity;
        public boolean noted;
        public double rarity;
        public int rolls;
    }
}
