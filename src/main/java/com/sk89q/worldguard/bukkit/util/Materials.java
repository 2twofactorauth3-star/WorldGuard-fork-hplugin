/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.util;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Material utility class.
 */
public final class Materials {

    private static final int MODIFIED_ON_RIGHT = 1;
    private static final int MODIFIED_ON_LEFT = 2;
    private static final int MODIFIES_BLOCKS = 4;

    private static final BiMap<EntityType, Material> ENTITY_ITEMS = HashBiMap.create();
    private static final Map<Material, Integer> MATERIAL_FLAGS = new EnumMap<>(Material.class);
    private static final Set<PotionEffectType> DAMAGE_EFFECTS = new HashSet<>();

    private static void putMaterialTag(Tag<Material> tag, Integer value) {
        if (tag == null) return;
        tag.getValues().forEach(mat -> MATERIAL_FLAGS.put(mat, value));
    }

    private static void putMaterial(String name, int value) {
        Material material = Material.getMaterial(name);
        if (material != null) MATERIAL_FLAGS.put(material, value);
    }

    @SuppressWarnings("unchecked")
    private static void putMaterialTag(String fieldName, int value) {
        try {
            putMaterialTag((Tag<Material>) Tag.class.getField(fieldName).get(null), value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isMaterialTag(String fieldName, Material material) {
        try {
            return ((Tag<Material>) Tag.class.getField(fieldName).get(null)).isTagged(material);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void putEntityItem(String entityTypeName, String materialName) {
        Material material = Material.getMaterial(materialName);
        if (material == null) return;
        try {
            ENTITY_ITEMS.put(EntityType.valueOf(entityTypeName), material);
        } catch (IllegalArgumentException ignored) {
        }
    }

    static {
        ENTITY_ITEMS.put(EntityType.PAINTING, Material.PAINTING);
        ENTITY_ITEMS.put(EntityType.ARROW, Material.ARROW);
        ENTITY_ITEMS.put(EntityType.SNOWBALL, Material.SNOWBALL);
        ENTITY_ITEMS.put(EntityType.FIREBALL, Material.FIRE_CHARGE);
        ENTITY_ITEMS.put(EntityType.ENDER_PEARL, Material.ENDER_PEARL);
        ENTITY_ITEMS.put(EntityType.EXPERIENCE_BOTTLE, Material.EXPERIENCE_BOTTLE);
        ENTITY_ITEMS.put(EntityType.ITEM_FRAME, Material.ITEM_FRAME);
        ENTITY_ITEMS.put(EntityType.GLOW_ITEM_FRAME, Material.GLOW_ITEM_FRAME);
        ENTITY_ITEMS.put(EntityType.TNT, Material.TNT);
        ENTITY_ITEMS.put(EntityType.FIREWORK_ROCKET, Material.FIREWORK_ROCKET);
        ENTITY_ITEMS.put(EntityType.COMMAND_BLOCK_MINECART, Material.COMMAND_BLOCK_MINECART);
        ENTITY_ITEMS.put(EntityType.MINECART, Material.MINECART);
        ENTITY_ITEMS.put(EntityType.CHEST_MINECART, Material.CHEST_MINECART);
        ENTITY_ITEMS.put(EntityType.FURNACE_MINECART, Material.FURNACE_MINECART);
        ENTITY_ITEMS.put(EntityType.TNT_MINECART, Material.TNT_MINECART);
        ENTITY_ITEMS.put(EntityType.HOPPER_MINECART, Material.HOPPER_MINECART);
        putEntityItem("POTION", "SPLASH_POTION");
        putEntityItem("SPLASH_POTION", "SPLASH_POTION");
        putEntityItem("LINGERING_POTION", "LINGERING_POTION");
        ENTITY_ITEMS.put(EntityType.EGG, Material.EGG);
        ENTITY_ITEMS.put(EntityType.ARMOR_STAND, Material.ARMOR_STAND);
        ENTITY_ITEMS.put(EntityType.END_CRYSTAL, Material.END_CRYSTAL);

        putEntityItem("BOAT", "OAK_BOAT");
        putEntityItem("CHEST_BOAT", "OAK_CHEST_BOAT");
        for (String wood : new String[]{"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "PALE_OAK"}) {
            String regular = wood + "_BOAT";
            String chest = wood + "_CHEST_BOAT";
            putEntityItem(regular, regular);
            putEntityItem(chest, chest);
        }
        putEntityItem("BAMBOO_RAFT", "BAMBOO_RAFT");
        putEntityItem("BAMBOO_CHEST_RAFT", "BAMBOO_CHEST_RAFT");

        for (Material material : Registry.MATERIAL) {
            MATERIAL_FLAGS.put(material, 0);
        }

        putMaterialTag(Tag.DOORS, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.TRAPDOORS, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.SHULKER_BOXES, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.BUTTONS, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.FLOWER_POTS, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.BEDS, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.FENCE_GATES, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.CANDLES, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.CANDLE_CAKES, MODIFIED_ON_RIGHT);
        putMaterialTag(Tag.CAULDRONS, MODIFIED_ON_RIGHT);

        for (Material material : new Material[]{
                Material.DISPENSER, Material.NOTE_BLOCK, Material.TNT, Material.BOOKSHELF,
                Material.SPAWNER, Material.CHEST, Material.REDSTONE_WIRE, Material.FURNACE,
                Material.LEVER, Material.JUKEBOX, Material.CAKE, Material.REPEATER,
                Material.ENCHANTING_TABLE, Material.BREWING_STAND, Material.COMMAND_BLOCK,
                Material.BEACON, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
                Material.TRAPPED_CHEST, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
                Material.HOPPER, Material.DROPPER, Material.REPEATING_COMMAND_BLOCK,
                Material.CHAIN_COMMAND_BLOCK, Material.BARREL, Material.BELL,
                Material.BLAST_FURNACE, Material.COMPOSTER, Material.SMOKER,
                Material.SWEET_BERRY_BUSH, Material.BEEHIVE, Material.BEE_NEST,
                Material.RESPAWN_ANCHOR, Material.SOUL_CAMPFIRE, Material.LIGHT,
                Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.CHISELED_BOOKSHELF,
                Material.DECORATED_POT, Material.VAULT
        }) {
            MATERIAL_FLAGS.put(material, MODIFIED_ON_RIGHT);
        }
        for (Material material : new Material[]{
                Material.DRAGON_EGG, Material.STRUCTURE_BLOCK,
                Material.CAMPFIRE, Material.JIGSAW
        }) {
            MATERIAL_FLAGS.put(material, MODIFIED_ON_LEFT | MODIFIED_ON_RIGHT);
        }
        MATERIAL_FLAGS.put(Material.BONE_MEAL, MODIFIES_BLOCKS);

        putMaterial("TEST_BLOCK", MODIFIED_ON_RIGHT);
        putMaterial("TEST_INSTANCE_BLOCK", MODIFIED_ON_RIGHT);
        for (String tag : new String[]{"COPPER_CHESTS", "COPPER_GOLEM_STATUES", "WOODEN_SHELVES"}) {
            putMaterialTag(tag, MODIFIED_ON_RIGHT);
        }

        DAMAGE_EFFECTS.add(PotionEffectType.SLOWNESS);
        DAMAGE_EFFECTS.add(PotionEffectType.MINING_FATIGUE);
        DAMAGE_EFFECTS.add(PotionEffectType.INSTANT_DAMAGE);
        DAMAGE_EFFECTS.add(PotionEffectType.NAUSEA);
        DAMAGE_EFFECTS.add(PotionEffectType.BLINDNESS);
        DAMAGE_EFFECTS.add(PotionEffectType.HUNGER);
        DAMAGE_EFFECTS.add(PotionEffectType.WEAKNESS);
        DAMAGE_EFFECTS.add(PotionEffectType.POISON);
        DAMAGE_EFFECTS.add(PotionEffectType.WITHER);
        DAMAGE_EFFECTS.add(PotionEffectType.GLOWING);
        DAMAGE_EFFECTS.add(PotionEffectType.LEVITATION);
        DAMAGE_EFFECTS.add(PotionEffectType.UNLUCK);
        DAMAGE_EFFECTS.add(PotionEffectType.BAD_OMEN);
        DAMAGE_EFFECTS.add(PotionEffectType.DARKNESS);
        DAMAGE_EFFECTS.add(PotionEffectType.TRIAL_OMEN);
        DAMAGE_EFFECTS.add(PotionEffectType.WIND_CHARGED);
        DAMAGE_EFFECTS.add(PotionEffectType.WEAVING);
        DAMAGE_EFFECTS.add(PotionEffectType.OOZING);
        DAMAGE_EFFECTS.add(PotionEffectType.INFESTED);
    }

    private Materials() {
    }

    /**
     * Get the related material for an entity type.
     *
     * @param type the entity type
     * @return the related material or {@code null} if one is not known or exists
     */
    @Nullable
    public static Material getRelatedMaterial(EntityType type) {
        return ENTITY_ITEMS.get(type);
    }

    /**
     * Get the related entity type for a material.
     *
     * @param material the material
     * @return the related entity type or {@code null} if one is not known or exists
     */
    @Nullable
    public static EntityType getRelatedEntity(Material material) {
        return ENTITY_ITEMS.inverse().get(material);
    }

    /**
     * Get the material of the block placed by the given bucket, defaulting
     * to water if the bucket type is not known.
     *
     * <p>If a non-bucket material is given, it will be assumed to be
     * an unknown bucket type. If the given bucket doesn't have a block form
     * (it can't be placed), then water will be returned (i.e. for milk).
     * Be aware that either the stationary or non-stationary material may be
     * returned.</p>
     *
     * @param type the bucket material
     * @return the block material
     */
    public static Material getBucketBlockMaterial(Material type) {
        return type == Material.LAVA_BUCKET ? Material.LAVA : Material.WATER;
    }

    /**
     * Test whether the given material is a mushroom.
     *
     * @param material the material
     * @return true if a mushroom block
     */
    public static boolean isMushroom(Material material) {
        return material == Material.RED_MUSHROOM || material == Material.BROWN_MUSHROOM;
    }

    /**
     * Test whether the given material is a leaf block.
     *
     * @param material the material
     * @return true if a leaf block
     */
    public static boolean isLeaf(Material material) {
        return Tag.LEAVES.isTagged(material);
    }

    /**
     * Test whether the given material is a liquid block.
     *
     * @param material the material
     * @return true if a liquid block
     */
    public static boolean isLiquid(Material material) {
        return isWater(material) || isLava(material);
    }

    /**
     * Test whether the given material is water.
     *
     * @param material the material
     * @return true if a water block
     */
    public static boolean isWater(Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN
            || material == Material.KELP_PLANT || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS;
    }

    /**
     * Test whether the given material is lava.
     *
     * @param material the material
     * @return true if a lava block
     */
    public static boolean isLava(Material material) {
        return material == Material.LAVA;
    }

    /**
     * Test whether the given material is a portal material.
     *
     * @param material the material
     * @return true if a portal block
     */
    public static boolean isPortal(Material material) {
        return material == Material.NETHER_PORTAL || material == Material.END_PORTAL;
    }

    /**
     * Test whether the given material is a rail block.
     *
     * @param material the material
     * @return true if a rail block
     */
    public static boolean isRailBlock(Material material) {
        return Tag.RAILS.isTagged(material);
    }

    /**
     * Test whether the given material is a piston block, not including
     * the "technical blocks" such as the piston extension block.
     *
     * @param material the material
     * @return true if a piston block
     */
    public static boolean isPistonBlock(Material material) {
        return material == Material.PISTON
                || material == Material.STICKY_PISTON
                || material == Material.MOVING_PISTON;
    }

    /**
     * Test whether the given material is a Minecart.
     *
     * @param material the material
     * @return true if a Minecart item
     */
    public static boolean isMinecart(Material material) {
        return material == Material.MINECART
                || material == Material.COMMAND_BLOCK_MINECART
                || material == Material.TNT_MINECART
                || material == Material.HOPPER_MINECART
                || material == Material.FURNACE_MINECART
                || material == Material.CHEST_MINECART;
    }

    /**
     * Test whether the given material is a Boat.
     *
     * @param material the material
     * @return true if a Boat item
     */
    public static boolean isBoat(Material material) {
        return Tag.ITEMS_BOATS.isTagged(material);
    }

    /**
     * Test whether the given material is a Shulker Box.
     *
     * @param material the material
     * @return true if a Shulker Box block
     */
    public static boolean isShulkerBox(Material material) {
        return Tag.SHULKER_BOXES.isTagged(material);
    }

    /**
     * Test whether the given material is an inventory block.
     *
     * @param material the material
     * @return true if an inventory block
     */
    public static boolean isInventoryBlock(Material material) {
        return material == Material.CHEST
                || material == Material.JUKEBOX
                || material == Material.DISPENSER
                || material == Material.FURNACE
                || material == Material.BREWING_STAND
                || material == Material.TRAPPED_CHEST
                || material == Material.HOPPER
                || material == Material.DROPPER
                || material == Material.BARREL
                || material == Material.BLAST_FURNACE
                || material == Material.SMOKER
                || material == Material.CHISELED_BOOKSHELF
                || material == Material.CRAFTER
                || material == Material.DECORATED_POT
                || isMaterialTag("WOODEN_SHELVES", material)
                || isMaterialTag("COPPER_CHESTS", material)
                || Tag.ITEMS_CHEST_BOATS.isTagged(material)
                || Tag.SHULKER_BOXES.isTagged(material);
    }

    public static boolean isSpawnEgg(Material material) {
        return getEntitySpawnEgg(material) != null;
    }

    public static EntityType getEntitySpawnEgg(Material material) {
        String name = material.name();
        if (!name.endsWith("_SPAWN_EGG")) return null;
        String entityName = name.substring(0, name.length() - "_SPAWN_EGG".length())
                .toLowerCase(java.util.Locale.ROOT);
        return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(entityName));
    }

    public static boolean isBed(Material material) {
        return Tag.BEDS.isTagged(material);
    }

    public static boolean isAnvil(Material material) {
        return Tag.ANVIL.isTagged(material);
    }

    public static boolean isCoral(Material material) {
        return Tag.CORAL_BLOCKS.isTagged(material) ||
                Tag.CORAL_PLANTS.isTagged(material) ||
                Tag.CORALS.isTagged(material) ||
                Tag.WALL_CORALS.isTagged(material);
    }

    /**
     * Test whether the material is a crop.
     * @param type the material
     * @return true if the material is a crop
     */
    public static boolean isCrop(Material type) {
        if (Tag.CROPS.isTagged(type)) return true;
        // yea, that's not all, there are some more
        return switch (type) {
            case PUMPKIN, MELON, CACTUS, SUGAR_CANE, BAMBOO, BAMBOO_SAPLING,
                 SWEET_BERRY_BUSH, NETHER_WART, CAVE_VINES, CAVE_VINES_PLANT, COCOA ->
                    true;
            default -> false;
        };
    }

    /**
     * Test whether the material should be handled as vine. Used by the vine-growth flag
     * @param newType the material
     * @return true if the material should be handled as vine
     */
    public static boolean isVine(Material newType) {
        return newType == Material.VINE ||
                newType == Material.KELP ||
                newType == Material.TWISTING_VINES ||
                newType == Material.WEEPING_VINES ||
                Tag.CAVE_VINES.isTagged(newType);

    }

    /**
     * Test whether the given material is affected by
     * {@link Flags#USE}.
     *
     * <p>Generally, materials that are considered by this method are those
     * that are not inventories but can be used.</p>
     *
     * @param material the material
     * @return true if covered by the use flag
     */
    public static boolean isUseFlagApplicable(Material material) {
        if (Tag.BUTTONS.isTagged(material)
                || Tag.DOORS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material)
                || Tag.PRESSURE_PLATES.isTagged(material)) {
            return true;
        }
        return switch (material) {
            case LEVER, LECTERN, ENCHANTING_TABLE, BELL, LOOM,
                    CARTOGRAPHY_TABLE, STONECUTTER, GRINDSTONE, VAULT -> true;
            default -> false;
        };
    }

    /**
     * Test whether the given material is a block that is modified when it is
     * left or right clicked.
     *
     * <p>This test is conservative, returning true for blocks that it is not
     * aware of.</p>
     *
     * @param material the material
     * @param rightClick whether it is a right click
     * @return true if the block is modified
     */
    public static boolean isBlockModifiedOnClick(Material material, boolean rightClick) {
        Integer flags = MATERIAL_FLAGS.get(material);
        return flags == null
                || (rightClick && (flags & MODIFIED_ON_RIGHT) == MODIFIED_ON_RIGHT)
                || (!rightClick && (flags & MODIFIED_ON_LEFT) == MODIFIED_ON_LEFT);
    }

    /**
     * Test whether the given item modifies a given block when right clicked.
     *
     * <p>This test is conservative, returning true for items that it is not
     * aware of or does not have the details for.</p>
     *
     * @param item the item
     * @param block the block
     * @return true if the item is applied to the block
     */
    public static boolean isItemAppliedToBlock(Material item, Material block) {
        Integer flags = MATERIAL_FLAGS.get(item);
        return flags == null || (flags & MODIFIES_BLOCKS) == MODIFIES_BLOCKS || isToolApplicable(item, block);
    }

    /**
     * Test whether the given material should be tested as "building" when
     * it is used.
     *
     * @param type the type
     * @return true to be considered as used
     */
    public static boolean isConsideredBuildingIfUsed(Material type) {
        return type == Material.REPEATER
            || type == Material.COMPARATOR
            || type == Material.CAKE
            || type == Material.DRAGON_EGG
            || Tag.FLOWER_POTS.isTagged(type)
            || Tag.CANDLES.isTagged(type)
            || Tag.CANDLE_CAKES.isTagged(type)
            || Tag.ALL_SIGNS.isTagged(type);
    }

    /**
     * Test whether a list of potion effects contains one or more potion
     * effects used for doing damage.
     *
     * @param effects A collection of effects
     * @return True if at least one damage effect exists
     */
    public static boolean hasDamageEffect(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (DAMAGE_EFFECTS.contains(effect.getType())) {
                return true;
            }
        }

        return false;
    }

    // should match instances of ItemArmor

    /**
     * Check if the material is equippable armor (i.e. that it is equipped on right-click
     * not necessarily that it can be put in the armor slots)
     *
     * @param type material to check
     * @return true if equippable armor
     */
    public static boolean isArmor(Material type) {
        if (Tag.ITEMS_HEAD_ARMOR.isTagged(type) || Tag.ITEMS_CHEST_ARMOR.isTagged(type) ||
                Tag.ITEMS_LEG_ARMOR.isTagged(type) || Tag.ITEMS_FOOT_ARMOR.isTagged(type) ||
                Tag.ITEMS_SKULLS.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case CARVED_PUMPKIN, ELYTRA -> true;
            default -> false;
        };
    }

    /**
     * Check if the material is usable via right-click on the target
     * material. Returns false if the target material cannot be modified
     * by the provided tool, or of the provided tool material isn't
     * a tool material.
     *
     * @param toolMaterial the tool material being used
     * @param targetMaterial the target material to check
     * @return true if tool has an interact function with this material
     */
    public static boolean isToolApplicable(Material toolMaterial, Material targetMaterial) {
        if (toolMaterial.name().equals("COPPER_HOE")) {
            return switch (targetMaterial) {
                case GRASS_BLOCK, DIRT, DIRT_PATH, ROOTED_DIRT -> true;
                default -> false;
            };
        }
        if (toolMaterial.name().equals("COPPER_AXE")) {
            return isWaxedCopper(targetMaterial) || Tag.LOGS.isTagged(targetMaterial)
                    || switch (targetMaterial) {
                        case OAK_WOOD, DARK_OAK_WOOD, ACACIA_WOOD, BIRCH_WOOD, SPRUCE_WOOD, PUMPKIN,
                                BAMBOO_BLOCK, JUNGLE_WOOD, CRIMSON_STEM, WARPED_STEM, CRIMSON_HYPHAE,
                                WARPED_HYPHAE -> true;
                        default -> false;
                    };
        }
        if (toolMaterial.name().equals("COPPER_SHOVEL")) {
            return switch (targetMaterial) {
                case GRASS_BLOCK, CAMPFIRE, SOUL_CAMPFIRE -> true;
                default -> false;
            };
        }
        switch (toolMaterial) {
            case WOODEN_HOE:
            case STONE_HOE:
            case IRON_HOE:
            case GOLDEN_HOE:
            case DIAMOND_HOE:
            case NETHERITE_HOE:
                return switch (targetMaterial) {
                    case GRASS_BLOCK, DIRT, DIRT_PATH, ROOTED_DIRT ->
                            true;
                    default -> false;
                };
            case WOODEN_AXE:
            case STONE_AXE:
            case IRON_AXE:
            case GOLDEN_AXE:
            case DIAMOND_AXE:
            case NETHERITE_AXE:
                if (isWaxedCopper(targetMaterial)) return true;
                if (Tag.LOGS.isTagged(targetMaterial)) return true;
                return switch (targetMaterial) {
                    case OAK_WOOD, DARK_OAK_WOOD, ACACIA_WOOD, BIRCH_WOOD, SPRUCE_WOOD, PUMPKIN, BAMBOO_BLOCK,
                            JUNGLE_WOOD, CRIMSON_STEM, WARPED_STEM, CRIMSON_HYPHAE, WARPED_HYPHAE ->
                            true;
                    default -> false;
                };
            case WOODEN_SHOVEL:
            case STONE_SHOVEL:
            case IRON_SHOVEL:
            case GOLDEN_SHOVEL:
            case DIAMOND_SHOVEL:
            case NETHERITE_SHOVEL:
                return switch (targetMaterial) {
                    case GRASS_BLOCK, CAMPFIRE, SOUL_CAMPFIRE -> true;
                    default -> false;
                };
            case SHEARS:
                return switch (targetMaterial) {
                    case PUMPKIN, BEE_NEST, BEEHIVE -> true;
                    default -> false;
                };
            case BLACK_DYE:
            case BLUE_DYE:
            case BROWN_DYE:
            case CYAN_DYE:
            case GRAY_DYE:
            case GREEN_DYE:
            case LIGHT_BLUE_DYE:
            case LIGHT_GRAY_DYE:
            case LIME_DYE:
            case MAGENTA_DYE:
            case ORANGE_DYE:
            case PINK_DYE:
            case PURPLE_DYE:
            case RED_DYE:
            case WHITE_DYE:
            case YELLOW_DYE:
            case GLOW_INK_SAC:
            case INK_SAC:
                return Tag.ALL_SIGNS.isTagged(targetMaterial);
            case HONEYCOMB:
                return isUnwaxedCopper(targetMaterial) || Tag.ALL_SIGNS.isTagged(targetMaterial);
            case BRUSH:
                return switch (targetMaterial) {
                    case SUSPICIOUS_GRAVEL, SUSPICIOUS_SAND -> true;
                    default -> false;
                };
            case WRITTEN_BOOK:
            case WRITABLE_BOOK:
                return targetMaterial == Material.LECTERN;
            default:
                return false;
        }
    }

    public static boolean isFire(Material type) {
        return type == Material.FIRE || type == Material.SOUL_FIRE;
    }

    public static boolean isWaxedCopper(Material type) {
        // copied from the MaterialTags class in Paper
        return type.name().startsWith("WAXED_") && type.name().contains("COPPER");
    }

    public static boolean isUnwaxedCopper(Material type) {
        String name = type.name();
        return type.isBlock() && name.contains("COPPER") && !name.startsWith("WAXED_")
                && !name.startsWith("RAW_") && !name.endsWith("_ORE");
    }

    public static boolean isAmethystGrowth(Material mat) {
        return mat == Material.BUDDING_AMETHYST
                || mat == Material.AMETHYST_CLUSTER
                || mat == Material.LARGE_AMETHYST_BUD
                || mat == Material.MEDIUM_AMETHYST_BUD
                || mat == Material.SMALL_AMETHYST_BUD;
    }

    public static boolean isSculkGrowth(Material mat) {
        return mat == Material.SCULK || mat == Material.SCULK_VEIN;
    }
}
