package com.example.roamersplus.event;

import com.example.roamersplus.RoamersPlusMod;
import com.example.roamersplus.util.PitySystem;
import com.example.roamersplus.util.SaplingHelper;
import com.example.roamersplus.util.SaplingPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Event handler that hooks into Roamers mod entities to add our features.
 * Uses reflection to interact with Roamers mod classes since we can't directly depend on them.
 */
public class RoamerEventHandler {
    
    // Cache for roamer class detection
    private static Class<?> roamerEntityClass = null;
    private static Class<?> abstractCharacterClass = null;
    private static Class<?> playerLikeCharacterClass = null;
    private static boolean classesInitialized = false;
    
    // Track which roamers have been given their initial saplings
    private static final WeakHashMap<Entity, Boolean> saplingsGiven = new WeakHashMap<>();
    
    // Track which roamers have scattered their saplings
    private static final WeakHashMap<Entity, Boolean> saplingsScattered = new WeakHashMap<>();
    
    // Track last known positions for replanting logic
    private static final WeakHashMap<Entity, BlockPos> lastChopPositions = new WeakHashMap<>();
    
    /**
     * Initialize Roamers mod class references via reflection.
     */
    private static void initializeClasses() {
        if (classesInitialized) return;
        
        try {
            roamerEntityClass = Class.forName("net.caitie.roamers.entity.RoamerEntity");
            abstractCharacterClass = Class.forName("net.caitie.roamers.entity.AbstractCharacter");
            playerLikeCharacterClass = Class.forName("net.caitie.roamers.entity.PlayerLikeCharacter");
            classesInitialized = true;
            RoamersPlusMod.LOGGER.info("Successfully loaded Roamers mod classes for integration");
        } catch (ClassNotFoundException e) {
            RoamersPlusMod.LOGGER.warn("Could not find Roamers mod classes. RoamersPlus features will be disabled.");
            classesInitialized = true; // Don't keep trying
        }
    }
    
    /**
     * Check if an entity is a Roamer.
     */
    private static boolean isRoamer(Entity entity) {
        initializeClasses();
        if (roamerEntityClass == null) return false;
        return roamerEntityClass.isInstance(entity);
    }
    
    /**
     * Check if an entity is any PlayerLikeCharacter (Roamer, Bandit, Descendant).
     */
    private static boolean isPlayerLikeCharacter(Entity entity) {
        initializeClasses();
        if (playerLikeCharacterClass == null) return false;
        return playerLikeCharacterClass.isInstance(entity);
    }
    
    /**
     * Get the race name of a roamer via reflection.
     */
    private static String getRaceName(Entity entity) {
        try {
            if (abstractCharacterClass == null) return null;
            
            Method getRaceMethod = abstractCharacterClass.getMethod("getRace");
            Object race = getRaceMethod.invoke(entity);
            if (race != null) {
                return race.toString();
            }
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Could not get race for entity: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Get the roamer's inventory via reflection.
     */
    private static Container getInventory(Entity entity) {
        try {
            if (playerLikeCharacterClass == null) return null;
            
            Method getInventoryMethod = playerLikeCharacterClass.getMethod("getInventory");
            Object inventory = getInventoryMethod.invoke(entity);
            if (inventory instanceof Container) {
                return (Container) inventory;
            }
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Could not get inventory for entity: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Add items to a roamer's inventory via reflection.
     */
    private static boolean addToInventory(Entity entity, ItemStack stack) {
        Container inventory = getInventory(entity);
        if (inventory == null || stack.isEmpty()) return false;
        
        // Find first available slot
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.isEmpty()) {
                inventory.setItem(i, stack.copy());
                return true;
            } else if (ItemStack.isSameItem(slotStack, stack) && 
                       slotStack.getCount() < slotStack.getMaxStackSize()) {
                int canAdd = Math.min(stack.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
                slotStack.grow(canAdd);
                stack.shrink(canAdd);
                if (stack.isEmpty()) return true;
            }
        }
        return false;
    }
    
    /**
     * Get the roamer's home position via reflection.
     */
    private static BlockPos getHomePos(Entity entity) {
        try {
            if (playerLikeCharacterClass == null) return null;
            
            Method getHomePosMethod = playerLikeCharacterClass.getMethod("getHomePos");
            Object pos = getHomePosMethod.invoke(entity);
            if (pos instanceof BlockPos) {
                return (BlockPos) pos;
            }
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Could not get home position for entity: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Get the tree check range from Roamers config via reflection.
     */
    private static int getTreeCheckRange() {
        try {
            Class<?> configClass = Class.forName("net.caitie.roamers.Config");
            Field treeCheckRangeField = configClass.getDeclaredField("treeCheckRange");
            treeCheckRangeField.setAccessible(true);
            
            // Get the CONFIG instance from RoamersMod
            Class<?> roamersModClass = Class.forName("net.caitie.roamers.RoamersMod");
            Field configField = roamersModClass.getDeclaredField("CONFIG");
            configField.setAccessible(true);
            Object configInstance = configField.get(null);
            
            if (configInstance != null) {
                Object value = treeCheckRangeField.get(configInstance);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            }
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Could not get treeCheckRange from config, using default: {}", e.getMessage());
        }
        return 15; // Default fallback
    }
    
    /**
     * Check if a roamer is currently idle/stuck needing an item.
     * Returns the needed item, or null if not idle.
     */
    private static Item getNeededItemIfIdle(Entity entity) {
        try {
            if (playerLikeCharacterClass == null) return null;
            
            // Try to get the wanted building block
            Method getWantedBuildingBlock = playerLikeCharacterClass.getMethod("getWantedBuildingBlock");
            Object wantedBlock = getWantedBuildingBlock.invoke(entity);
            
            if (wantedBlock instanceof BlockState blockState) {
                Block block = blockState.getBlock();
                return block.asItem();
            }
            
            // Also check wanted crafting item
            Method getWantedCraftingItem = playerLikeCharacterClass.getMethod("getWantedCraftingItem");
            Object wantedItem = getWantedCraftingItem.invoke(entity);
            
            if (wantedItem instanceof Item item) {
                return item;
            }
        } catch (Exception e) {
            // Method might not exist or other issue - that's fine
        }
        return null;
    }
    
    /**
     * Check if a roamer is actively working (building, mining, etc.)
     */
    private static boolean isRoamerWorking(Entity entity) {
        try {
            // Check if they have navigation target (moving somewhere)
            if (entity instanceof net.minecraft.world.entity.Mob mob) {
                if (mob.getNavigation().isInProgress()) {
                    return true;
                }
            }
            
            // Could add more checks here for specific activity states
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }
    
    // ==================== EVENT HANDLERS ====================
    
    /**
     * Handle entity spawn - give initial saplings to new roamers.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        
        if (level.isClientSide()) return;
        if (!isRoamer(entity)) return;
        if (saplingsGiven.containsKey(entity)) return;
        
        String raceName = getRaceName(entity);
        if (raceName == null) return;
        
        List<Item> saplings = SaplingHelper.getSaplingsForRace(raceName);
        if (saplings.isEmpty()) {
            saplingsGiven.put(entity, true);
            return;
        }
        
        int count = SaplingHelper.getSaplingCount();
        for (Item sapling : saplings) {
            ItemStack stack = new ItemStack(sapling, count);
            addToInventory(entity, stack);
            RoamersPlusMod.LOGGER.debug("Gave {} {} x{} to {}", 
                entity.getName().getString(), sapling.getDescriptionId(), count, raceName);
        }
        
        saplingsGiven.put(entity, true);
    }
    
    /**
     * Handle entity tick - scatter saplings before excavation and run pity system.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        
        if (entity.level().isClientSide()) return;
        if (!isRoamer(entity)) return;
        
        // Only process every 20 ticks (1 second) to reduce overhead
        if (entity.tickCount % 20 != 0) return;
        
        // Handle sapling scattering (before they start building)
        handleSaplingScattering(entity);
        
        // Handle pity system
        handlePitySystem(entity);
    }
    
    /**
     * Scatter saplings before roamer starts excavating.
     */
    private static void handleSaplingScattering(Entity entity) {
        if (saplingsScattered.getOrDefault(entity, false)) return;
        
        BlockPos homePos = getHomePos(entity);
        if (homePos == null || homePos.equals(BlockPos.ZERO)) return;
        
        // Home position is set - scatter saplings now
        Container inventory = getInventory(entity);
        if (inventory == null) return;
        
        int treeCheckRange = getTreeCheckRange();
        
        // Find and scatter all saplings in inventory
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
                if (blockItem.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
                    int remainder = SaplingPlacementHelper.scatterSaplings(
                        entity.level(), homePos, treeCheckRange, stack
                    );
                    
                    if (remainder > 0) {
                        stack.setCount(remainder);
                    } else {
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
        }
        
        saplingsScattered.put(entity, true);
        RoamersPlusMod.LOGGER.debug("Roamer {} scattered saplings around home at {}", 
            entity.getName().getString(), homePos);
    }
    
    /**
     * Handle pity system for idle roamers.
     */
    private static void handlePitySystem(Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)) return;
        
        // Check if roamer is working
        if (isRoamerWorking(entity)) {
            PitySystem.clearIdleState(livingEntity);
            return;
        }
        
        // Check if they need an item
        Item neededItem = getNeededItemIfIdle(entity);
        if (neededItem == null) {
            PitySystem.clearIdleState(livingEntity);
            return;
        }
        
        // Track idle state and potentially grant items
        PitySystem.trackIdleRoamer(livingEntity, neededItem, (e, stack) -> {
            addToInventory(e, stack);
            RoamersPlusMod.LOGGER.info("Pity system granted {} x{} to idle roamer {}", 
                stack.getItem().getDescriptionId(), stack.getCount(), e.getName().getString());
        });
    }
    
    /**
     * Handle block break events - replant saplings when trees are chopped.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        
        Entity breaker = event.getPlayer();
        if (breaker == null) return;
        
        // Check if a roamer broke this block (they trigger as "players" in some contexts)
        // We need to find nearby roamers who might be chopping
        BlockState state = event.getState();
        if (!state.is(BlockTags.LOGS)) return;
        
        BlockPos pos = event.getPos();
        Level level = (Level) event.getLevel();
        
        // Find roamers near this block break
        AABB searchBox = new AABB(pos).inflate(5);
        List<Entity> nearbyEntities = level.getEntities(null, searchBox);
        
        for (Entity entity : nearbyEntities) {
            if (!isRoamer(entity)) continue;
            
            // This roamer might have chopped this tree
            Block logBlock = state.getBlock();
            Item saplingItem = SaplingPlacementHelper.getSaplingForLog(logBlock);
            
            if (saplingItem != null) {
                // Store the chop position for replanting
                lastChopPositions.put(entity, pos);
                
                // Schedule replanting check
                scheduleReplantCheck(entity, pos, saplingItem, level);
            }
            break;
        }
    }
    
    /**
     * Schedule a check for replanting after sapling drops.
     */
    private static void scheduleReplantCheck(Entity roamer, BlockPos chopPos, Item saplingItem, Level level) {
        // We'll handle this in the tick event by checking for nearby sapling item entities
        // and having the roamer pick them up and replant
        
        // For now, store the association
        // The actual replanting logic will be in handleReplanting called from tick
    }
    
    /**
     * Called periodically to handle replanting logic for roamers.
     */
    public static void handleReplanting(Entity entity, Level level) {
        if (!isRoamer(entity)) return;
        
        BlockPos lastChopPos = lastChopPositions.get(entity);
        if (lastChopPos == null) return;
        
        // Look for nearby sapling item entities
        AABB searchBox = new AABB(lastChopPos).inflate(3);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);
        
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            Item item = stack.getItem();
            
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
                if (blockItem.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
                    // Check if roamer can reach this item
                    double distance = entity.distanceToSqr(itemEntity);
                    if (distance > 25) { // More than 5 blocks away - too far, give up
                        lastChopPositions.remove(entity);
                        return;
                    }
                    
                    // Try to replant
                    if (SaplingPlacementHelper.replantAtStump(level, lastChopPos, item)) {
                        // Success! Remove one sapling from the dropped stack
                        stack.shrink(1);
                        if (stack.isEmpty()) {
                            itemEntity.discard();
                        }
                        
                        lastChopPositions.remove(entity);
                        RoamersPlusMod.LOGGER.debug("Roamer replanted {} at {}", 
                            item.getDescriptionId(), lastChopPos);
                        return;
                    }
                }
            }
        }
        
        // Check if roamer has saplings in inventory to replant
        Container inventory = getInventory(entity);
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                
                Item item = stack.getItem();
                if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
                    if (blockItem.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
                        if (SaplingPlacementHelper.replantAtStump(level, lastChopPos, item)) {
                            stack.shrink(1);
                            lastChopPositions.remove(entity);
                            RoamersPlusMod.LOGGER.debug("Roamer replanted {} from inventory at {}", 
                                item.getDescriptionId(), lastChopPos);
                            return;
                        }
                    }
                }
            }
        }
        
        // If we've been trying for too long, give up (don't freeze the roamer)
        // This is handled by natural timeout - if no saplings found after several ticks, clear
        if (entity.tickCount % 100 == 0) { // After ~5 seconds, give up
            lastChopPositions.remove(entity);
        }
    }
    
    /**
     * Cleanup when server stops.
     */
    public static void cleanup() {
        saplingsGiven.clear();
        saplingsScattered.clear();
        lastChopPositions.clear();
    }
}
