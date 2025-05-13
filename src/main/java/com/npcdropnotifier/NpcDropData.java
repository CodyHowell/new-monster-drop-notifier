package com.npcdropnotifier;

import org.apache.commons.math3.fraction.Fraction;

import java.util.*;

public class NpcDropData {
    public String name;
    public List<Drop> drops;

    public Map<Integer, List<Drop>> getDropsByItemIdAndQuantity() {
        Map<Integer, List<Drop>> dropsByItemId = new HashMap<>();
        for (Drop drop : this.drops) {
            if (Objects.equals(drop.name, "Nothing")) {
                continue;
            }
            drop.parseQuantity();
            drop.parseRarity();
            dropsByItemId.computeIfAbsent(drop.itemId, k -> new ArrayList<>()).add(drop);
        }
        return dropsByItemId;
    }

    public static class Drop {
        public int itemId;
        public String name;
        public String quantity;
        public String rarity;

        public int minQuantity;
        public int maxQuantity;

        public boolean hasQuantityRange;

        public int simplifiedDenominator;

        public void parseQuantity() {
            if (quantity.contains("–")) {
                String[] parts = quantity.split("–");
                minQuantity = Integer.parseInt(parts[0].trim());
                maxQuantity = Integer.parseInt(parts[1].trim());
                quantity = quantity.replace("–", "-");
                hasQuantityRange = true;
            } else {
                minQuantity = maxQuantity = Integer.parseInt(quantity.trim());
            }
        }

        public void parseRarity() {
            if (rarity.contains("/")) {
                String dropFraction = rarity;
                if (rarity.contains("×")) {
                    dropFraction = rarity.split("×")[1].trim();
                    rarity = rarity.replace("×", "x");
                }

                String[] parts = dropFraction.split("/");

                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                double rarityDouble = numerator / denominator;

                Fraction rarityFraction = new Fraction(rarityDouble, 1.0e-10, 10000);

                simplifiedDenominator = rarityFraction.getDenominator() / rarityFraction.getNumerator();
            }
        }
    }
}
