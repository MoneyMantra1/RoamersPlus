package com.example.roamersplus.util;

import com.example.roamersplus.RoamersPlusMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for sapling placement operations.
 * Handles scattering saplings before excavation and replanting after tree chopping.
 */
public class SaplingPlacementHelper {
    
    /**
     * Scatters multiple types of saplings, alternating between them for even distribution.
     * Also applies bonemeal to placed saplings if available.
     * 
     * @param level The world level
     * @param centerPos Center position (usually roamer's home/campfire)
     * @param radius The radius to scatter within
     * @param saplingStacks List of sapling stacks to scatter (will be modified)
     * @param bonemealStack Bonemeal stack to use (will be modified), can be null or empty
     */
    public static void scatterSaplingsAlternating(Level level, BlockPos centerPos, int radius, 
                                                   List<ItemStack> saplingStacks, ItemStack bonemealStack) {
        if (level.isClientSide() || saplingStacks.isEmpty()) {
            return;
        }
        
        // Group stacks by sapling *type* (not by slot) so alternation actually alternates between sapling types.
        java.util.LinkedHashMap<Block, List<ItemStack>> stacksByBlock = new java.util.LinkedHashMap<>();

        for (ItemStack stack : saplingStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block instanceof SaplingBlock) {
                    stacksByBlock.computeIfAbsent(block, k -> new ArrayList<>()).add(stack);
                }
            }
        }

        if (stacksByBlock.isEmpty()) {
            RoamersPlusMod.LOGGER.debug("No valid sapling stacks to scatter");
            return;
        }

        List<Block> saplingBlocks = new ArrayList<>(stacksByBlock.keySet());
        List<List<ItemStack>> stacksByType = new ArrayList<>(stacksByBlock.values());

        RoamersPlusMod.LOGGER.debug("Scattering {} sapling types around {}", saplingBlocks.size(), centerPos);
// Generate and shuffle positions
        List<BlockPos> potentialPositions = generateScatterPositions(centerPos, radius);
        
        // Track placed saplings for bonemeal application
        List<BlockPos> placedSaplingPositions = new ArrayList<>();
        
        // Current sapling type index for alternation
        int typeIndex = 0;
        int totalPlaced = 0;
        
        for (BlockPos basePos : potentialPositions) {
            // Check if we still have saplings to place
            boolean hasAnySaplings = false;
            for (List<ItemStack> stacks : stacksByType) {
                for (ItemStack stack : stacks) {
                    if (stack != null && !stack.isEmpty() && stack.getCount() > 0) {
                        hasAnySaplings = true;
                        break;
                    }
                }
                if (hasAnySaplings) break;
            }
if (!hasAnySaplings) {
                break;
            }
            
            // Find the next sapling type that has saplings remaining
            // Start from current typeIndex and wrap around
            int attempts = 0;
            while (attempts < saplingBlocks.size()) {
                // Find a non-empty stack for this sapling type
                List<ItemStack> typeStacks = stacksByType.get(typeIndex);
                ItemStack stackToUse = ItemStack.EMPTY;
                for (ItemStack s : typeStacks) {
                    if (s != null && !s.isEmpty() && s.getCount() > 0) {
                        stackToUse = s;
                        break;
                    }
                }

                if (!stackToUse.isEmpty() && stackToUse.getCount() > 0) {
                    // Try to place this sapling
                    BlockPos validPos = findValidSaplingPosition(level, basePos);
                    if (validPos != null) {
                        Block block = saplingBlocks.get(typeIndex);
                        BlockState saplingState = block.defaultBlockState();
                        level.setBlock(validPos, saplingState, 3);
                        stackToUse.shrink(1);
                        placedSaplingPositions.add(validPos);
                        totalPlaced++;

                        RoamersPlusMod.LOGGER.debug("Placed {} at {} (type index {})", 
                            block.getName().getString(), validPos, typeIndex);

                        // Move to next type for alternation
                        typeIndex = (typeIndex + 1) % saplingBlocks.size();
                        break;
                    }
                }

                // Try next type
                typeIndex = (typeIndex + 1) % saplingBlocks.size();
                attempts++;
            }}
        
        RoamersPlusMod.LOGGER.debug("Placed {} saplings total", totalPlaced);
        
        // Apply bonemeal to placed saplings
        // Each sapling gets up to 3 bonemeal applications to help them grow faster
        if (bonemealStack != null && !bonemealStack.isEmpty() && level instanceof ServerLevel serverLevel) {
            int bonemealUsed = 0;
            int bonemealPerSapling = 3; // Apply multiple bonemeal to help growth
            
            for (BlockPos saplingPos : placedSaplingPositions) {
                if (bonemealStack.isEmpty() || bonemealStack.getCount() <= 0) {
                    break;
                }
                
                // Apply bonemeal multiple times per sapling (up to bonemealPerSapling times)
                for (int i = 0; i < bonemealPerSapling && bonemealStack.getCount() > 0; i++) {
                    BlockState state = level.getBlockState(saplingPos);
                    
                    // Stop if sapling is gone (grew into tree)
                    if (!(state.getBlock() instanceof SaplingBlock)) {
                        break;
                    }
                    
                    // Apply bonemeal effect
                    if (applyBonemealToSapling(serverLevel, saplingPos, null)) {
                        bonemealStack.shrink(1);
                        bonemealUsed++;
                    }
                }
            }
            RoamersPlusMod.LOGGER.debug("Used {} bonemeal on {} saplings", bonemealUsed, placedSaplingPositions.size());
        }
    }
    
    /**
     * Applies bonemeal effect to a sapling, potentially growing it into a tree.
     * Always returns true if bonemeal was applied (sapling existed), regardless of whether tree grew.
     * This ensures bonemeal is consumed on each application attempt.
     *
     * @param level The server level
     * @param pos The sapling position
     * @param sapling The sapling block type (can be null to auto-detect)
     * @return true if bonemeal was applied, false if no sapling at position
     */
    private static boolean applyBonemealToSapling(ServerLevel level, BlockPos pos, SaplingBlock sapling) {
        BlockState state = level.getBlockState(pos);
        
        // Auto-detect sapling if not specified
        if (!(state.getBlock() instanceof SaplingBlock currentSapling)) {
            return false;
        }
        
        // If specific sapling was requested, verify it matches
        if (sapling != null && currentSapling != sapling) {
            return false;
        }
        
        // Apply bonemeal effect - advanceTree handles the growth stage advancement
        // This mimics what BoneMealItem does internally
        currentSapling.advanceTree(level, pos, state, level.random);
        
        // Always return true since we applied the effect - bonemeal should be consumed
        // The tree may or may not have grown, but the bonemeal was used
        return true;
    }

    
    /**
     * Generates a shuffled list of potential scatter positions within radius.
     */
    private static List<BlockPos> generateScatterPositions(BlockPos centerPos, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Skip positions too close to center (leave room for building)
                if (Math.abs(x) <= 3 && Math.abs(z) <= 3) {
                    continue;
                }
                
                // Check within circular radius
                if (x * x + z * z <= radius * radius) {
                    positions.add(centerPos.offset(x, 0, z));
                }
            }
        }
        
        // Shuffle for random distribution
        Collections.shuffle(positions);
        return positions;
    }
    
    /**
     * Finds a valid position to place a sapling near the given base position.
     * Searches vertically to find appropriate ground level.
     * 
     * @param level The world level
     * @param basePos The base position to search around
     * @return A valid position for sapling placement, or null if none found
     */
    private static BlockPos findValidSaplingPosition(Level level, BlockPos basePos) {
        // Search up and down from base position
        for (int yOffset = -5; yOffset <= 5; yOffset++) {
            BlockPos checkPos = basePos.offset(0, yOffset, 0);
            if (isValidSaplingSpot(level, checkPos)) {
                return checkPos;
            }
        }
        return null;
    }
    
    /**
     * Checks if a position is valid for placing a sapling.
     * Requires: air at position, solid plantable ground below, air above.
     */
    private static boolean isValidSaplingSpot(Level level, BlockPos pos) {
        BlockState stateAtPos = level.getBlockState(pos);
        BlockState stateBelow = level.getBlockState(pos.below());
        BlockState stateAbove = level.getBlockState(pos.above());
        
        // Position must be air or replaceable
        if (!stateAtPos.isAir() && !stateAtPos.canBeReplaced()) {
            return false;
        }
        
        // Must have space above
        if (!stateAbove.isAir()) {
            return false;
        }
        
        // Ground below must be valid for saplings (dirt, grass, podzol, etc.)
        Block blockBelow = stateBelow.getBlock();
        return blockBelow == Blocks.GRASS_BLOCK || 
               blockBelow == Blocks.DIRT || 
               blockBelow == Blocks.COARSE_DIRT ||
               blockBelow == Blocks.PODZOL ||
               blockBelow == Blocks.ROOTED_DIRT ||
               blockBelow == Blocks.MOSS_BLOCK ||
               blockBelow == Blocks.MUD ||
               blockBelow == Blocks.MUDDY_MANGROVE_ROOTS ||
               stateBelow.is(BlockTags.DIRT);
    }
    
    /**
     * Attempts to replant a sapling at or near a tree stump position.
     * 
     * @param level The world level
     * @param stumpPos The position where the tree was chopped
     * @param saplingItem The sapling item to plant
     * @return true if successfully replanted, false otherwise
     */
    public static boolean replantAtStump(Level level, BlockPos stumpPos, Item saplingItem) {
        if (level.isClientSide() || !(saplingItem instanceof BlockItem blockItem)) {
            return false;
        }
        
        Block block = blockItem.getBlock();
        if (!(block instanceof SaplingBlock)) {
            return false;
        }
        
        // Try the exact stump position first
        if (isValidSaplingSpot(level, stumpPos)) {
            level.setBlock(stumpPos, block.defaultBlockState(), 3);
            RoamersPlusMod.LOGGER.debug("Replanted sapling at stump position {}", stumpPos);
            return true;
        }
        
        // Try nearby positions if stump isn't valid
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                
                BlockPos nearbyPos = stumpPos.offset(x, 0, z);
                BlockPos validPos = findValidSaplingPosition(level, nearbyPos);
                if (validPos != null) {
                    level.setBlock(validPos, block.defaultBlockState(), 3);
                    RoamersPlusMod.LOGGER.debug("Replanted sapling near stump at {}", validPos);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Gets the sapling item that corresponds to a log block type.
     * 
     * @param logBlock The log block that was chopped
     * @return The corresponding sapling item, or null if not a standard log
     */
    public static Item getSaplingForLog(Block logBlock) {
        if (logBlock == Blocks.OAK_LOG || logBlock == Blocks.OAK_WOOD ||
            logBlock == Blocks.STRIPPED_OAK_LOG || logBlock == Blocks.STRIPPED_OAK_WOOD) {
            return Items.OAK_SAPLING;
        }
        if (logBlock == Blocks.SPRUCE_LOG || logBlock == Blocks.SPRUCE_WOOD ||
            logBlock == Blocks.STRIPPED_SPRUCE_LOG || logBlock == Blocks.STRIPPED_SPRUCE_WOOD) {
            return Items.SPRUCE_SAPLING;
        }
        if (logBlock == Blocks.BIRCH_LOG || logBlock == Blocks.BIRCH_WOOD ||
            logBlock == Blocks.STRIPPED_BIRCH_LOG || logBlock == Blocks.STRIPPED_BIRCH_WOOD) {
            return Items.BIRCH_SAPLING;
        }
        if (logBlock == Blocks.JUNGLE_LOG || logBlock == Blocks.JUNGLE_WOOD ||
            logBlock == Blocks.STRIPPED_JUNGLE_LOG || logBlock == Blocks.STRIPPED_JUNGLE_WOOD) {
            return Items.JUNGLE_SAPLING;
        }
        if (logBlock == Blocks.ACACIA_LOG || logBlock == Blocks.ACACIA_WOOD ||
            logBlock == Blocks.STRIPPED_ACACIA_LOG || logBlock == Blocks.STRIPPED_ACACIA_WOOD) {
            return Items.ACACIA_SAPLING;
        }
        if (logBlock == Blocks.DARK_OAK_LOG || logBlock == Blocks.DARK_OAK_WOOD ||
            logBlock == Blocks.STRIPPED_DARK_OAK_LOG || logBlock == Blocks.STRIPPED_DARK_OAK_WOOD) {
            return Items.DARK_OAK_SAPLING;
        }
        if (logBlock == Blocks.CHERRY_LOG || logBlock == Blocks.CHERRY_WOOD ||
            logBlock == Blocks.STRIPPED_CHERRY_LOG || logBlock == Blocks.STRIPPED_CHERRY_WOOD) {
            return Items.CHERRY_SAPLING;
        }
        if (logBlock == Blocks.MANGROVE_LOG || logBlock == Blocks.MANGROVE_WOOD ||
            logBlock == Blocks.STRIPPED_MANGROVE_LOG || logBlock == Blocks.STRIPPED_MANGROVE_WOOD) {
            return Items.MANGROVE_PROPAGULE;
        }
        
        return null;
    }


/**
 * Attempts to bonemeal a sapling at the given position using bonemeal from the provided inventory.
 * Will consume up to maxUses bonemeal, stopping early if the sapling grows into a tree.
 *
 * @return number of bonemeal items consumed
 */
public static int bonemealSaplingFromInventory(Level level, BlockPos pos, Container inventory, int maxUses) {
    if (level.isClientSide() || !(level instanceof ServerLevel serverLevel) || inventory == null || maxUses <= 0) {
        return 0;
    }

    BlockState state = serverLevel.getBlockState(pos);
    if (!(state.getBlock() instanceof SaplingBlock)) {
        return 0;
    }

    int used = 0;
    for (int attempt = 0; attempt < maxUses; attempt++) {
        // Check if sapling still exists (might have grown from previous application)
        if (!(serverLevel.getBlockState(pos).getBlock() instanceof SaplingBlock)) {
            break; // Tree grew!
        }
        
        int slot = findFirstBonemealSlot(inventory);
        if (slot < 0) break;

        ItemStack bone = inventory.getItem(slot);
        if (bone.isEmpty() || bone.getItem() != Items.BONE_MEAL) break;

        // Apply bonemeal - always consumes one regardless of growth
        applyBonemealToSapling(serverLevel, pos, null);

        bone.shrink(1);
        if (bone.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        used++;
    }
    return used;
}

private static int findFirstBonemealSlot(Container inventory) {
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        ItemStack s = inventory.getItem(i);
        if (!s.isEmpty() && s.getItem() == Items.BONE_MEAL) return i;
    }
    return -1;
}
}
