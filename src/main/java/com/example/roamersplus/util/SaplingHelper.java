package com.example.roamersplus.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class that maps Roamer races to their corresponding saplings.
 * Based on the wood types used in each race's building structures.
 */
public class SaplingHelper {
    
    private static final Map<String, List<Item>> RACE_SAPLINGS = new HashMap<>();
    
    static {
        // Plains uses Oak (primary) and Birch (secondary in hut2)
        RACE_SAPLINGS.put("PLAINS", List.of(Items.OAK_SAPLING, Items.BIRCH_SAPLING));
        
        // Taiga uses Spruce exclusively
        RACE_SAPLINGS.put("TAIGA", List.of(Items.SPRUCE_SAPLING));
        
        // Jungle uses Jungle (primary) and Oak (secondary in hut2)
        RACE_SAPLINGS.put("JUNGLE", List.of(Items.JUNGLE_SAPLING, Items.OAK_SAPLING));
        
        // Savanna uses Acacia (primary) and Oak (secondary in hut1/hut2)
        RACE_SAPLINGS.put("SAVANNA", List.of(Items.ACACIA_SAPLING, Items.OAK_SAPLING));
        
        // Cherry uses Cherry exclusively
        RACE_SAPLINGS.put("CHERRY", List.of(Items.CHERRY_SAPLING));
        
        // Desert uses sandstone - no wood/saplings
        RACE_SAPLINGS.put("DESERT", Collections.emptyList());
        
        // Badlands uses red sandstone/terracotta - no wood/saplings
        RACE_SAPLINGS.put("BADLANDS", Collections.emptyList());
        
        // Arctic uses snow blocks - no wood/saplings
        RACE_SAPLINGS.put("ARTIC", Collections.emptyList()); // Note: Roamers mod uses "ARTIC" spelling
    }
    
    /**
     * Gets the list of saplings for a given race.
     * @param raceName The race name (e.g., "PLAINS", "TAIGA")
     * @return List of sapling items, or empty list if race doesn't use wood
     */
    public static List<Item> getSaplingsForRace(String raceName) {
        return RACE_SAPLINGS.getOrDefault(raceName.toUpperCase(), Collections.emptyList());
    }
    
    /**
     * Gets the count of saplings to give per type.
     * @return Always returns 8
     */
    public static int getSaplingCount() {
        return 8;
    }
    
    /**
     * Checks if a race uses wood for building.
     * @param raceName The race name
     * @return true if the race uses wood, false otherwise
     */
    public static boolean raceUsesWood(String raceName) {
        List<Item> saplings = RACE_SAPLINGS.get(raceName.toUpperCase());
        return saplings != null && !saplings.isEmpty();
    }
}
