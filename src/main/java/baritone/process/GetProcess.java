/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.process.IGetProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMeta;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GetProcess extends BaritoneProcessHelper implements IGetProcess {

    private static final int MAX_RECIPE_DEPTH = 16;
    private static final int PICKUP_TIMEOUT_TICKS = 200;
    private static final int PICKUP_RETRY_COOLDOWN_TICKS = 200;
    private static final int CRAFTING_TABLE_SCAN_HORIZONTAL = 32;
    private static final int CRAFTING_TABLE_SCAN_VERTICAL = 8;

    private static final List<WoodOption> WOOD_OPTIONS = Arrays.asList(
            new WoodOption(Items.OAK_PLANKS, Items.OAK_LOG, Blocks.OAK_LOG),
            new WoodOption(Items.SPRUCE_PLANKS, Items.SPRUCE_LOG, Blocks.SPRUCE_LOG),
            new WoodOption(Items.BIRCH_PLANKS, Items.BIRCH_LOG, Blocks.BIRCH_LOG),
            new WoodOption(Items.JUNGLE_PLANKS, Items.JUNGLE_LOG, Blocks.JUNGLE_LOG),
            new WoodOption(Items.ACACIA_PLANKS, Items.ACACIA_LOG, Blocks.ACACIA_LOG),
            new WoodOption(Items.DARK_OAK_PLANKS, Items.DARK_OAK_LOG, Blocks.DARK_OAK_LOG),
            new WoodOption(Items.MANGROVE_PLANKS, Items.MANGROVE_LOG, Blocks.MANGROVE_LOG),
            new WoodOption(Items.CHERRY_PLANKS, Items.CHERRY_LOG, Blocks.CHERRY_LOG)
    );

    private final List<GetTarget> targets = new ArrayList<>();
    private int targetIndex;
    private Item target;
    private int quantity;

    private CraftAction craftAction;
    private SubAction subAction = SubAction.NONE;
    private Item miningItem;
    private Item pickupItem;
    private int pickupDesired;
    private int pickupTicks;
    private BlockPos craftingTablePos;
    private final Map<Item, List<Block>> mineSourceCache = new HashMap<>();
    private final Map<Item, Integer> pickupCooldowns = new HashMap<>();
    private String status = "Starting";
    private String focus = "";

    public GetProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void get(Item item, int quantity) {
        onLostControl();
        this.targets.add(new GetTarget(item, Math.max(1, quantity)));
        this.targetIndex = 0;
        this.target = item;
        this.quantity = Math.max(1, quantity);
        this.status = "Starting";
        this.focus = shortName(item);
    }

    @Override
    public void get(Map<Item, Integer> items) {
        onLostControl();
        items.forEach((item, count) -> {
            if (item != null && count != null && count > 0) {
                this.targets.add(new GetTarget(item, count));
            }
        });
        this.targetIndex = 0;
        if (!this.targets.isEmpty()) {
            activateTarget(this.targets.get(0));
        }
    }

    @Override
    public boolean isActive() {
        return target != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (target == null) {
            return null;
        }
        tickPickupCooldowns();

        if (count(target) >= quantity) {
            return finishTarget();
        }

        if (subAction == SubAction.PICKING_UP) {
            return commandForStep(ensureDroppedItem(pickupItem, pickupDesired));
        }

        if (craftAction != null) {
            return tickCraftAction(isSafeToCancel);
        }

        Step step = ensureItem(target, quantity, new HashSet<>());
        return commandForStep(step);
    }

    private PathingCommand commandForStep(Step step) {
        switch (step.type) {
            case READY:
            case PAUSE:
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            case DEFER:
                return new PathingCommand(null, PathingCommandType.DEFER);
            case UNSUPPORTED:
                logDirect("Cannot get " + name(target) + ": " + step.message);
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            default:
                throw new IllegalStateException("Unexpected get step " + step.type);
        }
    }

    private Step ensureItem(Item item, int desired, Set<Item> visiting) {
        status = "Planning";
        focus = "Resolving " + shortName(item) + " " + count(item) + "/" + desired;
        if (count(item) >= desired) {
            if (subAction == SubAction.MINING && item == miningItem) {
                clearSubAction();
            }
            return Step.ready();
        }
        if (visiting.size() >= MAX_RECIPE_DEPTH || !visiting.add(item)) {
            return Step.unsupported("recipe cycle while resolving " + name(item));
        }

        Step droppedItem = ensureDroppedItem(item, desired);
        if (droppedItem.type != StepType.READY) {
            visiting.remove(item);
            return droppedItem;
        }

        Optional<CraftingRecipe> recipe = findBestRecipe(item);
        if (recipe.isPresent()) {
            CraftingRecipe craftingRecipe = recipe.get();
            status = "Planning recipe";
            focus = shortName(item) + " from " + craftingRecipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count() + " ingredients";
            Map<Item, Integer> requirements = selectRequirements(craftingRecipe);
            if (requirements.isEmpty()) {
                visiting.remove(item);
                return Step.unsupported("recipe has no usable ingredients");
            }

            for (Map.Entry<Item, Integer> requirement : requirements.entrySet()) {
                Step step = ensureItem(requirement.getKey(), requirement.getValue(), visiting);
                if (step.type != StepType.READY) {
                    visiting.remove(item);
                    return step;
                }
            }

            for (Map.Entry<Item, Integer> requirement : requirements.entrySet()) {
                if (count(requirement.getKey()) < requirement.getValue()) {
                    Step step = ensureItem(requirement.getKey(), requirement.getValue(), visiting);
                    if (step.type != StepType.READY) {
                        visiting.remove(item);
                        return step;
                    }
                }
            }

            if (!craftingRecipe.canCraftInDimensions(2, 2)) {
                Step table = ensureCraftingTable(visiting);
                if (table.type != StepType.READY) {
                    visiting.remove(item);
                    return table;
                }
            }

            craftAction = new CraftAction(craftingRecipe, item, count(item));
            status = "Crafting queued";
            focus = shortName(item);
            visiting.remove(item);
            return Step.pause();
        }

        Step mined = ensureMined(item, desired, visiting);
        visiting.remove(item);
        return mined;
    }

    private Step ensureDroppedItem(Item item, int desired) {
        if (subAction == SubAction.PICKING_UP && item == pickupItem) {
            pickupTicks++;
            status = "Picking up";
            focus = shortName(item) + " " + count(item) + "/" + desired;
            if (count(item) >= desired) {
                clearSubAction();
                return Step.ready();
            }
            if (matchingDroppedItemCount(item) <= 0) {
                clearSubAction();
                return Step.pause();
            }
            if (pickupTicks > PICKUP_TIMEOUT_TICKS) {
                pickupCooldowns.put(item, PICKUP_RETRY_COOLDOWN_TICKS);
                clearSubAction();
                status = "Skipping dropped item";
                focus = shortName(item) + " pickup timed out";
                return Step.pause();
            }
            if (closeOpenGui("Closing GUI", "Picking up " + shortName(item))) {
                return Step.pause();
            }
            if (baritone.getFollowProcess().isActive()) {
                return Step.defer();
            }
            clearSubAction();
            return Step.pause();
        }

        if (pickupCooldowns.containsKey(item) || matchingDroppedItemCount(item) <= 0 || !hasInventorySpaceFor(item)) {
            return Step.ready();
        }
        if (closeOpenGui("Closing GUI", "Picking up " + shortName(item))) {
            return Step.pause();
        }
        subAction = SubAction.PICKING_UP;
        pickupItem = item;
        pickupTicks = 0;
        pickupDesired = desired;
        baritone.getFollowProcess().pickup(stack -> stack.is(item));
        status = "Picking up";
        focus = shortName(item) + " from dropped items";
        return Step.defer();
    }

    private Step ensureCraftingTable(Set<Item> visiting) {
        status = "Preparing crafting table";
        focus = "Need 3x3 crafting grid";
        if (ctx.player().containerMenu instanceof CraftingMenu) {
            clearSubAction();
            return Step.ready();
        }

        if (subAction == SubAction.BUILDING_CRAFTING_TABLE) {
            status = "Placing crafting table";
            focus = craftingTablePos == null ? "Waiting for placement" : formatPos(craftingTablePos);
            if (baritone.getBuilderProcess().isActive()) {
                return Step.defer();
            }
            if (craftingTablePos != null && ctx.world().getBlockState(craftingTablePos).getBlock() == Blocks.CRAFTING_TABLE) {
                subAction = SubAction.NONE;
                return openCraftingTable();
            }
            clearSubAction();
            return Step.unsupported("failed to place a crafting table");
        }

        if (subAction == SubAction.OPENING_CRAFTING_TABLE) {
            status = "Opening crafting table";
            focus = craftingTablePos == null ? "Finding table" : formatPos(craftingTablePos);
            if (ctx.player().containerMenu instanceof CraftingMenu) {
                clearSubAction();
                return Step.ready();
            }
            if (baritone.getGetToBlockProcess().isActive()) {
                return Step.defer();
            }
            clearSubAction();
        }

        Optional<BlockPos> nearby = findNearbyCraftingTable();
        if (nearby.isPresent()) {
            status = "Using nearby crafting table";
            focus = formatPos(nearby.get());
            craftingTablePos = nearby.get();
            return openCraftingTable();
        }

        Step tableItem = ensureItem(Items.CRAFTING_TABLE, 1, visiting);
        if (tableItem.type != StepType.READY) {
            return tableItem;
        }

        Step hotbar = selectOrMoveToHotbar(Items.CRAFTING_TABLE);
        if (hotbar.type != StepType.READY) {
            return hotbar;
        }

        if (closeOpenGui("Closing GUI", "Preparing to place crafting table")) {
            return Step.pause();
        }

        Optional<BlockPos> placement = findCraftingTablePlacement();
        if (placement.isEmpty()) {
            status = "Blocked";
            focus = "No safe crafting-table placement";
            return Step.unsupported("no safe nearby position for a crafting table");
        }

        BlockState[][][] states = new BlockState[][][]{{{Blocks.CRAFTING_TABLE.defaultBlockState()}}};
        craftingTablePos = placement.get();
        subAction = SubAction.BUILDING_CRAFTING_TABLE;
        baritone.getBuilderProcess().build("crafting table", new StaticSchematic(states), craftingTablePos);
        status = "Placing crafting table";
        focus = formatPos(craftingTablePos);
        return Step.defer();
    }

    private Step openCraftingTable() {
        status = "Opening crafting table";
        focus = craftingTablePos == null ? "Finding table" : formatPos(craftingTablePos);
        if (ctx.player().containerMenu instanceof CraftingMenu) {
            clearSubAction();
            return Step.ready();
        }
        if (closeOpenGui("Closing GUI", "Preparing to open crafting table")) {
            return Step.pause();
        }
        subAction = SubAction.OPENING_CRAFTING_TABLE;
        baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(Blocks.CRAFTING_TABLE));
        return Step.defer();
    }

    private Step ensureMined(Item item, int desired, Set<Item> visiting) {
        if (subAction == SubAction.MINING && item == miningItem) {
            status = "Mining";
            focus = shortName(item) + " " + count(item) + "/" + desired;
            if (closeOpenGui("Closing GUI", "Continuing mining")) {
                return Step.pause();
            }
            if (baritone.getMineProcess().isActive()) {
                return Step.defer();
            }
            if (count(item) >= desired) {
                clearSubAction();
                return Step.ready();
            }
            clearSubAction();
            return Step.unsupported("mining stopped before enough " + name(item) + " was collected");
        }

        List<Block> sources = mineSources(item);
        if (sources.isEmpty()) {
            return Step.unsupported("no crafting recipe or mined block source is registered");
        }

        Step tool = ensureMiningTool(item, sources, visiting);
        if (tool.type != StepType.READY) {
            return tool;
        }
        sources = mineableSources(sources);
        if (sources.isEmpty()) {
            return Step.unsupported("no source block can currently be mined for drops");
        }

        if (closeOpenGui("Closing GUI", "Preparing to mine " + shortName(item))) {
            return Step.pause();
        }

        miningItem = item;
        subAction = SubAction.MINING;
        baritone.getMineProcess().mine(desired, sources.toArray(new Block[0]));
        status = "Mining";
        focus = shortName(item) + " from " + summarizeBlocks(sources);
        return Step.defer();
    }

    private PathingCommand tickCraftAction(boolean isSafeToCancel) {
        status = "Crafting";
        focus = shortName(craftAction.item) + " " + craftStageName(craftAction.stage);
        if (!isSafeToCancel) {
            focus = "Waiting for safe pause";
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (!canCraftInMenu(craftAction.recipe, menu)) {
            status = "Waiting for crafting grid";
            focus = craftAction.recipe.canCraftInDimensions(2, 2) ? "Need inventory grid" : "Need crafting table";
            craftAction = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (craftAction.cooldown-- > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (craftAction.stage == CraftStage.PLACE_RECIPE) {
            if (ctx.minecraft().gameMode == null) {
                status = "Waiting for controller";
                focus = shortName(craftAction.item);
                craftAction = null;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            ctx.minecraft().gameMode.handlePlaceRecipe(menu.containerId, craftAction.recipe, false);
            craftAction.stage = CraftStage.TAKE_RESULT;
            craftAction.cooldown = 2;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (craftAction.stage == CraftStage.TAKE_RESULT) {
            if (menu.getSlot(0).hasItem() && menu.getSlot(0).getItem().is(craftAction.item)) {
                ctx.playerController().windowClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, ctx.player());
                craftAction.stage = CraftStage.VERIFY;
                craftAction.cooldown = 2;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (++craftAction.attempts > 10) {
                status = "Crafting retry failed";
                focus = shortName(craftAction.item);
                logDirect("Recipe did not produce " + name(craftAction.item));
                craftAction = null;
            } else if (ctx.minecraft().gameMode != null) {
                ctx.minecraft().gameMode.handlePlaceRecipe(menu.containerId, craftAction.recipe, false);
                craftAction.cooldown = 2;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (count(craftAction.item) > craftAction.previousCount) {
            status = "Crafted";
            focus = shortName(craftAction.item) + " " + count(craftAction.item) + "/" + quantity;
            craftAction = null;
        } else if (++craftAction.attempts > 20) {
            status = "Crafting verify failed";
            focus = shortName(craftAction.item);
            logDirect("Unable to verify crafted " + name(craftAction.item));
            craftAction = null;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean canCraftInMenu(CraftingRecipe recipe, AbstractContainerMenu menu) {
        if (menu instanceof CraftingMenu) {
            return recipe.canCraftInDimensions(3, 3);
        }
        return menu instanceof InventoryMenu && recipe.canCraftInDimensions(2, 2);
    }

    private Optional<CraftingRecipe> findBestRecipe(Item item) {
        Level world = ctx.world();
        if (world == null) {
            return Optional.empty();
        }
        return world.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)
                .stream()
                .filter(recipe -> recipe.getResultItem(world.registryAccess()).is(item))
                .filter(recipe -> !recipe.isSpecial())
                .min(Comparator.comparingInt(this::scoreRecipe));
    }

    private int scoreRecipe(CraftingRecipe recipe) {
        int score = recipe.canCraftInDimensions(2, 2) ? 0 : 10;
        Map<Item, Integer> reserved = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Item item = chooseIngredientItem(ingredient, reserved);
            if (item == null) {
                return Integer.MAX_VALUE;
            }
            reserved.merge(item, 1, Integer::sum);
            if (count(item) > 0) {
                score -= 5;
            } else if (!mineSources(item).isEmpty() || hasRecipe(item)) {
                score += 1;
            } else {
                score += 1000;
            }
        }
        return score;
    }

    private boolean hasRecipe(Item item) {
        Level world = ctx.world();
        return world != null && world.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)
                .stream()
                .anyMatch(recipe -> recipe.getResultItem(world.registryAccess()).is(item) && !recipe.isSpecial());
    }

    private Map<Item, Integer> selectRequirements(CraftingRecipe recipe) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        Map<Item, Integer> reserved = new HashMap<>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Item item = chooseIngredientItem(ingredient, reserved);
            if (item == null) {
                return Map.of();
            }
            result.merge(item, 1, Integer::sum);
            reserved.merge(item, 1, Integer::sum);
        }
        return result;
    }

    private Item chooseIngredientItem(Ingredient ingredient, Map<Item, Integer> reserved) {
        List<Item> candidates = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            Item item = stack.getItem();
            if (!candidates.contains(item)) {
                candidates.add(item);
            }
        }
        for (Item item : candidates) {
            if (count(item) - reserved.getOrDefault(item, 0) > 0) {
                return item;
            }
        }
        for (Item item : candidates) {
            if (matchingDroppedItemCount(item) > 0 && hasInventorySpaceFor(item)) {
                return item;
            }
        }
        Item woodChoice = chooseWoodIngredient(ingredient);
        if (woodChoice != null) {
            return woodChoice;
        }
        for (Item item : candidates) {
            if (!mineSources(item).isEmpty()) {
                return item;
            }
        }
        for (Item item : candidates) {
            if (hasRecipe(item)) {
                return item;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private Item chooseWoodIngredient(Ingredient ingredient) {
        for (WoodOption option : WOOD_OPTIONS) {
            if (ingredient.test(new ItemStack(option.planks)) && count(option.logItem) > 0) {
                return option.planks;
            }
        }
        for (WoodOption option : WOOD_OPTIONS) {
            if (ingredient.test(new ItemStack(option.logItem)) && hasNearbyBlock(option.logBlock, 24, 12)) {
                return option.logItem;
            }
        }
        for (WoodOption option : WOOD_OPTIONS) {
            if (ingredient.test(new ItemStack(option.planks)) && hasNearbyBlock(option.logBlock, 24, 12)) {
                return option.planks;
            }
        }
        for (WoodOption option : WOOD_OPTIONS) {
            if (ingredient.test(new ItemStack(option.planks))) {
                return option.planks;
            }
        }
        for (WoodOption option : WOOD_OPTIONS) {
            if (ingredient.test(new ItemStack(option.logItem))) {
                return option.logItem;
            }
        }
        return null;
    }

    private List<Block> mineSources(Item item) {
        return mineSourceCache.computeIfAbsent(item, this::findMineSources);
    }

    private List<Block> findMineSources(Item item) {
        ItemStack target = new ItemStack(item);
        List<Block> result = new ArrayList<>();
        Block direct = Block.byItem(item);
        if (direct != Blocks.AIR) {
            result.add(direct);
        }
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block == Blocks.AIR || result.contains(block)) {
                return;
            }
            try {
                if (new BlockOptionalMeta(block).matches(target)) {
                    result.add(block);
                }
            } catch (RuntimeException ignored) {
                // Some modded loot tables can be unavailable client-side; skip them instead of failing #get.
            }
        });
        return result;
    }

    private Step ensureMiningTool(Item item, List<Block> sources, Set<Item> visiting) {
        if (!mineableSources(sources).isEmpty()) {
            return Step.ready();
        }

        ToolRequirement best = null;
        for (Block source : sources) {
            ToolRequirement requirement = missingToolRequirement(source.defaultBlockState());
            if (requirement == null) {
                continue;
            }
            if (best == null || toolTier(requirement.tool) < toolTier(best.tool)) {
                best = requirement;
            }
        }

        if (best == null) {
            return Step.unsupported("no usable mining tool is known for " + summarizeBlocks(sources));
        }

        status = "Preparing tool";
        focus = "Need " + shortName(best.tool) + " for " + shortName(item);
        Step tool = ensureItem(best.tool, best.desiredTotal, visiting);
        if (tool.type != StepType.READY) {
            return tool;
        }
        if (closeOpenGui("Closing GUI", "Preparing " + shortName(best.tool))) {
            return Step.pause();
        }
        return selectOrMoveCorrectToolToHotbar(best.state, best.tool);
    }

    private List<Block> mineableSources(List<Block> sources) {
        List<Block> result = new ArrayList<>();
        for (Block source : sources) {
            if (canMineForDrops(source.defaultBlockState())) {
                result.add(source);
            }
        }
        return result;
    }

    private boolean canMineForDrops(BlockState state) {
        return !state.requiresCorrectToolForDrops() || findCorrectToolSlot(state, 0, 9) != -1;
    }

    private ToolRequirement missingToolRequirement(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return null;
        }

        Item existing = findCorrectToolItem(state, 0, 36);
        if (existing != null) {
            return new ToolRequirement(existing, state, 1);
        }

        Item minimum = minimumToolFor(state);
        return minimum == null ? null : new ToolRequirement(minimum, state, count(minimum) + 1);
    }

    private Item minimumToolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return tieredToolFor(state, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return tieredToolFor(state, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return tieredToolFor(state, Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL);
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return tieredToolFor(state, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE);
        }
        return null;
    }

    private Item tieredToolFor(BlockState state, Item wooden, Item stone, Item iron, Item diamond) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return diamond;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return iron;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return stone;
        }
        return wooden;
    }

    private Item findCorrectToolItem(BlockState state, int startInclusive, int endExclusive) {
        int slot = findCorrectToolSlot(state, startInclusive, endExclusive);
        return slot == -1 ? null : ctx.player().getInventory().items.get(slot).getItem();
    }

    private int findCorrectToolSlot(BlockState state, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (isUsableTool(stack) && stack.isCorrectToolForDrops(state)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isUsableTool(ItemStack stack) {
        return !stack.isEmpty()
                && (!Baritone.settings().itemSaver.value
                || stack.getMaxDamage() <= 1
                || stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value < stack.getMaxDamage());
    }

    private int toolTier(Item item) {
        if (item instanceof TieredItem) {
            return ((TieredItem) item).getTier().getLevel();
        }
        return 0;
    }

    private Step selectOrMoveCorrectToolToHotbar(BlockState state, Item item) {
        int hotbarSlot = findCorrectToolSlot(state, 0, 9);
        if (hotbarSlot != -1) {
            status = "Selecting";
            focus = shortName(item) + " on hotbar";
            ctx.player().getInventory().selected = hotbarSlot;
            return Step.ready();
        }

        int inventorySlot = findCorrectToolSlot(state, 9, 36);
        if (inventorySlot == -1) {
            return Step.unsupported("missing usable " + name(item));
        }

        status = "Moving inventory";
        focus = shortName(item) + " to hotbar";
        int targetHotbarSlot = firstEmptyHotbarSlot();
        if (targetHotbarSlot == -1) {
            targetHotbarSlot = 7;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inventorySlot, targetHotbarSlot, ClickType.SWAP, ctx.player());
        ctx.player().getInventory().selected = targetHotbarSlot;
        return Step.pause();
    }

    private Step selectOrMoveToHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().items.get(i).is(item)) {
                status = "Selecting";
                focus = shortName(item) + " on hotbar";
                ctx.player().getInventory().selected = i;
                return Step.ready();
            }
        }
        int inventorySlot = findInventorySlot(item, 9, 36);
        if (inventorySlot == -1) {
            return Step.unsupported("missing " + name(item));
        }
        status = "Moving inventory";
        focus = shortName(item) + " to hotbar";
        int hotbarSlot = firstEmptyHotbarSlot();
        if (hotbarSlot == -1) {
            hotbarSlot = 7;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inventorySlot, hotbarSlot, ClickType.SWAP, ctx.player());
        ctx.player().getInventory().selected = hotbarSlot;
        return Step.pause();
    }

    private int findInventorySlot(Item item, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (ctx.player().getInventory().items.get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int firstEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().items.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private Optional<BlockPos> findNearbyCraftingTable() {
        BlockPos feet = ctx.playerFeet();
        List<BlockPos> tables = new ArrayList<>();
        for (int dx = -CRAFTING_TABLE_SCAN_HORIZONTAL; dx <= CRAFTING_TABLE_SCAN_HORIZONTAL; dx++) {
            for (int dy = -CRAFTING_TABLE_SCAN_VERTICAL; dy <= CRAFTING_TABLE_SCAN_VERTICAL; dy++) {
                for (int dz = -CRAFTING_TABLE_SCAN_HORIZONTAL; dz <= CRAFTING_TABLE_SCAN_HORIZONTAL; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (ctx.world().getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                        tables.add(pos);
                    }
                }
            }
        }
        return tables.stream().min(Comparator.comparingDouble(feet::distSqr));
    }

    private boolean hasNearbyBlock(Block block, int horizontalRange, int verticalRange) {
        BlockPos feet = ctx.playerFeet();
        for (int dx = -horizontalRange; dx <= horizontalRange; dx++) {
            for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                for (int dz = -horizontalRange; dz <= horizontalRange; dz++) {
                    if (ctx.world().getBlockState(feet.offset(dx, dy, dz)).getBlock() == block) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Optional<BlockPos> findCraftingTablePlacement() {
        BlockPos feet = ctx.playerFeet();
        AABB playerBox = ctx.player().getBoundingBox().inflate(0.05);
        List<BlockPos> positions = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos pos = feet.offset(dx, 0, dz);
                    if (canPlaceCraftingTableAt(pos, playerBox)) {
                        positions.add(pos);
                    }
                }
            }
            if (!positions.isEmpty()) {
                break;
            }
        }
        return positions.stream().min(Comparator.comparingDouble(feet::distSqr));
    }

    private boolean canPlaceCraftingTableAt(BlockPos pos, AABB playerBox) {
        BlockState current = ctx.world().getBlockState(pos);
        BlockState support = ctx.world().getBlockState(pos.below());
        return current.canBeReplaced()
                && support.isCollisionShapeFullBlock(ctx.world(), pos.below())
                && !playerBox.intersects(new AABB(pos));
    }

    private int count(Item item) {
        int count = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int matchingDroppedItemCount(Item item) {
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        return ctx.entitiesStream()
                .filter(entity -> entity instanceof ItemEntity)
                .filter(Entity::isAlive)
                .filter(entity -> maxDist == 0 || entity.distanceToSqr(ctx.player()) <= maxDist * maxDist)
                .map(entity -> ((ItemEntity) entity).getItem())
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private boolean hasInventorySpaceFor(Item item) {
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private void tickPickupCooldowns() {
        pickupCooldowns.entrySet().removeIf(entry -> entry.setValue(entry.getValue() - 1) <= 0);
    }

    private void clearSubAction() {
        if (subAction == SubAction.PICKING_UP) {
            baritone.getFollowProcess().cancel();
        }
        subAction = SubAction.NONE;
        miningItem = null;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
    }

    private void activateTarget(GetTarget next) {
        this.target = next.item;
        this.quantity = next.quantity;
        this.craftAction = null;
        this.subAction = SubAction.NONE;
        this.miningItem = null;
        this.pickupItem = null;
        this.pickupDesired = 0;
        this.pickupTicks = 0;
        this.craftingTablePos = null;
        this.status = "Starting";
        this.focus = shortName(next.item);
    }

    private PathingCommand finishTarget() {
        logDirect("Have " + quantity + " " + name(target));
        cancelDelegatedProcesses();
        craftAction = null;
        subAction = SubAction.NONE;
        miningItem = null;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        craftingTablePos = null;
        int nextIndex = nextUnsatisfiedTargetIndex(targetIndex + 1);
        if (nextIndex == -1) {
            status = "Finished";
            focus = "";
            closeOpenGui();
            targets.clear();
            target = null;
            quantity = 0;
            targetIndex = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        targetIndex = nextIndex;
        activateTarget(targets.get(targetIndex));
        if (closeOpenGui("Closing GUI", "Next: " + shortName(target))) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private int nextUnsatisfiedTargetIndex(int startInclusive) {
        for (int i = startInclusive; i < targets.size(); i++) {
            GetTarget next = targets.get(i);
            if (count(next.item) < next.quantity) {
                return i;
            }
        }
        for (int i = 0; i < startInclusive && i < targets.size(); i++) {
            GetTarget next = targets.get(i);
            if (count(next.item) < next.quantity) {
                return i;
            }
        }
        return -1;
    }

    private boolean closeOpenGui(String nextStatus, String nextFocus) {
        if (!closeOpenGui()) {
            return false;
        }
        status = nextStatus;
        focus = nextFocus;
        return true;
    }

    private boolean closeOpenGui() {
        if (ctx.player() == null || ctx.minecraft() == null) {
            return false;
        }
        boolean closed = false;
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            ctx.player().closeContainer();
            closed = true;
        }
        if (ctx.minecraft().screen instanceof AbstractContainerScreen) {
            ctx.minecraft().setScreen(null);
            closed = true;
        }
        return closed;
    }

    @Override
    public void onLostControl() {
        closeOpenGui();
        targets.clear();
        targetIndex = 0;
        target = null;
        quantity = 0;
        craftAction = null;
        subAction = SubAction.NONE;
        miningItem = null;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        craftingTablePos = null;
        status = "Idle";
        focus = "";
        cancelDelegatedProcesses();
    }

    private void cancelDelegatedProcesses() {
        baritone.getMineProcess().cancel();
        baritone.getBuilderProcess().onLostControl();
        baritone.getGetToBlockProcess().onLostControl();
        baritone.getFollowProcess().cancel();
    }

    @Override
    public String displayName0() {
        if (targets.size() > 1) {
            return "Get batch " + (targetIndex + 1) + "/" + targets.size() + ": " + quantity + " " + name(target) + " - " + status;
        }
        return "Get " + quantity + " " + name(target) + " - " + status;
    }

    @Override
    public double priority() {
        return 2;
    }

    @Override
    public List<String> statusLines() {
        if (target == null) {
            return List.of("Get: inactive");
        }
        List<String> lines = new ArrayList<>();
        if (targets.size() > 1) {
            lines.add("Batch: " + (targetIndex + 1) + "/" + targets.size());
        }
        lines.add("Get: " + shortName(target) + " " + count(target) + "/" + quantity);
        lines.add("Status: " + status);
        if (!focus.isEmpty()) {
            lines.add("Task: " + focus);
        }
        if (targets.size() > 1 && targetIndex + 1 < targets.size()) {
            lines.add("Next: " + shortName(targets.get(targetIndex + 1).item));
        }
        baritone.getPathingControlManager()
                .mostRecentInControl()
                .filter(process -> process != this)
                .map(process -> "Active: " + process.displayName())
                .ifPresent(lines::add);
        return lines;
    }

    private static String name(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.toString();
    }

    private static String shortName(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.getPath();
    }

    private static String summarizeBlocks(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return "unknown blocks";
        }
        String first = BuiltInRegistries.BLOCK.getKey(blocks.get(0)).getPath();
        if (blocks.size() == 1) {
            return first;
        }
        return first + " +" + (blocks.size() - 1);
    }

    private static String craftStageName(CraftStage stage) {
        switch (stage) {
            case PLACE_RECIPE:
                return "placing recipe";
            case TAKE_RESULT:
                return "taking result";
            case VERIFY:
                return "verifying";
            default:
                return stage.name().toLowerCase();
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private enum SubAction {
        NONE,
        PICKING_UP,
        MINING,
        BUILDING_CRAFTING_TABLE,
        OPENING_CRAFTING_TABLE
    }

    private enum CraftStage {
        PLACE_RECIPE,
        TAKE_RESULT,
        VERIFY
    }

    private static final class CraftAction {
        private final CraftingRecipe recipe;
        private final Item item;
        private final int previousCount;
        private CraftStage stage = CraftStage.PLACE_RECIPE;
        private int cooldown;
        private int attempts;

        private CraftAction(CraftingRecipe recipe, Item item, int previousCount) {
            this.recipe = recipe;
            this.item = item;
            this.previousCount = previousCount;
        }
    }

    private static final class WoodOption {
        private final Item planks;
        private final Item logItem;
        private final Block logBlock;

        private WoodOption(Item planks, Item logItem, Block logBlock) {
            this.planks = planks;
            this.logItem = logItem;
            this.logBlock = logBlock;
        }
    }

    private static final class GetTarget {
        private final Item item;
        private final int quantity;

        private GetTarget(Item item, int quantity) {
            this.item = item;
            this.quantity = Math.max(1, quantity);
        }
    }

    private static final class ToolRequirement {
        private final Item tool;
        private final BlockState state;
        private final int desiredTotal;

        private ToolRequirement(Item tool, BlockState state, int desiredTotal) {
            this.tool = tool;
            this.state = state;
            this.desiredTotal = Math.max(1, desiredTotal);
        }
    }

    private enum StepType {
        READY,
        PAUSE,
        DEFER,
        UNSUPPORTED
    }

    private static final class Step {
        private final StepType type;
        private final String message;

        private Step(StepType type, String message) {
            this.type = type;
            this.message = message;
        }

        private static Step ready() {
            return new Step(StepType.READY, "");
        }

        private static Step pause() {
            return new Step(StepType.PAUSE, "");
        }

        private static Step defer() {
            return new Step(StepType.DEFER, "");
        }

        private static Step unsupported(String message) {
            return new Step(StepType.UNSUPPORTED, message);
        }
    }
}
