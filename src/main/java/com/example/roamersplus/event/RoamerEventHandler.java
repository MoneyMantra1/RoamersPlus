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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Event handler that hooks into Roamers mod entities to add our features.
 * Uses reflection to interact with Roamers mod classes since we can't directly depend on them.
 * 
 * Key features added by RoamersPlus:
 * - Initial sapling/bonemeal distribution based on race
 * - Periodic sapling planting with type rotation
 * - Bonemeal application to planted saplings
 * - Pity system for stuck roamers (grants needed items after timeout)
 * - Stuck detection and recovery for navigation issues
 * - Crafting table placement assistance
 */
public class RoamerEventHandler {
    
    // Cache for roamer class detection
    private static Class<?> roamerEntityClass = null;
    private static Class<?> abstractCharacterClass = null;
    private static Class<?> playerLikeCharacterClass = null;
    private static Class<?> craftingManagerClass = null;
    private static Class<?> landClass = null;
    private static boolean classesInitialized = false;
    
    // Track which roamers have been given their initial saplings
    private static final WeakHashMap<Entity, Boolean> saplingsGiven = new WeakHashMap<>();
    
    // Track which roamers have scattered their saplings
    private static final WeakHashMap<Entity, Boolean> saplingsScattered = new WeakHashMap<>();
    
    // Track last known positions for replanting logic
    private static final WeakHashMap<Entity, BlockPos> lastChopPositions = new WeakHashMap<>();

    // Delay replanting checks so sapling drops have time to appear (entity.tickCount when replanting is allowed)
    private static final WeakHashMap<Entity, Integer> replantStartTicks = new WeakHashMap<>();
    // Alternation index per roamer for sapling placement (helps enforce multi-type planting)
    private static final WeakHashMap<Entity, Integer> saplingAltIndex = new WeakHashMap<>();
    // Pending saplings to bonemeal per roamer
    private static final WeakHashMap<Entity, List<BlockPos>> pendingBonemeal = new WeakHashMap<>();
    // Snapshot of sapling counts (used to safely refund saplings when swapping types on place events)
    private static final WeakHashMap<Entity, Map<Item, Integer>> lastSaplingCounts = new WeakHashMap<>();
    
    // Stuck detection: track positions to detect if roamer is stuck
    private static final WeakHashMap<Entity, BlockPos> lastPositions = new WeakHashMap<>();
    private static final WeakHashMap<Entity, Integer> stuckCounter = new WeakHashMap<>();
    private static final int STUCK_THRESHOLD = 30; // ~30 seconds of not moving while wanting to move
    
    // Track if we've helped place a crafting table for a roamer
    private static final WeakHashMap<Entity, Boolean> craftingTablePlaced = new WeakHashMap<>();
/**
     * Initialize Roamers mod class references via reflection.
     */
    private static void initializeClasses() {
        if (classesInitialized) return;
        
        try {
            roamerEntityClass = Class.forName("net.caitie.roamers.entity.RoamerEntity");
            abstractCharacterClass = Class.forName("net.caitie.roamers.entity.AbstractCharacter");
            playerLikeCharacterClass = Class.forName("net.caitie.roamers.entity.PlayerLikeCharacter");
            
            // Additional classes for extended functionality
            try {
                craftingManagerClass = Class.forName("net.caitie.roamers.util.CraftingManager");
                landClass = Class.forName("net.caitie.roamers.entity.ai.building.Land");
            } catch (ClassNotFoundException e) {
                RoamersPlusMod.LOGGER.debug("Optional Roamers classes not found: {}", e.getMessage());
            }
            
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

            // Try to get the wanted building block (Roamers returns Block in 2.1+; older builds may return BlockState)
            Method getWantedBuildingBlock = playerLikeCharacterClass.getMethod("getWantedBuildingBlock");
            Object wantedBlock = getWantedBuildingBlock.invoke(entity);

            if (wantedBlock instanceof BlockState blockState) {
                return blockState.getBlock().asItem();
            }
            if (wantedBlock instanceof Block block) {
                return block.asItem();
            }

            // Also check wanted crafting item (may be Item or ItemStack depending on Roamers version)
            Method getWantedCraftingItem = playerLikeCharacterClass.getMethod("getWantedCraftingItem");
            Object wantedItem = getWantedCraftingItem.invoke(entity);

            if (wantedItem instanceof Item item) {
                return item;
            }
            if (wantedItem instanceof ItemStack stack) {
                return stack.getItem();
            }
        } catch (Exception e) {
            // Method might not exist or other issue - that's fine
        }
        return null;
    }

    /**
     * Check if a roamer is actively working (building, mining, navigating, etc.)
     * We don't want to interfere with roamers when they're busy.
     */
    private static boolean isRoamerWorking(Entity entity) {
        try {
            // Check if they have navigation target (moving somewhere)
            if (entity instanceof net.minecraft.world.entity.Mob mob) {
                if (mob.getNavigation().isInProgress()) {
                    return true;
                }
            }
            
            // Check current activity via reflection
            if (playerLikeCharacterClass != null) {
                try {
                    Method getCurrentActivity = playerLikeCharacterClass.getMethod("getCurrentActivity");
                    Object activity = getCurrentActivity.invoke(entity);
                    if (activity != null) {
                        String activityName = activity.toString();
                        // IDLE is fine to interrupt, but not other activities
                        if (!activityName.equals("IDLE")) {
                            return true;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist in this version, ignore
                }
                
                // Check if they want a specific building block (actively building)
                try {
                    Method getWantedBuildingBlock = playerLikeCharacterClass.getMethod("getWantedBuildingBlock");
                    Object wanted = getWantedBuildingBlock.invoke(entity);
                    if (wanted != null) {
                        return true; // They need materials = actively building
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist in this version, ignore
                }
            }
        } catch (Exception e) {
            // If we can't determine, assume not working (fail-safe)
            RoamersPlusMod.LOGGER.debug("Could not check if roamer is working: {}", e.getMessage());
        }
        return false;
    }
    
    // ==================== EVENT HANDLERS ====================
    
    /**
     * Handle entity spawn - give initial saplings and bonemeal to new roamers.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        try {
            Entity entity = event.getEntity();
            Level level = event.getLevel();
            
            if (level.isClientSide()) return;
            if (!isRoamer(entity)) return;
            if (saplingsGiven.containsKey(entity)) return;
            
            String raceName = getRaceName(entity);
            if (raceName == null) return;
            
            List<Item> saplings = SaplingHelper.getSaplingsForRace(raceName);
            
            // Give saplings if this race uses wood
            if (!saplings.isEmpty()) {
                int count = SaplingHelper.getSaplingCount();
                for (Item sapling : saplings) {
                    ItemStack stack = new ItemStack(sapling, count);
                    addToInventory(entity, stack);
                    RoamersPlusMod.LOGGER.debug("Gave {} {} x{} to {}", 
                        entity.getName().getString(), sapling.getDescriptionId(), count, raceName);
                }
                
                // Give bonemeal to races that use saplings
                ItemStack bonemealStack = new ItemStack(SaplingHelper.getBonemeal(), SaplingHelper.getBonemealCount());
                addToInventory(entity, bonemealStack);
                RoamersPlusMod.LOGGER.debug("Gave {} bonemeal x{} to {}", 
                    entity.getName().getString(), SaplingHelper.getBonemealCount(), raceName);
            }
            
            saplingsGiven.put(entity, true);
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.error("Error in onEntityJoinLevel: {}", e.getMessage());
        }
    }
    
    /**
     * Handle entity tick - scatter saplings before excavation and run pity system.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        try {
            Entity entity = event.getEntity();
            
            if (entity.level().isClientSide()) return;
            if (!isRoamer(entity)) return;
            
            // Only process every 20 ticks (1 second) to reduce overhead
            if (entity.tickCount % 20 != 0) return;
            
            // Handle sapling scattering (before they start building)
            handleSaplingScattering(entity);

            // Snapshot sapling counts and process any queued bonemeal for saplings this roamer placed.
            Container invForBonemeal = getInventory(entity);
            if (invForBonemeal != null) {
                updateSaplingCountSnapshot(entity, invForBonemeal);
                processBonemealQueue(entity, entity.level(), invForBonemeal);
                
                // Rotate saplings in inventory every ~30 seconds so different types get used
                if (entity.tickCount % 600 == 0) {
                    rotateSaplingsInInventory(entity, invForBonemeal);
                }
                
                // Periodically plant saplings from inventory (every ~10 seconds)
                // This ensures roamers actively use ALL their sapling types over time
                if (entity.tickCount % 200 == 0) {
                    plantSaplingsFromInventory(entity, entity.level(), invForBonemeal);
                }
                
                // Check if roamer needs a crafting table and help place one
                if (entity.tickCount % 100 == 0) {
                    helpWithCraftingTable(entity, entity.level(), invForBonemeal);
                }
            }

            // Handle stuck detection and recovery
            handleStuckDetection(entity);

            // Handle replanting after chopping
            if (lastChopPositions.containsKey(entity)) {
                Integer startTick = replantStartTicks.get(entity);
                if (startTick == null || entity.tickCount >= startTick) {
                    handleReplanting(entity, entity.level());
                }
            }
            
            // Handle pity system
            handlePitySystem(entity);
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.error("Error in onEntityTick: {}", e.getMessage());
        }
    }
    
    /**
     * Actively plants saplings from the roamer's inventory around their home.
     * Plants up to 2 saplings per call, using the first sapling type found (which rotates over time).
     * 
     * Safety checks:
     * - Only plants if roamer has established home
     * - Avoids planting when roamer is busy (navigating/working)
     * - Respects spacing to avoid clustering saplings
     * - Limits bonemeal queue size to prevent memory issues
     */
    private static void plantSaplingsFromInventory(Entity entity, Level level, Container inventory) {
        if (level.isClientSide()) return;
        
        // Safety: Don't plant if roamer is actively working/moving
        if (isRoamerWorking(entity)) return;
        
        BlockPos homePos = getHomePos(entity);
        if (homePos == null || homePos.equals(BlockPos.ZERO)) return;
        
        // Safety: Don't plant if roamer is too far from home (they might be exploring/gathering)
        double distanceFromHome = entity.blockPosition().distSqr(homePos);
        if (distanceFromHome > 400) return; // More than 20 blocks away
        
        // Safety: Limit pending bonemeal queue size to prevent memory bloat
        List<BlockPos> bonemealQueue = pendingBonemeal.get(entity);
        if (bonemealQueue != null && bonemealQueue.size() > 50) {
            // Too many pending - let them grow naturally or wait for bonemealing to catch up
            return;
        }
        
        // Find the first sapling stack in inventory (rotation ensures variety)
        ItemStack saplingStack = ItemStack.EMPTY;
        int saplingSlot = -1;
        Block saplingBlock = null;
        
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            if (item instanceof BlockItem blockItem) {
                if (blockItem.getBlock() instanceof SaplingBlock) {
                    saplingStack = stack;
                    saplingSlot = i;
                    saplingBlock = blockItem.getBlock();
                    break;
                }
            }
        }
        
        if (saplingStack.isEmpty() || saplingBlock == null) return;
        
        // Get tree check range for placement radius
        int radius = getTreeCheckRange();
        
        // Try to plant up to 2 saplings per tick
        int planted = 0;
        int maxToPlant = Math.min(2, saplingStack.getCount());
        
        // Generate random positions around home
        java.util.Random random = new java.util.Random();
        
        for (int attempt = 0; attempt < 15 && planted < maxToPlant; attempt++) {
            // Random position within radius, but not too close to center (leave building area clear)
            int x = random.nextInt(radius * 2 + 1) - radius;
            int z = random.nextInt(radius * 2 + 1) - radius;
            
            // Skip if too close to center (building area) or outside circular radius
            if ((Math.abs(x) <= 4 && Math.abs(z) <= 4) || (x * x + z * z > radius * radius)) {
                continue;
            }
            
            BlockPos basePos = homePos.offset(x, 0, z);
            BlockPos validPos = SaplingPlacementHelper.findValidSaplingPositionPublic(level, basePos);
            
            if (validPos != null) {
                // Safety: Check there isn't already a sapling nearby (prevent clustering)
                if (hasSaplingNearby(level, validPos, 2)) {
                    continue;
                }
                
                // Check there isn't already a sapling or tree here
                BlockState existingState = level.getBlockState(validPos);
                if (existingState.isAir() || existingState.canBeReplaced()) {
                    level.setBlock(validPos, saplingBlock.defaultBlockState(), 3);
                    saplingStack.shrink(1);
                    planted++;
                    
                    // Queue for bonemeal
                    queueForBonemeal(entity, validPos);
                    
                    RoamersPlusMod.LOGGER.debug("Roamer {} planted {} at {}", 
                        entity.getName().getString(), saplingBlock.getName().getString(), validPos);
                }
            }
        }
        
        // Update inventory if stack is empty
        if (saplingSlot >= 0 && saplingStack.isEmpty()) {
            inventory.setItem(saplingSlot, ItemStack.EMPTY);
        }
    }
    
    /**
     * Checks if there's already a sapling within the given radius of a position.
     * Used to prevent clustering saplings too close together.
     */
    private static boolean hasSaplingNearby(Level level, BlockPos pos, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos checkPos = pos.offset(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    if (state.getBlock() instanceof SaplingBlock) {
                        return true;
                    }
                    // Also check for logs (existing trees)
                    if (state.is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Rotates sapling stacks in the roamer's inventory so different types get used.
     * This works around the base Roamers mod always selecting the first matching sapling.
     */
    private static void rotateSaplingsInInventory(Entity entity, Container inventory) {
        // Find all sapling slots and their types
        List<Integer> saplingSlots = new ArrayList<>();
        List<ItemStack> saplingStacks = new ArrayList<>();
        
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            if (item instanceof BlockItem blockItem) {
                if (blockItem.getBlock() instanceof SaplingBlock) {
                    saplingSlots.add(i);
                    saplingStacks.add(stack.copy());
                }
            }
        }
        
        // Need at least 2 different sapling slots to rotate
        if (saplingSlots.size() < 2) return;
        
        // Rotate: move first to end, shift others up
        ItemStack first = saplingStacks.remove(0);
        saplingStacks.add(first);
        
        // Apply rotated stacks back to inventory
        for (int i = 0; i < saplingSlots.size(); i++) {
            inventory.setItem(saplingSlots.get(i), saplingStacks.get(i));
        }
        
        RoamersPlusMod.LOGGER.debug("Rotated {} sapling stacks in {}'s inventory", 
            saplingSlots.size(), entity.getName().getString());
    }
    
    /**
     * Scatter saplings before roamer starts excavating.
     * Uses alternating placement to distribute different sapling types evenly.
     * Also applies bonemeal to the placed saplings.
     */
    private static void handleSaplingScattering(Entity entity) {
        if (saplingsScattered.getOrDefault(entity, false)) return;
        
        BlockPos homePos = getHomePos(entity);
        if (homePos == null || homePos.equals(BlockPos.ZERO)) return;
        
        // Home position is set - scatter saplings now
        Container inventory = getInventory(entity);
        if (inventory == null) return;
        
        int treeCheckRange = getTreeCheckRange();
        
        // Collect all sapling stacks from inventory
        List<ItemStack> saplingStacks = new ArrayList<>();
        List<Integer> saplingSlots = new ArrayList<>();
        ItemStack bonemealStack = ItemStack.EMPTY;
        int bonemealSlot = -1;
        
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            
            // Check for bonemeal
            if (item == net.minecraft.world.item.Items.BONE_MEAL) {
                bonemealStack = stack;
                bonemealSlot = i;
                continue;
            }
            
            // Check for saplings
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
                if (blockItem.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
                    saplingStacks.add(stack);
                    saplingSlots.add(i);
                }
            }
        }
        
        if (!saplingStacks.isEmpty()) {
            RoamersPlusMod.LOGGER.info("Roamer {} scattering {} sapling types with {} bonemeal around {}", 
                entity.getName().getString(), saplingStacks.size(), 
                bonemealStack.isEmpty() ? 0 : bonemealStack.getCount(), homePos);
            
            // Use alternating scatter method with bonemeal
            SaplingPlacementHelper.scatterSaplingsAlternating(
                entity.level(), homePos, treeCheckRange, saplingStacks, bonemealStack
            );
            
            // Update inventory with remaining saplings (stacks were modified in place)
            for (int i = 0; i < saplingStacks.size(); i++) {
                ItemStack stack = saplingStacks.get(i);
                int slot = saplingSlots.get(i);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
            
            // Update bonemeal slot if it was used
            if (bonemealSlot >= 0 && bonemealStack.isEmpty()) {
                inventory.setItem(bonemealSlot, ItemStack.EMPTY);
            }
        }
        
        saplingsScattered.put(entity, true);
        RoamersPlusMod.LOGGER.debug("Roamer {} finished scattering saplings around home at {}", 
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
        try {
            if (event.getLevel().isClientSide()) return;
            
            Entity breaker = event.getPlayer();
            if (breaker == null) return;

            // Only react to Roamers' internal fake player block-breaking (avoid triggering off real players)
            if (!(breaker instanceof FakePlayer)) return;
            
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
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.error("Error in onBlockBreak: {}", e.getMessage());
        }
    }
    
    /**
     * Schedule a check for replanting after sapling drops.
     */
    private static void scheduleReplantCheck(Entity roamer, BlockPos chopPos, Item saplingItem, Level level) {
        // Give the world a moment to spawn item drops before we search for saplings to replant.
        // We schedule the first eligible check a couple seconds later, then handleReplanting will run from the tick event.
        replantStartTicks.put(roamer, roamer.tickCount + 40); // ~2 seconds
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
                        replantStartTicks.remove(entity);
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
                        replantStartTicks.remove(entity);
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
                        replantStartTicks.remove(entity);
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
                        replantStartTicks.remove(entity);
        }
    }
    
    /**
     * Updates the snapshot of sapling counts in the roamer's inventory.
     * Used to track sapling usage for alternation enforcement.
     */
    private static void updateSaplingCountSnapshot(Entity entity, Container inventory) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item instanceof BlockItem blockItem) {
                if (blockItem.getBlock() instanceof SaplingBlock) {
                    counts.merge(item, stack.getCount(), Integer::sum);
                }
            }
        }
        lastSaplingCounts.put(entity, counts);
    }
    
    /**
     * Processes the bonemeal queue for a roamer - applies bonemeal to saplings they've placed.
     * Includes safety measures to prevent memory issues and stale entries.
     */
    private static void processBonemealQueue(Entity entity, Level level, Container inventory) {
        List<BlockPos> queue = pendingBonemeal.get(entity);
        if (queue == null || queue.isEmpty()) return;
        
        if (!(level instanceof ServerLevel serverLevel)) return;
        
        // Safety: Limit queue size - remove oldest entries if too large
        while (queue.size() > 100) {
            queue.remove(0);
        }
        
        // Safety: Clean up stale entries (saplings that no longer exist)
        // Do this occasionally to prevent buildup
        if (entity.tickCount % 100 == 0) {
            queue.removeIf(pos -> {
                BlockState state = level.getBlockState(pos);
                return !(state.getBlock() instanceof SaplingBlock);
            });
        }
        
        // Process up to 3 sapling positions per tick to avoid lag
        int positionsProcessed = 0;
        int bonemealUsed = 0;
        int bonemealPerSapling = 2; // Apply 2 bonemeal per sapling per tick
        
        Iterator<BlockPos> iter = queue.iterator();
        while (iter.hasNext() && positionsProcessed < 3) {
            BlockPos pos = iter.next();
            BlockState state = level.getBlockState(pos);
            
            // Check if still a sapling
            if (!(state.getBlock() instanceof SaplingBlock saplingBlock)) {
                iter.remove(); // Sapling is gone (grew or was broken)
                continue;
            }
            
            // Apply bonemeal multiple times to this sapling
            for (int i = 0; i < bonemealPerSapling; i++) {
                // Find bonemeal in inventory (search each time as slot contents may change)
                int bonemealSlot = -1;
                ItemStack bonemealStack = ItemStack.EMPTY;
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (!stack.isEmpty() && stack.getItem() == Items.BONE_MEAL) {
                        bonemealSlot = slot;
                        bonemealStack = stack;
                        break;
                    }
                }
                
                if (bonemealStack.isEmpty()) {
                    // No more bonemeal - don't clear queue, just stop processing
                    // The roamer might get more bonemeal later
                    if (bonemealUsed > 0) {
                        RoamersPlusMod.LOGGER.debug("Roamer ran out of bonemeal after using {}", bonemealUsed);
                    }
                    return;
                }
                
                // Check if sapling still exists (might have grown from previous bonemeal)
                if (!(level.getBlockState(pos).getBlock() instanceof SaplingBlock)) {
                    break; // Tree grew!
                }
                
                // Apply bonemeal
                saplingBlock.advanceTree(serverLevel, pos, level.getBlockState(pos), serverLevel.random);
                bonemealStack.shrink(1);
                bonemealUsed++;
                
                // Update inventory slot if stack is empty
                if (bonemealStack.isEmpty()) {
                    inventory.setItem(bonemealSlot, ItemStack.EMPTY);
                }
            }
            
            iter.remove();
            positionsProcessed++;
        }
        
        if (bonemealUsed > 0) {
            RoamersPlusMod.LOGGER.debug("Applied {} bonemeal to saplings ({} positions processed)", 
                bonemealUsed, positionsProcessed);
        }
    }
    
    /**
     * Queues a sapling position for bonemeal application.
     */
    public static void queueForBonemeal(Entity roamer, BlockPos saplingPos) {
        pendingBonemeal.computeIfAbsent(roamer, k -> new ArrayList<>()).add(saplingPos);
    }
    
    /**
     * Gets the alternation index for a roamer's sapling placement.
     */
    public static int getSaplingAltIndex(Entity roamer) {
        return saplingAltIndex.getOrDefault(roamer, 0);
    }
    
    /**
     * Increments the alternation index for a roamer's sapling placement.
     */
    public static void incrementSaplingAltIndex(Entity roamer, int numTypes) {
        int current = saplingAltIndex.getOrDefault(roamer, 0);
        saplingAltIndex.put(roamer, (current + 1) % Math.max(1, numTypes));
    }
    
    // ==================== STUCK DETECTION AND RECOVERY ====================
    
    /**
     * Detects if a roamer is stuck (not moving but should be) and attempts recovery.
     * 
     * Common causes of stuck roamers in the base mod:
     * - Navigation path blocked and not recalculating
     * - Waiting for crafting item that can't be crafted (missing ingredient)
     * - Building position unreachable
     */
    private static void handleStuckDetection(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)) return;
        
        BlockPos currentPos = entity.blockPosition();
        BlockPos lastPos = lastPositions.get(entity);
        
        // Check if roamer is supposed to be moving
        boolean shouldBeMoving = mob.getNavigation().isInProgress();
        
        if (shouldBeMoving) {
            if (lastPos != null && currentPos.equals(lastPos)) {
                // Roamer hasn't moved but should be
                int count = stuckCounter.getOrDefault(entity, 0) + 1;
                stuckCounter.put(entity, count);
                
                if (count >= STUCK_THRESHOLD) {
                    // Roamer is stuck - attempt recovery
                    RoamersPlusMod.LOGGER.info("Roamer {} appears stuck at {}, attempting recovery",
                        entity.getName().getString(), currentPos);
                    
                    attemptStuckRecovery(entity, mob);
                    stuckCounter.put(entity, 0); // Reset counter
                }
            } else {
                // Roamer is moving, reset counter
                stuckCounter.put(entity, 0);
            }
        } else {
            // Not trying to move, reset counter
            stuckCounter.put(entity, 0);
        }
        
        lastPositions.put(entity, currentPos);
    }
    
    /**
     * Attempts to recover a stuck roamer by clearing their navigation and resetting state.
     */
    private static void attemptStuckRecovery(Entity entity, net.minecraft.world.entity.Mob mob) {
        try {
            // Stop current navigation
            mob.getNavigation().stop();
            
            // Try to clear any stuck crafting state via reflection
            if (playerLikeCharacterClass != null) {
                try {
                    Method setWantedCraftingItem = playerLikeCharacterClass.getMethod("setWantedCraftingItem", Item.class);
                    setWantedCraftingItem.invoke(entity, (Object) null);
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist in this version
                }
                
                // Try to reset activity to IDLE
                try {
                    Class<?> activityClass = Class.forName("net.caitie.roamers.entity.PlayerLikeCharacter$Activity");
                    Object idleActivity = null;
                    for (Object constant : activityClass.getEnumConstants()) {
                        if (constant.toString().equals("IDLE")) {
                            idleActivity = constant;
                            break;
                        }
                    }
                    if (idleActivity != null) {
                        Method setActivity = playerLikeCharacterClass.getMethod("setCurrentActivity", activityClass);
                        setActivity.invoke(entity, idleActivity);
                    }
                } catch (Exception e) {
                    RoamersPlusMod.LOGGER.debug("Could not reset activity: {}", e.getMessage());
                }
            }
            
            RoamersPlusMod.LOGGER.debug("Stuck recovery attempted for {}", entity.getName().getString());
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Error during stuck recovery: {}", e.getMessage());
        }
    }
    
    // ==================== CRAFTING TABLE ASSISTANCE ====================
    
    /**
     * Helps roamers by placing a crafting table if they need one for crafting.
     * 
     * The base mod requires roamers to have access to a crafting table for most recipes,
     * but sometimes they don't have one placed or can't reach it. This helps by:
     * 1. Detecting if the roamer needs to craft but has no crafting table
     * 2. Placing one from their inventory if available
     * 3. Or granting one if they've been stuck needing to craft for too long
     */
    private static void helpWithCraftingTable(Entity entity, Level level, Container inventory) {
        if (level.isClientSide()) return;
        
        // Check if roamer wants to craft something
        Item wantedItem = getWantedCraftingItem(entity);
        if (wantedItem == null) return;
        
        // Check if they have a crafting table position set
        BlockPos craftingTablePos = getCraftingTablePos(entity);
        if (craftingTablePos != null && !craftingTablePos.equals(BlockPos.ZERO)) {
            // They have a crafting table, check if it still exists
            BlockState state = level.getBlockState(craftingTablePos);
            if (state.getBlock() == Blocks.CRAFTING_TABLE) {
                return; // All good
            }
        }
        
        // Roamer needs to craft but may not have a crafting table
        // Check if they have one in inventory
        boolean hasCraftingTable = false;
        int craftingTableSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.CRAFTING_TABLE) {
                hasCraftingTable = true;
                craftingTableSlot = i;
                break;
            }
        }
        
        BlockPos homePos = getHomePos(entity);
        if (homePos == null || homePos.equals(BlockPos.ZERO)) return;
        
        if (hasCraftingTable && craftingTableSlot >= 0) {
            // Try to place the crafting table near their home
            BlockPos placePos = findValidCraftingTablePos(level, homePos);
            if (placePos != null) {
                level.setBlock(placePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                inventory.getItem(craftingTableSlot).shrink(1);
                if (inventory.getItem(craftingTableSlot).isEmpty()) {
                    inventory.setItem(craftingTableSlot, ItemStack.EMPTY);
                }
                
                // Update the roamer's crafting table position
                setCraftingTablePos(entity, placePos);
                
                RoamersPlusMod.LOGGER.info("Placed crafting table for {} at {}", 
                    entity.getName().getString(), placePos);
            }
        } else if (!craftingTablePlaced.getOrDefault(entity, false)) {
            // They don't have a crafting table - grant one after they've been waiting
            // (This is handled by the pity system for the item they want to craft,
            // but we can also help by giving them a crafting table)
            BlockPos placePos = findValidCraftingTablePos(level, homePos);
            if (placePos != null) {
                level.setBlock(placePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                setCraftingTablePos(entity, placePos);
                craftingTablePlaced.put(entity, true);
                
                RoamersPlusMod.LOGGER.info("Granted and placed crafting table for {} at {}", 
                    entity.getName().getString(), placePos);
            }
        }
    }
    
    /**
     * Gets the item a roamer wants to craft via reflection.
     */
    private static Item getWantedCraftingItem(Entity entity) {
        try {
            if (playerLikeCharacterClass == null) return null;
            Method getWantedCraftingItem = playerLikeCharacterClass.getMethod("getWantedCraftingItem");
            Object result = getWantedCraftingItem.invoke(entity);
            if (result instanceof Item item) {
                return item;
            }
        } catch (Exception e) {
            // Method might not exist
        }
        return null;
    }
    
    /**
     * Gets the roamer's crafting table position via reflection.
     */
    private static BlockPos getCraftingTablePos(Entity entity) {
        try {
            if (playerLikeCharacterClass == null) return null;
            Field craftingTablePosField = playerLikeCharacterClass.getDeclaredField("craftingTablePos");
            craftingTablePosField.setAccessible(true);
            Object pos = craftingTablePosField.get(entity);
            if (pos instanceof BlockPos blockPos) {
                return blockPos;
            }
        } catch (Exception e) {
            // Field might not exist
        }
        return null;
    }
    
    /**
     * Sets the roamer's crafting table position via reflection.
     */
    private static void setCraftingTablePos(Entity entity, BlockPos pos) {
        try {
            if (playerLikeCharacterClass == null) return;
            Field craftingTablePosField = playerLikeCharacterClass.getDeclaredField("craftingTablePos");
            craftingTablePosField.setAccessible(true);
            craftingTablePosField.set(entity, pos);
        } catch (Exception e) {
            RoamersPlusMod.LOGGER.debug("Could not set crafting table pos: {}", e.getMessage());
        }
    }
    
    /**
     * Finds a valid position to place a crafting table near the given position.
     */
    private static BlockPos findValidCraftingTablePos(Level level, BlockPos center) {
        // Search in a small area around the center
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos checkPos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    BlockState below = level.getBlockState(checkPos.below());
                    
                    // Need air at position and solid ground below
                    if (state.isAir() && below.isSolid()) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Cleanup when server stops.
     */
    public static void cleanup() {
        saplingsGiven.clear();
        saplingsScattered.clear();
        lastChopPositions.clear();
        replantStartTicks.clear();
        saplingAltIndex.clear();
        pendingBonemeal.clear();
        lastSaplingCounts.clear();
        lastPositions.clear();
        stuckCounter.clear();
        craftingTablePlaced.clear();
    }
}

