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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
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
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
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
    private static final int WORKSTATION_OPEN_MAX_ATTEMPTS = 3;

    private final List<GetTarget> targets = new ArrayList<>();
    private int targetIndex;
    private Item target;
    private int quantity;

    private CraftAction craftAction;
    private SmeltAction smeltAction;
    private SubAction subAction = SubAction.NONE;
    private Item miningItem;
    private Item pickupItem;
    private int pickupDesired;
    private int pickupTicks;
    private Block workstationBlock;
    private BlockPos workstationPos;
    private int workstationOpenAttempts;
    private final Map<Item, List<Block>> mineSourceCache = new HashMap<>();
    private final Map<Item, Boolean> mineDropSourceCache = new HashMap<>();
    private List<WoodOption> cachedWoodOptions;
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

        if (smeltAction != null) {
            return tickSmeltAction(isSafeToCancel);
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

        Optional<CraftingRecipe> recipe = findBestRecipe(item, visiting);
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
                Step table = ensureWorkstation(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, visiting);
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

        Optional<SmeltingRecipe> smelting = findBestSmeltingRecipe(item, visiting);
        if (smelting.isPresent()) {
            Step smelt = planSmelt(item, desired, smelting.get(), visiting);
            visiting.remove(item);
            return smelt;
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

    private Step ensureWorkstation(Block block, Item placeItem, Set<Item> visiting) {
        status = "Preparing " + shortName(placeItem);
        focus = block == Blocks.CRAFTING_TABLE ? "Need 3x3 crafting grid" : "Need " + shortName(placeItem);
        if (isWorkstationOpen(block)) {
            clearSubAction();
            return Step.ready();
        }

        if (subAction == SubAction.BUILDING_WORKSTATION && workstationBlock == block) {
            status = "Placing " + shortName(placeItem);
            focus = workstationPos == null ? "Waiting for placement" : formatPos(workstationPos);
            if (baritone.getBuilderProcess().isActive()) {
                return Step.defer();
            }
            if (workstationPos != null && ctx.world().getBlockState(workstationPos).getBlock() == block) {
                subAction = SubAction.NONE;
                return openWorkstation(block);
            }
            clearSubAction();
            return Step.unsupported("failed to place a " + shortName(placeItem));
        }

        if (subAction == SubAction.OPENING_WORKSTATION && workstationBlock == block) {
            status = "Opening " + shortName(placeItem);
            focus = workstationPos == null ? "Finding " + shortName(placeItem) : formatPos(workstationPos);
            if (isWorkstationOpen(block)) {
                workstationOpenAttempts = 0;
                clearSubAction();
                return Step.ready();
            }
            if (baritone.getGetToBlockProcess().isActive()) {
                return Step.defer();
            }
            if (workstationOpenAttempts >= WORKSTATION_OPEN_MAX_ATTEMPTS) {
                String name = shortName(placeItem);
                workstationOpenAttempts = 0;
                clearSubAction();
                return Step.unsupported("failed to open " + name + " after " + WORKSTATION_OPEN_MAX_ATTEMPTS + " attempts (menu did not open)");
            }
            clearSubAction();
        }

        Optional<BlockPos> nearby = findNearbyWorkstation(block);
        if (nearby.isPresent()) {
            status = "Using nearby " + shortName(placeItem);
            focus = formatPos(nearby.get());
            workstationBlock = block;
            workstationPos = nearby.get();
            return openWorkstation(block);
        }

        Step stationItem = ensureItem(placeItem, 1, visiting);
        if (stationItem.type != StepType.READY) {
            return stationItem;
        }

        Step hotbar = selectOrMoveToHotbar(placeItem);
        if (hotbar.type != StepType.READY) {
            return hotbar;
        }

        if (closeOpenGui("Closing GUI", "Preparing to place " + shortName(placeItem))) {
            return Step.pause();
        }

        Optional<BlockPos> placement = findWorkstationPlacement();
        if (placement.isEmpty()) {
            status = "Blocked";
            focus = "No safe placement for " + shortName(placeItem);
            return Step.unsupported("no safe nearby position for a " + shortName(placeItem));
        }

        workstationBlock = block;
        workstationPos = placement.get();
        workstationOpenAttempts = 0;
        subAction = SubAction.BUILDING_WORKSTATION;
        baritone.getBuilderProcess().build(shortName(placeItem), workstationSchematic(block), workstationPos);
        status = "Placing " + shortName(placeItem);
        focus = formatPos(workstationPos);
        return Step.defer();
    }

    private StaticSchematic workstationSchematic(Block block) {
        BlockState[][][] states = new BlockState[][][]{{{block.defaultBlockState()}}};
        return new StaticSchematic(states) {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                if (current.getBlock() == block) {
                    return current;
                }
                for (BlockState placeable : approxPlaceable) {
                    if (placeable.getBlock() == block) {
                        return placeable;
                    }
                }
                return block.defaultBlockState();
            }
        };
    }

    private Step openWorkstation(Block block) {
        status = "Opening " + shortName(block.asItem());
        focus = workstationPos == null ? "Finding block" : formatPos(workstationPos);
        if (isWorkstationOpen(block)) {
            workstationOpenAttempts = 0;
            clearSubAction();
            return Step.ready();
        }
        if (closeOpenGui("Closing GUI", "Preparing to open " + shortName(block.asItem()))) {
            return Step.pause();
        }
        workstationBlock = block;
        workstationOpenAttempts++;
        subAction = SubAction.OPENING_WORKSTATION;
        baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(block));
        return Step.defer();
    }

    private boolean isWorkstationOpen(Block block) {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (block == Blocks.CRAFTING_TABLE) {
            return menu instanceof CraftingMenu;
        }
        if (block == Blocks.FURNACE) {
            return menu instanceof FurnaceMenu;
        }
        return false;
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
        baritone.getMineProcess().mine(batchedMineTarget(item, desired), sources.toArray(new Block[0]));
        status = "Mining";
        focus = shortName(item) + " from " + summarizeBlocks(sources);
        return Step.defer();
    }

    /**
     * How many of {@code item} the mine process should gather in one trip: enough for the immediate recipe
     * ({@code desired}) but ideally enough for the entire remaining job, so we don't walk back to the same
     * ore/tree once per sub-recipe. Falls back to {@code desired} if the estimate fails.
     */
    private int batchedMineTarget(Item item, int desired) {
        int extra;
        try {
            extra = gatherNeeds().getOrDefault(item, 0);
        } catch (RuntimeException ignored) {
            extra = 0;
        }
        // gatherNeeds() already subtracts current inventory, so target = what we hold + what is still needed.
        int target = Math.max(desired, count(item) + Math.min(extra, 1024));
        return target;
    }

    /**
     * A bill of materials for the active target: how many of each base resource (mined/picked-up item) must
     * still be gathered, accounting for current inventory, recipe yields, the tools required to mine ores,
     * and the crafting table / furnace that crafting and smelting need.
     */
    private Map<Item, Integer> gatherNeeds() {
        Map<Item, Integer> gather = new HashMap<>();
        if (target == null || ctx.world() == null) {
            return gather;
        }
        expand(target, quantity, gather, inventorySnapshot(), new HashSet<>());
        return gather;
    }

    private Map<Item, Integer> inventorySnapshot() {
        Map<Item, Integer> snapshot = new HashMap<>();
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty()) {
                snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (!stack.isEmpty()) {
                snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return snapshot;
    }

    private void expand(Item item, int qty, Map<Item, Integer> gather, Map<Item, Integer> available, Set<Item> visiting) {
        int have = available.getOrDefault(item, 0);
        int use = Math.min(have, qty);
        if (use > 0) {
            available.put(item, have - use);
        }
        int remaining = qty - use;
        if (remaining <= 0) {
            return;
        }
        if (visiting.size() >= MAX_RECIPE_DEPTH || !visiting.add(item)) {
            gather.merge(item, remaining, Integer::sum);
            return;
        }
        try {
            Optional<CraftingRecipe> recipe = findBestRecipe(item, visiting);
            if (recipe.isPresent()) {
                CraftingRecipe craftingRecipe = recipe.get();
                int yield = Math.max(1, craftingRecipe.getResultItem(ctx.world().registryAccess()).getCount());
                int crafts = (remaining + yield - 1) / yield;
                if (!craftingRecipe.canCraftInDimensions(2, 2)) {
                    planWorkstation(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, gather, available, visiting);
                }
                for (Map.Entry<Item, Integer> requirement : selectRequirements(craftingRecipe).entrySet()) {
                    expand(requirement.getKey(), requirement.getValue() * crafts, gather, available, visiting);
                }
                // Surplus from rounding crafts up can satisfy other needs for the same item.
                available.merge(item, crafts * yield - remaining, Integer::sum);
                return;
            }
            Optional<SmeltingRecipe> smelting = findBestSmeltingRecipe(item, visiting);
            if (smelting.isPresent()) {
                planWorkstation(Blocks.FURNACE, Items.FURNACE, gather, available, visiting);
                Item input = firstSmeltInput(smelting.get());
                if (input != null) {
                    expand(input, remaining, gather, available, visiting);
                }
                Item fuel = chooseFuel(visiting);
                if (fuel != null) {
                    int perFuel = Math.max(1, AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel, 0) / 200);
                    expand(fuel, (remaining + perFuel - 1) / perFuel, gather, available, visiting);
                }
                return;
            }
            planMiningTool(item, gather, available, visiting);
            gather.merge(item, remaining, Integer::sum);
        } finally {
            visiting.remove(item);
        }
    }

    private void planWorkstation(Block block, Item blockItem, Map<Item, Integer> gather, Map<Item, Integer> available, Set<Item> visiting) {
        if (available.getOrDefault(blockItem, 0) > 0) {
            return;
        }
        if (isWorkstationOpen(block) || findNearbyWorkstation(block).isPresent()) {
            available.merge(blockItem, 1, Integer::sum);
            return;
        }
        expand(blockItem, 1, gather, available, visiting);
        available.merge(blockItem, 1, Integer::sum);
    }

    private void planMiningTool(Item item, Map<Item, Integer> gather, Map<Item, Integer> available, Set<Item> visiting) {
        Item tool = requiredMiningToolType(item);
        if (tool == null || available.getOrDefault(tool, 0) > 0) {
            return;
        }
        expand(tool, 1, gather, available, visiting);
        available.merge(tool, 1, Integer::sum);
    }

    private Item requiredMiningToolType(Item item) {
        Item best = null;
        for (Block source : mineSources(item)) {
            BlockState state = source.defaultBlockState();
            if (!state.requiresCorrectToolForDrops() || findCorrectToolSlot(state, 0, 36) != -1) {
                return null; // a source is mineable bare-handed, or we already hold a correct tool
            }
            Item minimum = minimumToolFor(state);
            if (minimum == null) {
                continue;
            }
            if (best == null || toolTier(minimum) < toolTier(best)) {
                best = minimum;
            }
        }
        return best;
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

    private PathingCommand tickSmeltAction(boolean isSafeToCancel) {
        status = "Smelting";
        focus = shortName(smeltAction.item) + " " + count(smeltAction.item) + "/" + smeltAction.desired;
        if (!isSafeToCancel) {
            focus = "Waiting for safe pause";
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (!(menu instanceof FurnaceMenu)) {
            // The furnace GUI closed unexpectedly; let planning reopen it.
            smeltAction = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (smeltAction.cooldown-- > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Pull finished results out of the furnace first so they count toward the goal.
        if (menu.getSlot(2).hasItem()) {
            ctx.playerController().windowClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, ctx.player());
            smeltAction.cooldown = 2;
            smeltAction.attempts = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (count(smeltAction.item) >= smeltAction.desired) {
            status = "Smelted";
            focus = shortName(smeltAction.item) + " " + count(smeltAction.item) + "/" + quantity;
            smeltAction = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        boolean loaded = false;
        if (!menu.getSlot(1).hasItem() && count(smeltAction.fuel) > 0 && moveToFurnace(menu, smeltAction.fuel)) {
            loaded = true;
        }
        if (!menu.getSlot(0).hasItem() && count(smeltAction.input) > 0 && moveToFurnace(menu, smeltAction.input)) {
            loaded = true;
        }
        if (loaded) {
            smeltAction.cooldown = 2;
            smeltAction.attempts = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Something is still cooking in the input slot; wait for it.
        if (menu.getSlot(0).hasItem()) {
            smeltAction.attempts = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Nothing left to load and nothing cooking: we cannot reach the goal anymore.
        if (count(smeltAction.input) <= 0) {
            status = "Smelting stalled";
            focus = shortName(smeltAction.item);
            logDirect("Smelting stopped before enough " + name(smeltAction.item) + " was produced (out of " + name(smeltAction.input) + ")");
            smeltAction = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (++smeltAction.attempts > 20) {
            status = "Smelting stalled";
            focus = shortName(smeltAction.item);
            logDirect("Unable to make progress smelting " + name(smeltAction.item));
            smeltAction = null;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean moveToFurnace(AbstractContainerMenu menu, Item item) {
        // Furnace slots 0 (input), 1 (fuel) and 2 (result) precede the player inventory slots.
        for (int i = 3; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(item)) {
                ctx.playerController().windowClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                return true;
            }
        }
        return false;
    }

    private Optional<CraftingRecipe> findBestRecipe(Item item, Set<Item> visiting) {
        return craftingRecipesFor(item)
                .stream()
                .filter(recipe -> !isDecompressionRecipe(item, recipe))
                .filter(recipe -> isRecipeObtainable(recipe, visiting))
                .min(Comparator.comparingInt(this::scoreRecipe));
    }

    /**
     * Whether {@code recipe} just unpacks a storage block into many copies of {@code item}
     * (e.g. iron_block -> 9 iron_ingot, raw_iron_block -> 9 raw_iron). Such recipes are the reverse of a
     * compression recipe and only create cycles when we are trying to obtain the small item, so we skip
     * them unless the storage block is already on hand.
     */
    private boolean isDecompressionRecipe(Item item, CraftingRecipe recipe) {
        Level world = ctx.world();
        if (world == null || recipe.getResultItem(world.registryAccess()).getCount() <= 1) {
            return false;
        }
        Map<Item, Integer> requirements = selectRequirements(recipe);
        if (requirements.size() != 1) {
            return false;
        }
        Map.Entry<Item, Integer> only = requirements.entrySet().iterator().next();
        Item block = only.getKey();
        if (only.getValue() != 1 || count(block) > 0) {
            return false;
        }
        for (CraftingRecipe reverse : craftingRecipesFor(block)) {
            if (selectRequirements(reverse).containsKey(item)) {
                return true;
            }
        }
        return false;
    }

    private List<CraftingRecipe> craftingRecipesFor(Item item) {
        Level world = ctx.world();
        if (world == null) {
            return List.of();
        }
        return world.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)
                .stream()
                .filter(recipe -> recipe.getResultItem(world.registryAccess()).is(item))
                .filter(recipe -> !recipe.isSpecial())
                .toList();
    }

    private List<SmeltingRecipe> smeltingRecipesFor(Item item) {
        Level world = ctx.world();
        if (world == null) {
            return List.of();
        }
        return world.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)
                .stream()
                .filter(recipe -> recipe.getResultItem(world.registryAccess()).is(item))
                .toList();
    }

    private Optional<SmeltingRecipe> findBestSmeltingRecipe(Item item, Set<Item> visiting) {
        return smeltingRecipesFor(item)
                .stream()
                .filter(recipe -> {
                    Item input = firstSmeltInput(recipe);
                    return input != null && canObtain(input, visiting);
                })
                .min(Comparator.comparingInt(this::scoreSmeltingRecipe));
    }

    private int scoreSmeltingRecipe(SmeltingRecipe recipe) {
        Item input = firstSmeltInput(recipe);
        if (input == null) {
            return Integer.MAX_VALUE;
        }
        if (count(input) > 0) {
            return 0;
        }
        if (matchingDroppedItemCount(input) > 0) {
            return 1;
        }
        // Prefer an input that a block actually drops when mined (e.g. raw_iron from iron_ore)
        // over one only reachable by silk-touching the ore block itself (the iron_ore item).
        if (hasMineDropSource(input)) {
            return 2;
        }
        if (!craftingRecipesFor(input).isEmpty()) {
            return 3;
        }
        return 5;
    }

    private boolean hasMineDropSource(Item item) {
        return mineDropSourceCache.computeIfAbsent(item, this::computeHasMineDropSource);
    }

    private boolean computeHasMineDropSource(Item item) {
        ItemStack target = new ItemStack(item);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == Blocks.AIR) {
                continue;
            }
            try {
                if (new BlockOptionalMeta(block).matches(target)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Some modded loot tables can be unavailable client-side; skip them instead of failing #get.
            }
        }
        return false;
    }

    private Item firstSmeltInput(SmeltingRecipe recipe) {
        if (recipe.getIngredients().isEmpty()) {
            return null;
        }
        Ingredient ingredient = recipe.getIngredients().get(0);
        if (ingredient.isEmpty()) {
            return null;
        }
        return chooseIngredientItem(ingredient, new HashMap<>());
    }

    private boolean isRecipeObtainable(CraftingRecipe recipe, Set<Item> visiting) {
        Map<Item, Integer> requirements = selectRequirements(recipe);
        if (requirements.isEmpty()) {
            return false;
        }
        for (Item requirement : requirements.keySet()) {
            // An ingredient that is already being resolved would be a recipe cycle
            // (e.g. iron_ingot crafted from iron_block while resolving iron_block).
            if (visiting.contains(requirement) || !canObtain(requirement, visiting)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code item} can ultimately be obtained from the current world/inventory without revisiting an item
     * already being resolved (which would be a recipe cycle, e.g. iron_block -> iron_ingot -> iron_block).
     */
    private boolean canObtain(Item item, Set<Item> chain) {
        if (count(item) > 0) {
            return true;
        }
        if (matchingDroppedItemCount(item) > 0 && hasInventorySpaceFor(item)) {
            return true;
        }
        if (!mineSources(item).isEmpty()) {
            return true;
        }
        if (chain.size() >= MAX_RECIPE_DEPTH || !chain.add(item)) {
            return false;
        }
        boolean obtainable = false;
        for (SmeltingRecipe recipe : smeltingRecipesFor(item)) {
            Item input = firstSmeltInput(recipe);
            if (input != null && !chain.contains(input) && canObtain(input, chain)) {
                obtainable = true;
                break;
            }
        }
        if (!obtainable) {
            for (CraftingRecipe recipe : craftingRecipesFor(item)) {
                if (isDecompressionRecipe(item, recipe)) {
                    continue;
                }
                Map<Item, Integer> requirements = selectRequirements(recipe);
                if (requirements.isEmpty()) {
                    continue;
                }
                boolean all = true;
                for (Item requirement : requirements.keySet()) {
                    if (chain.contains(requirement) || !canObtain(requirement, chain)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    obtainable = true;
                    break;
                }
            }
        }
        chain.remove(item);
        return obtainable;
    }

    private Step planSmelt(Item item, int desired, SmeltingRecipe recipe, Set<Item> visiting) {
        status = "Planning smelt";
        focus = shortName(item) + " by smelting";
        Item input = firstSmeltInput(recipe);
        if (input == null) {
            return Step.unsupported("smelting recipe has no usable input");
        }

        int needed = Math.max(1, desired - count(item));

        Step inputStep = ensureItem(input, needed, visiting);
        if (inputStep.type != StepType.READY) {
            return inputStep;
        }

        Item fuel = chooseFuel(visiting);
        if (fuel == null) {
            return Step.unsupported("no usable fuel is available for smelting");
        }
        int perFuel = Math.max(1, AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel, 0) / 200);
        int fuelNeeded = Math.max(1, (needed + perFuel - 1) / perFuel);
        Step fuelStep = ensureItem(fuel, fuelNeeded, visiting);
        if (fuelStep.type != StepType.READY) {
            return fuelStep;
        }

        Step furnace = ensureWorkstation(Blocks.FURNACE, Items.FURNACE, visiting);
        if (furnace.type != StepType.READY) {
            return furnace;
        }

        smeltAction = new SmeltAction(item, input, fuel, desired);
        status = "Smelting queued";
        focus = shortName(item) + " from " + shortName(input);
        return Step.pause();
    }

    private Item chooseFuel(Set<Item> visiting) {
        Map<Item, Integer> fuels = AbstractFurnaceBlockEntity.getFuel();
        if (count(Items.COAL) > 0) {
            return Items.COAL;
        }
        if (count(Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        for (Item fuel : fuels.keySet()) {
            if (isReasonableFuel(fuel) && count(fuel) > 0) {
                return fuel;
            }
        }
        if (fuels.containsKey(Items.COAL) && canObtain(Items.COAL, visiting)) {
            return Items.COAL;
        }
        if (fuels.containsKey(Items.CHARCOAL) && canObtain(Items.CHARCOAL, visiting)) {
            return Items.CHARCOAL;
        }
        for (Item fuel : fuels.keySet()) {
            if (isReasonableFuel(fuel) && canObtain(fuel, visiting)) {
                return fuel;
            }
        }
        return null;
    }

    private boolean isReasonableFuel(Item fuel) {
        // Avoid consuming a bucket of lava as a one-off fuel when cheaper options exist.
        return fuel != Items.LAVA_BUCKET;
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
        List<WoodOption> options = woodOptions();
        // Already hold a log whose planks satisfy the ingredient: turn that log into planks.
        for (WoodOption option : options) {
            if (count(option.logItem) > 0 && ingredient.test(new ItemStack(option.planks))) {
                return option.planks;
            }
        }
        // Chop the closest matching tree, not just the first wood type that happens to be in range.
        WoodOption nearest = findNearestWood(options, ingredient);
        if (nearest != null) {
            // Use the log directly if the recipe wants a log; otherwise craft planks from it.
            return ingredient.test(new ItemStack(nearest.logItem)) ? nearest.logItem : nearest.planks;
        }
        // Nothing nearby; fall back to any accepted planks, then any accepted log.
        for (WoodOption option : options) {
            if (ingredient.test(new ItemStack(option.planks))) {
                return option.planks;
            }
        }
        for (WoodOption option : options) {
            if (ingredient.test(new ItemStack(option.logItem))) {
                return option.logItem;
            }
        }
        return null;
    }

    private WoodOption findNearestWood(List<WoodOption> options, Ingredient ingredient) {
        // Map every log block whose log or planks the ingredient accepts back to its wood option,
        // then sweep the area once and keep the closest hit.
        Map<Block, WoodOption> byBlock = new HashMap<>();
        for (WoodOption option : options) {
            if (ingredient.test(new ItemStack(option.logItem)) || ingredient.test(new ItemStack(option.planks))) {
                byBlock.putIfAbsent(option.logBlock, option);
            }
        }
        if (byBlock.isEmpty()) {
            return null;
        }
        BlockPos feet = ctx.playerFeet();
        WoodOption best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -24; dx <= 24; dx++) {
            for (int dy = -12; dy <= 12; dy++) {
                for (int dz = -24; dz <= 24; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    WoodOption option = byBlock.get(ctx.world().getBlockState(pos).getBlock());
                    if (option == null) {
                        continue;
                    }
                    double distSq = feet.distSqr(pos);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = option;
                    }
                }
            }
        }
        return best;
    }

    /**
     * All planks -> log mappings known to the current world, derived from the loaded recipes instead of a
     * hardcoded list. This automatically covers every wood/stem type (including stripped and "wood"/"hyphae"
     * variants, nether stems and pale oak) as well as any modded woods, and stays correct across versions.
     */
    private List<WoodOption> woodOptions() {
        if (cachedWoodOptions != null) {
            return cachedWoodOptions;
        }
        Level world = ctx.world();
        if (world == null) {
            return List.of();
        }
        List<WoodOption> options = new ArrayList<>();
        for (CraftingRecipe recipe : world.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe.isSpecial()) {
                continue;
            }
            ItemStack result = recipe.getResultItem(world.registryAccess());
            if (!result.is(ItemTags.PLANKS)) {
                continue;
            }
            Ingredient logIngredient = null;
            int nonEmpty = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                nonEmpty++;
                logIngredient = ingredient;
            }
            if (nonEmpty != 1) {
                continue;
            }
            for (ItemStack logStack : logIngredient.getItems()) {
                Item logItem = logStack.getItem();
                Block logBlock = Block.byItem(logItem);
                if (logBlock != Blocks.AIR) {
                    options.add(new WoodOption(result.getItem(), logItem, logBlock));
                }
            }
        }
        cachedWoodOptions = options;
        return options;
    }

    private List<Block> mineSources(Item item) {
        return mineSourceCache.computeIfAbsent(item, this::findMineSources);
    }

    private List<Block> findMineSources(Item item) {
        ItemStack target = new ItemStack(item);
        List<Block> result = new ArrayList<>();
        if (item == Items.COBBLESTONE) {
            result.add(Blocks.STONE);
        }
        Block direct = Block.byItem(item);
        if (direct != Blocks.AIR && !skipMineSource(item, direct)) {
            result.add(direct);
        }
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block == Blocks.AIR || result.contains(block) || skipMineSource(item, block)) {
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

    private boolean skipMineSource(Item item, Block block) {
        return item == Items.COBBLESTONE && block == Blocks.COBBLESTONE;
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

    private Optional<BlockPos> findNearbyWorkstation(Block block) {
        BlockPos feet = ctx.playerFeet();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -CRAFTING_TABLE_SCAN_HORIZONTAL; dx <= CRAFTING_TABLE_SCAN_HORIZONTAL; dx++) {
            for (int dy = -CRAFTING_TABLE_SCAN_VERTICAL; dy <= CRAFTING_TABLE_SCAN_VERTICAL; dy++) {
                for (int dz = -CRAFTING_TABLE_SCAN_HORIZONTAL; dz <= CRAFTING_TABLE_SCAN_HORIZONTAL; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (ctx.world().getBlockState(pos).getBlock() == block) {
                        found.add(pos);
                    }
                }
            }
        }
        return found.stream().min(Comparator.comparingDouble(feet::distSqr));
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

    private Optional<BlockPos> findWorkstationPlacement() {
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
                    if (canPlaceWorkstationAt(pos, playerBox)) {
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

    private boolean canPlaceWorkstationAt(BlockPos pos, AABB playerBox) {
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
        this.smeltAction = null;
        this.subAction = SubAction.NONE;
        this.miningItem = null;
        this.pickupItem = null;
        this.pickupDesired = 0;
        this.pickupTicks = 0;
        this.workstationBlock = null;
        this.workstationPos = null;
        this.workstationOpenAttempts = 0;
        this.status = "Starting";
        this.focus = shortName(next.item);
    }

    private PathingCommand finishTarget() {
        logDirect("Have " + quantity + " " + name(target));
        cancelDelegatedProcesses();
        craftAction = null;
        smeltAction = null;
        subAction = SubAction.NONE;
        miningItem = null;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        workstationBlock = null;
        workstationPos = null;
        workstationOpenAttempts = 0;
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
        smeltAction = null;
        subAction = SubAction.NONE;
        miningItem = null;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        workstationBlock = null;
        workstationPos = null;
        workstationOpenAttempts = 0;
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
        BUILDING_WORKSTATION,
        OPENING_WORKSTATION
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

    private static final class SmeltAction {
        private final Item item;
        private final Item input;
        private final Item fuel;
        private final int desired;
        private int cooldown;
        private int attempts;

        private SmeltAction(Item item, Item input, Item fuel, int desired) {
            this.item = item;
            this.input = input;
            this.fuel = fuel;
            this.desired = desired;
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
