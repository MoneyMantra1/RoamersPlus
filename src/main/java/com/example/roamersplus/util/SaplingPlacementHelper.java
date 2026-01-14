package com.example.roamersplus.util;

import com.example.roamersplus.RoamersPlusMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
     * Attempts to scatter saplings within the given radius around a center position.
     * Places saplings on valid ground (grass, dirt, etc.) with air above.
     * 
     * @param level The world level
     * @param centerPos Center position (usually roamer's home/campfire)
     * @param radius The radius to scatter within (from treeCheckRange config)
     * @param saplingStack The sapling items to place
     * @return Number of saplings that could NOT be placed (remainder to keep in inventory)
     */
    public static int scatterSaplings(Level level, BlockPos centerPos, int radius, ItemStack saplingStack) {
        if (level.isClientSide() || saplingStack.isEmpty()) {
            return saplingStack.getCount();
        }
        
        Item item = saplingStack.getItem();
        if (!(item instanceof BlockItem blockItem)) {
            return saplingStack.getCount();
        }
        
        Block block = blockItem.getBlock();
        if (!(block instanceof SaplingBlock)) {
            return saplingStack.getCount();
        }
        
        int toPlace = saplingStack.getCount();
        int placed = 0;
        
        // Generate potential positions and shuffle for random scattering
        List<BlockPos> potentialPositions = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Skip positions too close to center (leave room for building)
                if (Math.abs(x) <= 3 && Math.abs(z) <= 3) {
                    continue;
                }
                
                // Check within circular radius
                if (x * x + z * z <= radius * radius) {
                    potentialPositions.add(centerPos.offset(x, 0, z));
                }
            }
        }
        
        // Shuffle for random distribution
        Collections.shuffle(potentialPositions);
        
        for (BlockPos basePos : potentialPositions) {
            if (placed >= toPlace) {
                break;
            }
            
            // Find valid Y level (search up and down a bit)
            BlockPos validPos = findValidSaplingPosition(level, basePos);
            if (validPos != null) {
                // Place the sapling
                BlockState saplingState = block.defaultBlockState();
                level.setBlock(validPos, saplingState, 3);
                placed++;
                
                RoamersPlusMod.LOGGER.debug("Scattered sapling at {}", validPos);
            }
        }
        
        return toPlace - placed;
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
            return net.minecraft.world.item.Items.OAK_SAPLING;
        }
        if (logBlock == Blocks.SPRUCE_LOG || logBlock == Blocks.SPRUCE_WOOD ||
            logBlock == Blocks.STRIPPED_SPRUCE_LOG || logBlock == Blocks.STRIPPED_SPRUCE_WOOD) {
            return net.minecraft.world.item.Items.SPRUCE_SAPLING;
        }
        if (logBlock == Blocks.BIRCH_LOG || logBlock == Blocks.BIRCH_WOOD ||
            logBlock == Blocks.STRIPPED_BIRCH_LOG || logBlock == Blocks.STRIPPED_BIRCH_WOOD) {
            return net.minecraft.world.item.Items.BIRCH_SAPLING;
        }
        if (logBlock == Blocks.JUNGLE_LOG || logBlock == Blocks.JUNGLE_WOOD ||
            logBlock == Blocks.STRIPPED_JUNGLE_LOG || logBlock == Blocks.STRIPPED_JUNGLE_WOOD) {
            return net.minecraft.world.item.Items.JUNGLE_SAPLING;
        }
        if (logBlock == Blocks.ACACIA_LOG || logBlock == Blocks.ACACIA_WOOD ||
            logBlock == Blocks.STRIPPED_ACACIA_LOG || logBlock == Blocks.STRIPPED_ACACIA_WOOD) {
            return net.minecraft.world.item.Items.ACACIA_SAPLING;
        }
        if (logBlock == Blocks.DARK_OAK_LOG || logBlock == Blocks.DARK_OAK_WOOD ||
            logBlock == Blocks.STRIPPED_DARK_OAK_LOG || logBlock == Blocks.STRIPPED_DARK_OAK_WOOD) {
            return net.minecraft.world.item.Items.DARK_OAK_SAPLING;
        }
        if (logBlock == Blocks.CHERRY_LOG || logBlock == Blocks.CHERRY_WOOD ||
            logBlock == Blocks.STRIPPED_CHERRY_LOG || logBlock == Blocks.STRIPPED_CHERRY_WOOD) {
            return net.minecraft.world.item.Items.CHERRY_SAPLING;
        }
        if (logBlock == Blocks.MANGROVE_LOG || logBlock == Blocks.MANGROVE_WOOD ||
            logBlock == Blocks.STRIPPED_MANGROVE_LOG || logBlock == Blocks.STRIPPED_MANGROVE_WOOD) {
            return net.minecraft.world.item.Items.MANGROVE_PROPAGULE;
        }
        
        return null;
    }
}
