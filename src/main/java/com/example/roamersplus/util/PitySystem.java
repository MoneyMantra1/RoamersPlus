package com.example.roamersplus.util;

import com.example.roamersplus.RoamersPlusMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Pity System: If a roamer is idle for 2 minutes needing an item they can't obtain,
 * grant them 16 of that item.
 * 
 * Uses WeakHashMap to prevent memory leaks - entries are automatically removed
 * when the entity is garbage collected.
 */
public class PitySystem {
    
    // 2 minutes in milliseconds
    private static final long PITY_TIMEOUT_MS = 2 * 60 * 1000;
    
    // Amount of items to grant
    private static final int PITY_GRANT_AMOUNT = 16;
    
    // Track idle state per entity using WeakHashMap to prevent memory leaks
    // Key: Entity, Value: IdleState
    private static final WeakHashMap<LivingEntity, IdleState> idleTracker = new WeakHashMap<>();
    
    /**
     * Internal class to track idle state
     */
    private static class IdleState {
        long idleStartTime;
        Item neededItem;
        boolean grantedThisCycle;
        
        IdleState(Item neededItem) {
            this.idleStartTime = System.currentTimeMillis();
            this.neededItem = neededItem;
            this.grantedThisCycle = false;
        }
    }
    
    /**
     * Called when a roamer is detected as idle and needing an item.
     * Tracks the idle time and grants items after 2 minutes.
     * 
     * @param entity The roamer entity
     * @param neededItem The item the roamer needs
     * @param inventoryAdder Function to add items to the roamer's inventory
     * @return true if items were granted, false otherwise
     */
    public static boolean trackIdleRoamer(LivingEntity entity, Item neededItem, 
                                          java.util.function.BiConsumer<LivingEntity, ItemStack> inventoryAdder) {
        if (entity == null || neededItem == null) {
            return false;
        }
        
        IdleState state = idleTracker.get(entity);
        
        if (state == null) {
            // First time seeing this entity idle with this need
            idleTracker.put(entity, new IdleState(neededItem));
            return false;
        }
        
        // Check if they're still needing the same item
        if (state.neededItem != neededItem) {
            // They need something different now, reset the timer
            state.idleStartTime = System.currentTimeMillis();
            state.neededItem = neededItem;
            state.grantedThisCycle = false;
            return false;
        }
        
        // Check if we already granted items this cycle
        if (state.grantedThisCycle) {
            return false;
        }
        
        // Check if enough time has passed
        long elapsedTime = System.currentTimeMillis() - state.idleStartTime;
        if (elapsedTime >= PITY_TIMEOUT_MS) {
            // Grant the items
            ItemStack grantedStack = new ItemStack(neededItem, PITY_GRANT_AMOUNT);
            inventoryAdder.accept(entity, grantedStack);
            
            state.grantedThisCycle = true;
            
            RoamersPlusMod.LOGGER.debug("Pity system granted {} x{} to roamer at {}", 
                neededItem.getDescriptionId(), PITY_GRANT_AMOUNT, entity.blockPosition());
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Called when a roamer is no longer idle (working, moving, etc.)
     * Resets their idle tracking state.
     * 
     * @param entity The roamer entity
     */
    public static void clearIdleState(LivingEntity entity) {
        if (entity != null) {
            idleTracker.remove(entity);
        }
    }
    
    /**
     * Cleanup method called when server stops to ensure no memory leaks.
     */
    public static void cleanup() {
        idleTracker.clear();
        RoamersPlusMod.LOGGER.debug("Pity system cleaned up");
    }
    
    /**
     * Gets the current idle time for an entity (for debugging purposes).
     * 
     * @param entity The entity to check
     * @return Idle time in milliseconds, or -1 if not tracked
     */
    public static long getIdleTime(LivingEntity entity) {
        IdleState state = idleTracker.get(entity);
        if (state == null) {
            return -1;
        }
        return System.currentTimeMillis() - state.idleStartTime;
    }
    
    /**
     * Gets the number of currently tracked entities (for debugging).
     */
    public static int getTrackedCount() {
        return idleTracker.size();
    }
}
