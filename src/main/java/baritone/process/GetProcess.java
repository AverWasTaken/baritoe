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
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
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
    private static final int WORKSTATION_RECLAIM_DISTANCE = 24;
    private static final int SMELT_REOPEN_BUFFER_TICKS = 30;
    private static final double UNKNOWN_SOURCE_DISTANCE = Double.MAX_VALUE;

    private final List<GetTarget> targets = new ArrayList<>();
    private int targetIndex;
    private Item target;
    private int quantity;

    private CraftAction craftAction;
    private SmeltAction smeltAction;
    private SubAction subAction = SubAction.NONE;
    private Item miningItem;
    private int miningDesired;
    private Item pickupItem;
    private int pickupDesired;
    private int pickupTicks;
    private Block workstationBlock;
    private BlockPos workstationPos;
    private int workstationOpenAttempts;
    private TemporaryWorkstation reclaimingWorkstation;
    private int reclaimingDesired;
    private final List<TemporaryWorkstation> temporaryWorkstations = new ArrayList<>();
    private final Map<Item, List<Block>> mineSourceCache = new HashMap<>();
    private final Map<Item, Boolean> mineDropSourceCache = new HashMap<>();
    private List<WoodOption> cachedWoodOptions;
    private final Map<Item, Integer> pickupCooldowns = new HashMap<>();
    private final Map<String, Integer> getDebugCooldowns = new HashMap<>();
    private FuelPlan cachedFuelPlan;
    private String status = "Starting";
    private String focus = "";
    private int getDebugTick;

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
        getDebug("target " + shortName(item) + " x" + this.quantity);
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
            getDebug("batch targets " + this.targets.size());
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
        tickGetDebug();

        if (count(target) >= quantity) {
            return finishTarget();
        }

        if (subAction == SubAction.PICKING_UP) {
            return commandForStep(ensureDroppedItem(pickupItem, pickupDesired));
        }

        if (subAction == SubAction.RECLAIMING_WORKSTATION) {
            return commandForStep(ensureReclaimingWorkstation());
        }

        if (craftAction != null) {
            return tickCraftAction(isSafeToCancel);
        }

        if (smeltAction != null) {
            return tickSmeltAction(isSafeToCancel);
        }

        Step gatherStep = ensureNearestGatherNeed();
        if (gatherStep.type != StepType.READY) {
            return commandForStep(gatherStep);
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
            getDebug("recipe " + shortName(item) + " <- " + summarizeItems(requirements));
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

        if (!mineSources(item).isEmpty()) {
            Step mined = ensureMined(item, desired, visiting);
            visiting.remove(item);
            return mined;
        }

        Optional<SmeltingRecipe> smelting = findBestSmeltingRecipe(item, visiting);
        if (smelting.isPresent()) {
            getDebug("smelt recipe " + shortName(item) + " input " + shortName(firstSmeltInput(smelting.get())));
            Step smelt = planSmelt(item, desired, smelting.get(), visiting);
            visiting.remove(item);
            return smelt;
        }

        visiting.remove(item);
        return Step.unsupported("no crafting recipe, smelting recipe, or mined block source is registered");
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
        getDebug("pickup " + shortName(item) + " total " + desired + " dropped " + matchingDroppedItemCount(item));
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
                recordTemporaryWorkstation(block, placeItem, workstationPos);
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

        Optional<BlockPos> nearby = knownTemporaryWorkstation(block).or(() -> findNearbyWorkstation(block));
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

        if (closeOpenGui("Closing GUI", "Preparing to place " + shortName(placeItem))) {
            return Step.pause();
        }

        Step hotbar = selectOrMoveToHotbar(placeItem);
        if (hotbar.type != StepType.READY) {
            return hotbar;
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
        getDebug("place workstation " + shortName(placeItem) + " at " + formatPos(workstationPos));
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

    private AbstractContainerMenu activeMenu() {
        if (ctx.minecraft() != null && ctx.minecraft().screen instanceof AbstractContainerScreen<?> screen) {
            return screen.getMenu();
        }
        return ctx.player().containerMenu;
    }

    private Step ensureMined(Item item, int desired, Set<Item> visiting) {
        if (subAction == SubAction.MINING && item == miningItem) {
            status = "Mining";
            focus = shortName(item) + " " + count(item) + "/" + Math.max(desired, miningDesired);
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

        Step reclaim = ensureReclaimedBeforeTravel(nearestMineSourceDistanceSq(item));
        if (reclaim.type != StepType.READY) {
            return reclaim;
        }

        if (closeOpenGui("Closing GUI", "Preparing to mine " + shortName(item))) {
            return Step.pause();
        }

        miningItem = item;
        miningDesired = batchedMineTarget(item, desired);
        subAction = SubAction.MINING;
        getDebug("mine " + shortName(item) + " total " + miningDesired + " sources " + summarizeBlocks(sources));
        baritone.getMineProcess().mine(miningDesired, sources.toArray(new Block[0]));
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

    private Step ensureNearestGatherNeed() {
        if (subAction == SubAction.MINING && miningItem != null) {
            return ensureMined(miningItem, Math.max(miningDesired, count(miningItem)), new HashSet<>());
        }
        if (subAction != SubAction.NONE) {
            return Step.ready();
        }

        GatherNeed need = nearestGatherNeed(gatherNeeds());
        if (need == null) {
            return Step.ready();
        }

        Step reclaim = ensureReclaimedBeforeTravel(need.distanceSq);
        if (reclaim.type != StepType.READY) {
            return reclaim;
        }

        getDebug("nearest gather " + shortName(need.item) + " total " + need.desiredTotal + " dist " + formatDistanceSq(need.distanceSq));
        status = "Gathering materials";
        focus = shortName(need.item) + " " + count(need.item) + "/" + need.desiredTotal;
        Step dropped = ensureDroppedItem(need.item, need.desiredTotal);
        if (dropped.type != StepType.READY) {
            return dropped;
        }

        if (mineSources(need.item).isEmpty()) {
            return Step.ready();
        }
        return ensureMined(need.item, need.desiredTotal, new HashSet<>());
    }

    private Step ensureReclaimedBeforeTravel(double distanceSq) {
        pruneTemporaryWorkstations();
        if (temporaryWorkstations.isEmpty()) {
            return Step.ready();
        }
        if (distanceSq != UNKNOWN_SOURCE_DISTANCE && distanceSq < WORKSTATION_RECLAIM_DISTANCE * WORKSTATION_RECLAIM_DISTANCE) {
            return Step.ready();
        }
        return startReclaimWorkstation(temporaryWorkstations.get(temporaryWorkstations.size() - 1));
    }

    private Step startReclaimWorkstation(TemporaryWorkstation workstation) {
        if (ctx.world().getBlockState(workstation.pos).getBlock() != workstation.block) {
            temporaryWorkstations.remove(workstation);
            return Step.ready();
        }

        Step tool = ensureMiningTool(workstation.item, List.of(workstation.block), new HashSet<>());
        if (tool.type != StepType.READY) {
            return tool;
        }

        if (closeOpenGui("Closing GUI", "Reclaiming " + shortName(workstation.item))) {
            return Step.pause();
        }

        reclaimingWorkstation = workstation;
        reclaimingDesired = count(workstation.item) + 1;
        subAction = SubAction.RECLAIMING_WORKSTATION;
        baritone.getMineProcess().mine(reclaimingDesired, workstation.block);
        status = "Reclaiming";
        focus = shortName(workstation.item) + " at " + formatPos(workstation.pos);
        getDebug("reclaim workstation " + shortName(workstation.item) + " at " + formatPos(workstation.pos));
        return Step.defer();
    }

    private Step ensureReclaimingWorkstation() {
        if (reclaimingWorkstation == null) {
            clearSubAction();
            return Step.ready();
        }
        status = "Reclaiming";
        focus = shortName(reclaimingWorkstation.item) + " at " + formatPos(reclaimingWorkstation.pos);
        if (baritone.getMineProcess().isActive()) {
            return Step.defer();
        }
        boolean stillThere = ctx.world().getBlockState(reclaimingWorkstation.pos).getBlock() == reclaimingWorkstation.block;
        boolean pickedUp = count(reclaimingWorkstation.item) >= reclaimingDesired;
        if (!stillThere || pickedUp) {
            temporaryWorkstations.remove(reclaimingWorkstation);
            getDebug("reclaimed workstation " + shortName(reclaimingWorkstation.item) + " pickedUp=" + pickedUp);
        } else {
            getDebug("reclaim stopped before " + shortName(reclaimingWorkstation.item) + " was collected");
        }
        clearSubAction();
        reclaimingWorkstation = null;
        reclaimingDesired = 0;
        return Step.pause();
    }

    private void recordTemporaryWorkstation(Block block, Item item, BlockPos pos) {
        for (TemporaryWorkstation workstation : temporaryWorkstations) {
            if (workstation.pos.equals(pos) && workstation.block == block) {
                return;
            }
        }
        temporaryWorkstations.add(new TemporaryWorkstation(block, item, pos.immutable()));
        getDebug("remember workstation " + shortName(item) + " at " + formatPos(pos));
    }

    private void pruneTemporaryWorkstations() {
        temporaryWorkstations.removeIf(workstation -> ctx.world().getBlockState(workstation.pos).getBlock() != workstation.block);
    }

    private GatherNeed nearestGatherNeed(Map<Item, Integer> needs) {
        GatherNeed best = null;
        for (Map.Entry<Item, Integer> entry : needs.entrySet()) {
            Item item = entry.getKey();
            int missing = entry.getValue();
            if (item == null || missing <= 0) {
                continue;
            }
            boolean canPickup = hasInventorySpaceFor(item) && matchingDroppedItemCount(item) > 0;
            boolean canMine = !mineSources(item).isEmpty();
            if (!canPickup && !canMine) {
                continue;
            }

            GatherNeed candidate = new GatherNeed(item, count(item) + missing, nearestGatherSourceDistanceSq(item));
            if (best == null || compareGatherNeed(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private int compareGatherNeed(GatherNeed first, GatherNeed second) {
        int distance = Double.compare(first.distanceSq, second.distanceSq);
        if (distance != 0) {
            return distance;
        }
        ResourceLocation firstId = BuiltInRegistries.ITEM.getKey(first.item);
        ResourceLocation secondId = BuiltInRegistries.ITEM.getKey(second.item);
        String firstName = firstId == null ? String.valueOf(first.item) : firstId.toString();
        String secondName = secondId == null ? String.valueOf(second.item) : secondId.toString();
        return firstName.compareTo(secondName);
    }

    private double nearestGatherSourceDistanceSq(Item item) {
        double dropped = nearestDroppedItemDistanceSq(item);
        double mined = nearestMineSourceDistanceSq(item);
        return Math.min(dropped, mined);
    }

    private double nearestDroppedItemDistanceSq(Item item) {
        if (!hasInventorySpaceFor(item)) {
            return UNKNOWN_SOURCE_DISTANCE;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        return ctx.entitiesStream()
                .filter(entity -> entity instanceof ItemEntity)
                .filter(Entity::isAlive)
                .filter(entity -> maxDist == 0 || entity.distanceToSqr(ctx.player()) <= maxDist * maxDist)
                .map(entity -> (ItemEntity) entity)
                .filter(entity -> entity.getItem().is(item))
                .mapToDouble(entity -> entity.distanceToSqr(ctx.player()))
                .min()
                .orElse(UNKNOWN_SOURCE_DISTANCE);
    }

    private double nearestMineSourceDistanceSq(Item item) {
        List<Block> sources = mineSources(item);
        if (sources.isEmpty()) {
            return UNKNOWN_SOURCE_DISTANCE;
        }
        try {
            List<BlockPos> found = MineProcess.searchWorld(
                    new CalculationContext(baritone),
                    new BlockOptionalMetaLookup(sources),
                    1,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>()
            );
            if (!found.isEmpty()) {
                return ctx.playerFeet().distSqr(found.get(0));
            }
        } catch (RuntimeException ignored) {
            // Distance is only a scheduling hint. Mining itself will still do the authoritative search.
        }
        return UNKNOWN_SOURCE_DISTANCE;
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
            if (!mineSources(item).isEmpty()) {
                planMiningTool(item, gather, available, visiting);
                gather.merge(item, remaining, Integer::sum);
                return;
            }
            Optional<SmeltingRecipe> smelting = findBestSmeltingRecipe(item, visiting);
            if (smelting.isPresent()) {
                planWorkstation(Blocks.FURNACE, Items.FURNACE, gather, available, visiting);
                Item input = firstSmeltInput(smelting.get());
                if (input != null) {
                    expand(input, remaining, gather, available, visiting);
                }
                Item fuel = chooseFuel(visiting, remaining);
                if (fuel != null) {
                    expand(fuel, fuelItemsNeeded(fuel, remaining), gather, available, visiting);
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
        if (isWorkstationOpen(block) || knownTemporaryWorkstation(block).isPresent() || findNearbyWorkstation(block).isPresent()) {
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

        AbstractContainerMenu menu = activeMenu();
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

        AbstractContainerMenu menu = activeMenu();
        if (!(menu instanceof FurnaceMenu furnaceMenu)) {
            if (smeltAction.loaded) {
                if (smeltAction.waitTicks-- > 0) {
                    status = "Smelting";
                    focus = shortName(smeltAction.item) + " cooking";
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                Step furnace = ensureWorkstation(Blocks.FURNACE, Items.FURNACE, new HashSet<>());
                return commandForStep(furnace);
            }
            // The furnace GUI closed unexpectedly before anything was loaded; let planning restart the smelt.
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
        if (!menu.getSlot(1).hasItem() && count(smeltAction.fuel) > 0 && moveToFurnaceSlot(menu, smeltAction.fuel, 1)) {
            getDebug("furnace fuel slot <- " + shortName(smeltAction.fuel));
            loaded = true;
        }
        if (!menu.getSlot(0).hasItem() && count(smeltAction.input) > 0 && moveToFurnaceSlot(menu, smeltAction.input, 0)) {
            getDebug("furnace input slot <- " + shortName(smeltAction.input));
            loaded = true;
        }
        if (loaded) {
            smeltAction.cooldown = 2;
            smeltAction.attempts = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (menu.getSlot(0).hasItem() && isFurnaceCooking(furnaceMenu)) {
            smeltAction.loaded = true;
            smeltAction.waitTicks = Math.max(SMELT_REOPEN_BUFFER_TICKS, smeltAction.remainingSmelts() * 200 + SMELT_REOPEN_BUFFER_TICKS);
            closeOpenGui("Smelting", shortName(smeltAction.item) + " cooking");
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

    private boolean isFurnaceCooking(FurnaceMenu menu) {
        return menu.isLit() || menu.getSlot(1).hasItem();
    }

    private boolean moveToFurnaceSlot(AbstractContainerMenu menu, Item item, int targetSlot) {
        ItemStack moving = new ItemStack(item);
        if (!menu.getCarried().isEmpty() || menu.getSlot(targetSlot).hasItem() || !menu.getSlot(targetSlot).mayPlace(moving)) {
            getDebug("furnace slot " + targetSlot + " rejected " + shortName(item));
            return false;
        }

        // Furnace slots 0 (input), 1 (fuel) and 2 (result) precede the player inventory slots.
        for (int i = 3; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(item)) {
                ctx.playerController().windowClick(menu.containerId, i, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(menu.containerId, targetSlot, 0, ClickType.PICKUP, ctx.player());
                if (!menu.getCarried().isEmpty()) {
                    ctx.playerController().windowClick(menu.containerId, i, 0, ClickType.PICKUP, ctx.player());
                }
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

        Item fuel = chooseFuel(visiting, needed);
        if (fuel == null) {
            return Step.unsupported("no usable fuel is available for smelting");
        }
        int fuelNeeded = fuelItemsNeeded(fuel, needed);
        getDebug("smelt plan " + shortName(item) + " need " + needed + " input " + shortName(input) + " fuel " + shortName(fuel) + " x" + fuelNeeded);
        Step fuelStep = ensureItem(fuel, fuelNeeded, visiting);
        if (fuelStep.type != StepType.READY) {
            return fuelStep;
        }

        Step furnace = ensureWorkstation(Blocks.FURNACE, Items.FURNACE, visiting);
        if (furnace.type != StepType.READY) {
            return furnace;
        }

        smeltAction = new SmeltAction(item, input, fuel, desired, count(item));
        status = "Smelting queued";
        focus = shortName(item) + " from " + shortName(input);
        return Step.pause();
    }

    private Item chooseFuel(Set<Item> visiting, int smeltsNeeded) {
        int inventoryHash = inventoryFuelHash();
        BlockPos origin = ctx.playerFeet();
        if (cachedFuelPlan != null && cachedFuelPlan.matches(smeltsNeeded, inventoryHash, origin)) {
            return cachedFuelPlan.item;
        }

        Map<Item, Integer> fuels = AbstractFurnaceBlockEntity.getFuel();
        List<FuelCandidate> candidates = new ArrayList<>();
        Set<Item> checked = new HashSet<>();

        if (fuels.containsKey(Items.COAL) && checked.add(Items.COAL)) {
            addCoalFuelCandidate(candidates, Items.COAL, smeltsNeeded, visiting);
        }
        if ((count(Items.CHARCOAL) > 0 || matchingDroppedItemCount(Items.CHARCOAL) > 0) && fuels.containsKey(Items.CHARCOAL) && checked.add(Items.CHARCOAL)) {
            addHeldFuelCandidate(candidates, Items.CHARCOAL, smeltsNeeded, visiting, 0);
        }

        for (WoodOption option : woodOptions()) {
            if (fuels.containsKey(option.planks) && checked.add(option.planks)) {
                addPlankFuelCandidate(candidates, option.planks, smeltsNeeded, visiting);
            }
        }

        if (candidates.isEmpty()) {
            for (Item fuel : fuels.keySet()) {
                if (isReasonableFuel(fuel) && checked.add(fuel)) {
                    addHeldFuelCandidate(candidates, fuel, smeltsNeeded, visiting, 8);
                }
            }
        }

        Optional<FuelCandidate> best = candidates.stream()
                .min(Comparator.comparingDouble(FuelCandidate::score).thenComparing(candidate -> name(candidate.item)));
        getDebug("fuel candidates " + summarizeFuelCandidates(candidates) + " -> " + best.map(candidate -> shortName(candidate.item)).orElse("none"));
        Item item = best.map(candidate -> candidate.item).orElse(null);
        cachedFuelPlan = new FuelPlan(item, smeltsNeeded, inventoryHash, origin);
        return item;
    }

    private int inventoryFuelHash() {
        int hash = 1;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty()) {
                hash = 31 * hash + BuiltInRegistries.ITEM.getId(stack.getItem());
                hash = 31 * hash + stack.getCount();
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (!stack.isEmpty()) {
                hash = 31 * hash + BuiltInRegistries.ITEM.getId(stack.getItem());
                hash = 31 * hash + stack.getCount();
            }
        }
        return hash;
    }

    private void addCoalFuelCandidate(List<FuelCandidate> candidates, Item fuel, int smeltsNeeded, Set<Item> visiting) {
        int held = count(fuel);
        double distance = nearestGatherSourceDistanceSq(fuel);
        if (held <= 0 && matchingDroppedItemCount(fuel) <= 0 && distance == UNKNOWN_SOURCE_DISTANCE) {
            return;
        }
        addFuelCandidate(candidates, fuel, smeltsNeeded, visiting, held, distance, held > 0 ? 0 : 3, true);
    }

    private void addHeldFuelCandidate(List<FuelCandidate> candidates, Item fuel, int smeltsNeeded, Set<Item> visiting, int priority) {
        addFuelCandidate(candidates, fuel, smeltsNeeded, visiting, count(fuel), nearestFuelSourceDistanceSq(fuel), priority, false);
    }

    private void addPlankFuelCandidate(List<FuelCandidate> candidates, Item planks, int smeltsNeeded, Set<Item> visiting) {
        int needed = fuelItemsNeeded(planks, smeltsNeeded);
        int heldPlanks = count(planks);
        int heldCraftablePlanks = heldPlanks + craftablePlanksFromHeldLogs(planks);
        int priority = heldPlanks >= needed ? 1 : heldCraftablePlanks >= needed ? 2 : 4;
        double distance = heldCraftablePlanks >= needed ? 0 : nearestPlankFuelSourceDistanceSq(planks);
        addFuelCandidate(candidates, planks, smeltsNeeded, visiting, heldCraftablePlanks, distance, priority, true);
    }

    private void addFuelCandidate(List<FuelCandidate> candidates, Item fuel, int smeltsNeeded, Set<Item> visiting, int effectiveHeld, double distanceSq, int priority, boolean allowObtain) {
        int needed = fuelItemsNeeded(fuel, smeltsNeeded);
        if (needed <= 0) {
            return;
        }
        if (effectiveHeld <= 0
                && matchingDroppedItemCount(fuel) <= 0
                && distanceSq == UNKNOWN_SOURCE_DISTANCE
                && (!allowObtain || !canObtainFuel(fuel, visiting))) {
            return;
        }
        candidates.add(new FuelCandidate(fuel, needed, effectiveHeld, distanceSq, priority));
    }

    private boolean canObtainFuel(Item fuel, Set<Item> visiting) {
        // Don't plan to make charcoal as fuel; using the wood directly as fuel is simpler and faster.
        return fuel != Items.CHARCOAL && canObtain(fuel, visiting);
    }

    private int fuelItemsNeeded(Item fuel, int smeltsNeeded) {
        int burnTicks = fuelBurnTicks(fuel);
        if (burnTicks <= 0) {
            return 0;
        }
        return Math.max(1, (smeltsNeeded * 200 + burnTicks - 1) / burnTicks);
    }

    private int fuelBurnTicks(Item fuel) {
        return AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel, 0);
    }

    private double nearestFuelSourceDistanceSq(Item fuel) {
        double ownItem = nearestGatherSourceDistanceSq(fuel);
        double wood = nearestWoodFuelDistanceSq(fuel);
        return Math.min(ownItem, wood);
    }

    private int craftablePlanksFromHeldLogs(Item planks) {
        int total = 0;
        for (WoodOption option : woodOptions()) {
            if (option.planks == planks) {
                total += count(option.logItem) * 4;
            }
        }
        return total;
    }

    private double nearestPlankFuelSourceDistanceSq(Item planks) {
        double best = nearestGatherSourceDistanceSq(planks);
        for (WoodOption option : woodOptions()) {
            if (option.planks == planks) {
                best = Math.min(best, nearestBlockDistanceSq(option.logBlock, 24, 12));
            }
        }
        return best;
    }

    private double nearestWoodFuelDistanceSq(Item fuel) {
        double best = UNKNOWN_SOURCE_DISTANCE;
        for (WoodOption option : woodOptions()) {
            if (option.logItem != fuel && option.planks != fuel) {
                continue;
            }
            double distance = nearestBlockDistanceSq(option.logBlock, 24, 12);
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    private double nearestBlockDistanceSq(Block block, int horizontalRange, int verticalRange) {
        BlockPos feet = ctx.playerFeet();
        double best = UNKNOWN_SOURCE_DISTANCE;
        for (int dx = -horizontalRange; dx <= horizontalRange; dx++) {
            for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                for (int dz = -horizontalRange; dz <= horizontalRange; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (ctx.world().getBlockState(pos).getBlock() == block) {
                        best = Math.min(best, feet.distSqr(pos));
                    }
                }
            }
        }
        return best;
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
        List<Block> result = new ArrayList<>();
        if (item == Items.COBBLESTONE) {
            result.add(Blocks.STONE);
        }
        Block direct = Block.byItem(item);
        if (direct != Blocks.AIR && !skipMineSource(item, direct) && blockDropsItem(direct, item)) {
            result.add(direct);
        }
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block == Blocks.AIR || result.contains(block) || skipMineSource(item, block)) {
                return;
            }
            if (blockDropsItem(block, item)) {
                result.add(block);
            }
        });
        return result;
    }

    private boolean blockDropsItem(Block block, Item item) {
        try {
            return new BlockOptionalMeta(block).matches(new ItemStack(item));
        } catch (RuntimeException ignored) {
            // Some modded loot tables can be unavailable client-side; skip them instead of failing #get.
            return false;
        }
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
        if (closeOpenGui("Closing GUI", "Preparing " + shortName(item))) {
            return Step.pause();
        }
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
        if (closeOpenGui("Closing GUI", "Preparing " + shortName(item))) {
            return Step.pause();
        }
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
        Optional<BlockPos> known = knownTemporaryWorkstation(block);
        if (known.isPresent()) {
            return known;
        }
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

    private Optional<BlockPos> knownTemporaryWorkstation(Block block) {
        pruneTemporaryWorkstations();
        BlockPos feet = ctx.playerFeet();
        Optional<BlockPos> active = Optional.empty();
        if (workstationBlock == block && workstationPos != null && ctx.world().getBlockState(workstationPos).getBlock() == block) {
            active = Optional.of(workstationPos);
        }
        Optional<BlockPos> remembered = temporaryWorkstations.stream()
                .filter(workstation -> workstation.block == block)
                .map(workstation -> workstation.pos)
                .min(Comparator.comparingDouble(feet::distSqr));
        return active.isPresent() && (remembered.isEmpty() || feet.distSqr(active.get()) <= feet.distSqr(remembered.get())) ? active : remembered;
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

    private void tickGetDebug() {
        getDebugTick++;
        getDebugCooldowns.entrySet().removeIf(entry -> getDebugTick - entry.getValue() > 200);
    }

    private void clearSubAction() {
        if (subAction == SubAction.PICKING_UP) {
            baritone.getFollowProcess().cancel();
        }
        if (subAction == SubAction.RECLAIMING_WORKSTATION) {
            baritone.getMineProcess().cancel();
        }
        subAction = SubAction.NONE;
        miningItem = null;
        miningDesired = 0;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        reclaimingWorkstation = null;
        reclaimingDesired = 0;
    }

    private void activateTarget(GetTarget next) {
        this.target = next.item;
        this.quantity = next.quantity;
        this.craftAction = null;
        this.smeltAction = null;
        this.subAction = SubAction.NONE;
        this.miningItem = null;
        this.miningDesired = 0;
        this.pickupItem = null;
        this.pickupDesired = 0;
        this.pickupTicks = 0;
        this.workstationBlock = null;
        this.workstationPos = null;
        this.workstationOpenAttempts = 0;
        this.reclaimingWorkstation = null;
        this.reclaimingDesired = 0;
        this.temporaryWorkstations.clear();
        this.cachedFuelPlan = null;
        this.getDebugCooldowns.clear();
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
        miningDesired = 0;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        workstationBlock = null;
        workstationPos = null;
        workstationOpenAttempts = 0;
        reclaimingWorkstation = null;
        reclaimingDesired = 0;
        temporaryWorkstations.clear();
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
        miningDesired = 0;
        pickupItem = null;
        pickupDesired = 0;
        pickupTicks = 0;
        workstationBlock = null;
        workstationPos = null;
        workstationOpenAttempts = 0;
        reclaimingWorkstation = null;
        reclaimingDesired = 0;
        temporaryWorkstations.clear();
        cachedFuelPlan = null;
        getDebugCooldowns.clear();
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
                .map(process -> "Active: " + compactActiveProcessName(process.displayName()))
                .ifPresent(lines::add);
        return lines;
    }

    private static String compactActiveProcessName(String displayName) {
        if (!displayName.startsWith("Mine BlockOptionalMetaLookup{")) {
            return displayName;
        }
        List<String> blocks = new ArrayList<>();
        int index = 0;
        while ((index = displayName.indexOf("block=Block{minecraft:", index)) != -1) {
            index += "block=Block{minecraft:".length();
            int end = displayName.indexOf('}', index);
            if (end == -1) {
                break;
            }
            blocks.add(displayName.substring(index, end));
            index = end + 1;
        }
        if (blocks.isEmpty()) {
            return "Mine";
        }
        if (blocks.size() == 1) {
            return "Mine " + blocks.get(0);
        }
        return "Mine " + blocks.get(0) + " +" + (blocks.size() - 1);
    }

    private void getDebug(String message) {
        if (!Baritone.settings().getDebug.value) {
            return;
        }
        String line = "[GetDebug] " + message;
        Integer lastTick = getDebugCooldowns.get(line);
        if (lastTick != null && getDebugTick - lastTick < 40) {
            return;
        }
        getDebugCooldowns.put(line, getDebugTick);
        logDirect(line);
    }

    private static String name(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.toString();
    }

    private static String shortName(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.getPath();
    }

    private static String summarizeItems(Map<Item, Integer> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        List<String> parts = new ArrayList<>();
        items.forEach((item, count) -> parts.add(count + "x " + shortName(item)));
        return String.join(", ", parts);
    }

    private static String summarizeFuelCandidates(List<FuelCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        candidates.stream()
                .sorted(Comparator.comparingDouble(FuelCandidate::score).thenComparing(candidate -> name(candidate.item)))
                .limit(8)
                .forEach(candidate -> parts.add(shortName(candidate.item) + " tier " + candidate.priority + " need " + candidate.needed + " held " + candidate.held + " dist " + formatDistanceSq(candidate.distanceSq)));
        if (candidates.size() > 8) {
            parts.add("+" + (candidates.size() - 8) + " more");
        }
        return String.join("; ", parts);
    }

    private static String formatDistanceSq(double distanceSq) {
        if (distanceSq == UNKNOWN_SOURCE_DISTANCE) {
            return "unknown";
        }
        return String.format("%.1f", Math.sqrt(distanceSq));
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
        RECLAIMING_WORKSTATION,
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
        private final int plannedStartCount;
        private int cooldown;
        private int attempts;
        private boolean loaded;
        private int waitTicks;

        private SmeltAction(Item item, Item input, Item fuel, int desired, int plannedStartCount) {
            this.item = item;
            this.input = input;
            this.fuel = fuel;
            this.desired = desired;
            this.plannedStartCount = plannedStartCount;
        }

        private int remainingSmelts() {
            return Math.max(1, desired - plannedStartCount);
        }
    }

    private static final class FuelCandidate {
        private final Item item;
        private final int needed;
        private final int held;
        private final double distanceSq;
        private final int priority;

        private FuelCandidate(Item item, int needed, int held, double distanceSq, int priority) {
            this.item = item;
            this.needed = needed;
            this.held = held;
            this.distanceSq = distanceSq;
            this.priority = priority;
        }

        private double score() {
            double source = distanceSq == UNKNOWN_SOURCE_DISTANCE ? 1_000_000 : distanceSq;
            int shortage = Math.max(0, needed - held);
            return priority * 1_000_000D + source + shortage * 64D + needed * 8D - Math.min(held, needed) * 8D + preferredFuelBonus();
        }

        private int preferredFuelBonus() {
            return item == Items.COAL || item == Items.CHARCOAL ? -64 : 0;
        }
    }

    private static final class FuelPlan {
        private static final int MAX_REUSE_DISTANCE_SQ = 16 * 16;

        private final Item item;
        private final int smeltsNeeded;
        private final int inventoryHash;
        private final BlockPos origin;

        private FuelPlan(Item item, int smeltsNeeded, int inventoryHash, BlockPos origin) {
            this.item = item;
            this.smeltsNeeded = smeltsNeeded;
            this.inventoryHash = inventoryHash;
            this.origin = origin;
        }

        private boolean matches(int smeltsNeeded, int inventoryHash, BlockPos current) {
            return this.smeltsNeeded == smeltsNeeded
                    && this.inventoryHash == inventoryHash
                    && this.origin.distSqr(current) <= MAX_REUSE_DISTANCE_SQ;
        }
    }

    private static final class TemporaryWorkstation {
        private final Block block;
        private final Item item;
        private final BlockPos pos;

        private TemporaryWorkstation(Block block, Item item, BlockPos pos) {
            this.block = block;
            this.item = item;
            this.pos = pos;
        }
    }

    private static final class GatherNeed {
        private final Item item;
        private final int desiredTotal;
        private final double distanceSq;

        private GatherNeed(Item item, int desiredTotal, double distanceSq) {
            this.item = item;
            this.desiredTotal = desiredTotal;
            this.distanceSq = distanceSq;
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
