package cy.jdkdigital.productivemetalworks.common.block.entity;

import cy.jdkdigital.productivelib.common.block.entity.FluidTankBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.IMultiBlockControllerBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import cy.jdkdigital.productivelib.exception.InvalidStructureException;
import cy.jdkdigital.productivelib.registry.LibItems;
import cy.jdkdigital.productivelib.util.MultiBlockDetector;
import cy.jdkdigital.productivelib.util.MultiFluidTank;
import cy.jdkdigital.productivemetalworks.Config;
import cy.jdkdigital.productivemetalworks.common.block.FoundryControllerBlock;
import cy.jdkdigital.productivemetalworks.common.datamap.EnergyCoilMap;
import cy.jdkdigital.productivemetalworks.common.menu.FoundryControllerContainer;
import cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe;
import cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe;
import cy.jdkdigital.productivemetalworks.registry.MetalworksRegistrator;
import cy.jdkdigital.productivemetalworks.registry.ModTags;
import cy.jdkdigital.productivemetalworks.util.RecipeHelper;
import cy.jdkdigital.productivemetalworks.util.TickingSlotInventoryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import oshi.util.tuples.Pair;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


public class FoundryControllerBlockEntity extends FluidTankBlockEntity implements IUpgradeableBlockEntity, IMultiBlockControllerBlockEntity, MenuProvider
{
    private MultiBlockDetector.MultiBlockData foundryData;
    private int tickCounter = 0;
    private float leftoverTick = 0;
    private CoilType coilType = CoilType.INVALID;

    public enum CoilType {
        INVALID,
        LIQUID,
        ENERGY
    }

    // used clientside for rendering fuel in gui screen
    public FluidStack fuel = FluidStack.EMPTY;

    public MultiFluidTank fluidHandler = new MultiFluidTank(20, 90000) {
        @Override
        protected void onContentsChanged(boolean hasChangedFluid) {
            super.onContentsChanged(hasChangedFluid);
            if (hasChangedFluid && FoundryControllerBlockEntity.this.level instanceof ServerLevel serverLevel) {
                FoundryControllerBlockEntity.this.sync(serverLevel);
            }
            FoundryControllerBlockEntity.this.setChanged();
        }
    };

    protected TickingSlotInventoryHandler itemHandler = new TickingSlotInventoryHandler(200, this) {
        @Override
        protected int getTimeInSlot(ItemStack stack) {
            if (this.blockEntity != null && blockEntity.getLevel() instanceof Level pLevel) {
                var fuelData = FoundryControllerBlockEntity.this.getFuel().getFluidHolder().getData(MetalworksRegistrator.FUEL_MAP);
                RecipeHolder<ItemMeltingRecipe> recipe = RecipeHelper.getItemMeltingRecipe(pLevel, stack, fuelData);
                if (recipe != null) {
                    return recipe.value().result.stream().map(FluidStack::getAmount).reduce(Integer::sum).orElse(0);
                }
            }
            return 0;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isInputSlotItem(int slot, ItemStack item) {
            return true;
        }

        @Override
        public boolean isInputSlot(int slot) {
            return true;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return true;
        }

        @Override
        public int[] getOutputSlots() {
            return new int[]{};
        }

        @Override
        protected void onContentsChanged(int slot) {
            if (FoundryControllerBlockEntity.this.level instanceof ServerLevel serverLevel) {
                FoundryControllerBlockEntity.this.sync(serverLevel);
            }
        }
    };

    protected IItemHandlerModifiable upgradeHandler = new InventoryHandlerHelper.UpgradeHandler(4, this, List.of(
            LibItems.UPGRADE_TIME.get(),
            LibItems.UPGRADE_TIME_2.get(),
            LibItems.UPGRADE_STABILITY.get()
    ));

    public FoundryControllerBlockEntity(BlockPos pos, BlockState state) {
        super(MetalworksRegistrator.FOUNDRY_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public IItemHandlerModifiable getUpgradeHandler() {
        return upgradeHandler;
    }

    protected void burnItemsAtSpeed(float burnSpeed) {
        int burnTicks = Math.round(burnSpeed + this.leftoverTick);
        this.leftoverTick = burnSpeed + this.leftoverTick - burnTicks;
        this.itemHandler.tick(burnTicks);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FoundryControllerBlockEntity blockEntity) {
        if (blockEntity.getMultiblockData() != null) {
            var mb = blockEntity.getMultiblockData();

            // Process Entity detection
            FluidTankBlockEntity.tick(level, pos, state, blockEntity);
            if (blockEntity.tickCounter%20 == 0 && (Config.foundryCollectItems || Config.foundryDamageEntities)) {
                var c1 = mb.topCorners().getFirst().below(mb.height() - 1);
                var c2 = mb.topCorners().getSecond().below(mb.height() - 2);

                level.getEntities(null, new AABB(c1.getX(), c1.getY(), c1.getZ(), c2.getX(), c2.getY(), c2.getZ())).forEach(entity -> {
                    // Item entities can be picked up when there's room in the item handler
                    if (Config.foundryCollectItems) {
                        if (entity instanceof ItemEntity item) {
                            var groundStack = item.getItem();
                            for (int slot = 0; slot < blockEntity.itemHandler.getSlots(); slot++) {
                                if (blockEntity.itemHandler.getItem(slot).isEmpty()) {
                                    var clonedStack = groundStack.copy();
                                    clonedStack.setCount(1);
                                    blockEntity.itemHandler.setStackInSlot(slot, clonedStack);
                                    groundStack.shrink(1);
                                }
                            }
                        }
                    }
                    if (Config.foundryDamageEntities) {
                        // Other entities take damage if there's heat
                        if (entity instanceof LivingEntity livingEntity && (blockEntity.getFluidHandler().totalFluidAmount() > 0 || blockEntity.getFuel().getAmount() > 0)) {
                            livingEntity.hurt(level.damageSources().hotFloor(), 2.0f);
                            var meltingFluid = livingEntity.getType().builtInRegistryHolder().getData(MetalworksRegistrator.ENTITY_MELTING_MAP);
                            if (meltingFluid != null) {
                                blockEntity.fluidHandler.fill(meltingFluid.fluid(), IFluidHandler.FluidAction.EXECUTE);
                            }
                        }
                    }
                });
            }

           

            // TODO: identify coil type


            //TODO: StreamSupport.stream(blockEntity.getCoils(mb).spliterator(), false).map(i -> ).findAny(CoilType.INVALID);


            //FoundryControllerBlockEntity::GetCoilType


            IMelterProcessor mp;
            switch (blockEntity.coilType){
                case CoilType.INVALID -> {

                }
                case CoilType.LIQUID -> {
                    mp = new LiquidMelter();
                    mp.Tick(level, pos, state, blockEntity);
                }
                case CoilType.ENERGY -> {
                    mp = new EnergyMelter();
                    mp.Tick(level, pos, state, blockEntity);
                }
            }
        }

        // Increment and rollover tick counter
        blockEntity.tickCounter = ++blockEntity.tickCounter % 200;

        // Every 10 seconds update multiblocks

        if (++blockEntity.tickCounter%200 == 0) {// TODO scaling number based on failures
            blockEntity.tickCounter = 0;
            try {
                blockEntity.setMultiBlockData(FoundryControllerBlock.detectMultiblock(level, pos));
            } catch (InvalidStructureException e) {
                blockEntity.setMultiBlockData(null);
            }
        }
    }

    public static void clientTick(Level level, BlockPos blockPos, BlockState blockState, FoundryControllerBlockEntity blockEntity) {
        var fuel = blockEntity.getFuel();
        if (!fuel.isEmpty()) {
            var fuelData = fuel.getFluid().builtInRegistryHolder().getData(MetalworksRegistrator.FUEL_MAP);
            if (fuelData != null) {
                float burnSpeed = fuelData.speed() * blockEntity.getSpeedModifier();
                int burnTicks = Math.round(burnSpeed + blockEntity.leftoverTick);
                blockEntity.leftoverTick = burnSpeed + blockEntity.leftoverTick - burnTicks;
                blockEntity.itemHandler.tick(burnTicks);
            }
        }
    }

    public int getSpeedModifier() {
        return 1 + this.getUpgradeCount(LibItems.UPGRADE_TIME.get()) + 2 * this.getUpgradeCount(LibItems.UPGRADE_TIME_2.get());
    }

    @Override
    public void tickFluidTank(Level level, BlockPos blockPos, BlockState blockState, FluidTankBlockEntity fluidTankBlockEntity) {
        // TODO refresh recipeProcessList every few seconds instead of each tick
        if (fluidTankBlockEntity instanceof FoundryControllerBlockEntity foundry && foundry.getUpgradeCount(LibItems.UPGRADE_STABILITY.get()) == 0) {
            List<RecipeHolder<FluidAlloyingRecipe>> recipeProcessList = RecipeHelper.getAlloyRecipes(level, fluidHandler);

            recipeProcessList.forEach(fluidAlloyingRecipe -> {
                int speed = fluidAlloyingRecipe.value().speed;
                boolean canDrainFullSpeed = fluidAlloyingRecipe.value().fluids.stream().map(f -> new SizedFluidIngredient(f.ingredient(), f.amount() * speed)).noneMatch(fluid -> fluidHandler.drain(fluid, IFluidHandler.FluidAction.SIMULATE).isEmpty());
                if (canDrainFullSpeed && fluidHandler.fill(new FluidStack(fluidAlloyingRecipe.value().result.getFluid(), fluidAlloyingRecipe.value().result.getAmount() * speed), IFluidHandler.FluidAction.SIMULATE) > 0) {
                    fluidAlloyingRecipe.value().fluids.forEach(fluid -> fluidHandler.drain(fluid, IFluidHandler.FluidAction.EXECUTE));
                    fluidHandler.fill(fluidAlloyingRecipe.value().result, IFluidHandler.FluidAction.EXECUTE);
                } else {
                    boolean canDrain = fluidAlloyingRecipe.value().fluids.stream().noneMatch(fluid -> fluidHandler.drain(fluid, IFluidHandler.FluidAction.SIMULATE).isEmpty());
                    if (canDrain && fluidHandler.fill(fluidAlloyingRecipe.value().result, IFluidHandler.FluidAction.SIMULATE) > 0) {
                        fluidAlloyingRecipe.value().fluids.forEach(fluid -> fluidHandler.drain(fluid, IFluidHandler.FluidAction.EXECUTE));
                        fluidHandler.fill(fluidAlloyingRecipe.value().result, IFluidHandler.FluidAction.EXECUTE);
                    }
                }
            });
        }
    }

    @Override
    public int tankTickRate() {
        return 1;
    }

    @Override
    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    @Override
    public MultiFluidTank getFluidHandler() {
        return fluidHandler;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {
        return new FoundryControllerContainer(i, inventory, this);
    }

    @Override
    public void savePacketNBT(CompoundTag tag, HolderLookup.Provider provider) {
        super.savePacketNBT(tag, provider);

        if (this.getMultiblockData() != null) {
            tag.put("multiData", this.getMultiblockData().serializeNBT(provider));
        }
    }

    @Override
    public void loadPacketNBT(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadPacketNBT(tag, provider);

        if (tag.contains("multiData")) {
            var data = new MultiBlockDetector.MultiBlockData(null, null, List.of(), 0, 0);
            data.deserializeNBT(provider, Objects.requireNonNull(tag.get("multiData")));
            setMultiBlockData(data);
        }
    }

    public FluidStack getFuel() {

        if (level == null) {
            return FluidStack.EMPTY;
        }

        var mb = getMultiblockData();
        if(mb == null) {
            return FluidStack.EMPTY;
        }

        FluidStack fuel = FluidStack.EMPTY;
        for (BlockPos pos: mb.peripherals()) {
            if (level.getBlockEntity(pos) instanceof FoundryTankBlockEntity tank) {
                var fluid = tank.getFluidHandler().getFluidInTank(0);
                if (!fluid.isEmpty()) {
                    if (fuel.isEmpty()) {
                        fuel = fluid.copy();
                    } else if (fuel.is(fluid.getFluid())) {
                        fuel.grow(fluid.getAmount());
                    }
                }
            }
        }
        return FluidStack.EMPTY;
    }

    void addRecipeResult(List<FluidStack> result) {
        for (FluidStack fluidStack : result) {
            if (this.fluidHandler.fill(fluidStack, IFluidHandler.FluidAction.SIMULATE) == fluidStack.getAmount()) {
                this.fluidHandler.fill(fluidStack, IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    @Override
    public void setMultiBlockData(MultiBlockDetector.MultiBlockData multiBlockData) {
        // Set tank size based on structure volume
        if (level instanceof ServerLevel serverLevel) {
            if (multiBlockData != null) {
                this.fluidHandler.setCapacity(multiBlockData.volume() * Config.foundryFluidCapacityPerBlockVolume);
                // if the inventory has shrunk and was full, then items are lost...TODO maybe handle that
                this.itemHandler.setSize(multiBlockData.volume());

                if (!getBlockState().getValue(BlockStateProperties.ATTACHED)) {
                    serverLevel.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(BlockStateProperties.ATTACHED, true));
                }

                // turn on heating coils
                var coilPos = getCoils(multiBlockData);
                //BlockState referenceCoil = serverLevel.getBlockState(coilPos.next());

                //blockEntity.coilType = CoilType.LIQUID;

                // TODO: Run coil type check and assignment

            } else if (getBlockState().getValue(BlockStateProperties.ATTACHED)) {
                serverLevel.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(BlockStateProperties.ATTACHED, false));
            }
        } else {
            // update client side
            this.fluidHandler.setCapacity(multiBlockData.volume() * Config.foundryFluidCapacityPerBlockVolume);
            this.itemHandler.setSize(multiBlockData.volume());
        }

        // sync if the multiblock is formed or has changed from/to formed
        if (level instanceof ServerLevel serverLevel && (this.foundryData != multiBlockData || multiBlockData != null)) {
            this.sync(serverLevel);
        }
        this.foundryData = multiBlockData;
        this.setChanged();
    }

    @Override
    public MultiBlockDetector.MultiBlockData getMultiblockData() {
        return this.foundryData;
    }

    private Iterable<BlockPos> getCoils(MultiBlockDetector.MultiBlockData multiBlockData) {
        var c1 = multiBlockData.topCorners().getFirst().below(multiBlockData.height());
        var c2 = multiBlockData.topCorners().getSecond().below(multiBlockData.height());

        int dx = c2.getX() - c1.getX();
        int dz = c2.getZ() - c1.getZ();

        return BlockPos.betweenClosed(
                new BlockPos(c1.getX() + Integer.signum(dx),c1.getY(),c1.getZ() + Integer.signum(dz)),
                new BlockPos(c2.getX() - Integer.signum(dx),c2.getY(),c2.getZ() - Integer.signum(dz))
        );
    }

    private CoilType compareValidCoil(BlockState state, BlockState reference) {
        if (!state.is(ModTags.Blocks.HEATING_COILS) || !reference.is(ModTags.Blocks.HEATING_COILS)){
            return CoilType.INVALID;
        }

        EnergyCoilMap coilECM = state.getBlock().builtInRegistryHolder().getData(MetalworksRegistrator.ENERGY_COIL_MAP);
        EnergyCoilMap coilRefECM = reference.getBlock().builtInRegistryHolder().getData(MetalworksRegistrator.ENERGY_COIL_MAP);

        if (coilECM != null && coilRefECM!= null) {
            if (coilECM.temperature() != coilRefECM.temperature()){
                return CoilType.INVALID;
            }
            if (coilECM.consumption() != coilRefECM.consumption()){
                return CoilType.INVALID;
            }
            if (coilECM.speed() != coilRefECM.speed()){
                return CoilType.INVALID;
            }
        }

        return CoilType.LIQUID;
    }

    private static CoilType GetCoilType(BlockState state){
        if (!state.is(ModTags.Blocks.HEATING_COILS)){
            return CoilType.INVALID;
        }

        EnergyCoilMap coilECM = state.getBlock().builtInRegistryHolder().getData(MetalworksRegistrator.ENERGY_COIL_MAP);
        if (coilECM != null) {
            return CoilType.ENERGY;
        }

        return CoilType.LIQUID;
    }

    private static Stream<CoilType> GetCoilTypes(Stream<BlockState> states){
        return states.map(FoundryControllerBlockEntity::GetCoilType).distinct();
    }

    private static void RecipeTick(double energyInput, double rate) {

    }

    public void sync(Level level) {
        // TODO move to lib and schedule this so it's not called multiple times in the same tick
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public void moveTankFirst(int tank) {
        if (tank > 0 && tank < this.fluidHandler.getTanks()) {
            this.fluidHandler.moveTankToTop(tank);
            this.sync(level);
        }
    }
}

interface IMelterProcessor {
    int GetFuelLevel();
    void Tick(Level level, BlockPos pos, BlockState state, FoundryControllerBlockEntity blockEntity);
}

class LiquidMelter implements IMelterProcessor {

    public int GetFuelLevel(){
        return 0;
    }

    public void Tick(Level level, BlockPos pos, BlockState state, FoundryControllerBlockEntity blockEntity) {
        var mb = blockEntity.getMultiblockData();

        // Melt items from inventory if they have a melting recipe
        FluidStack fuel = blockEntity.getFuel();
        if (fuel.isEmpty()){ return; }

        // Get Fuel information
        var fuelData = fuel.getFluid().builtInRegistryHolder().getData(MetalworksRegistrator.FUEL_MAP);
        if (fuelData == null) {return;}

        // Assign fuel information constants
        int speedModifier = blockEntity.getSpeedModifier();
        float burnSpeed = fuelData.speed() * speedModifier;

        // Update
        blockEntity.burnItemsAtSpeed(burnSpeed);

        // Assign flag to trigger action on update
        boolean hasChanged = false;

        // Create an accumulator to count how much fuel will be used
        FluidStack consumedFuel = new FluidStack(fuel.getFluid(), 0);

        // TODO: Convert fuel to an energy value
        for (int slot = 0; slot < blockEntity.itemHandler.size(); slot++)
        {
            // Get timer
            var ticker = blockEntity.itemHandler.getTicker(slot);
            if (ticker.getSecond() <= 0 || ticker.getFirst() > 0) { continue; }

            // Get Item in Slot
            var item = blockEntity.getItemHandler().getStackInSlot(slot);
            if (item.isEmpty()) { continue; }

            // Get Recipe for item in slot
            RecipeHolder<ItemMeltingRecipe> recipe = RecipeHelper.getItemMeltingRecipe(level, item, fuelData);
            if (recipe == null) { continue; }

            // Sum total fluid amount that would be melted
            int totalProducedFluid = recipe.value().result.stream().map(FluidStack::getAmount).reduce(Integer::sum).orElse(0);

            // Look at the fuel required for this recipe melt
            int requiredFuel = (int)(totalProducedFluid * fuelData.consumption() * speedModifier);

            // Do not proceed if fuel required would exceed total capacity
            if (requiredFuel + consumedFuel.getAmount() > fuel.getAmount()){ continue; }

            // Do not proceed if liquid melted would exceed the total forge capacity
            if (totalProducedFluid > blockEntity.fluidHandler.getCapacity() - blockEntity.fluidHandler.totalFluidAmount()){ continue; }

            // Actually consume the fuel and remove the item
            consumedFuel.grow(requiredFuel);
            blockEntity.itemHandler.extractItem(slot, 1, false, false);
            hasChanged = true;

            // Melt every result into the tank
            blockEntity.addRecipeResult(recipe.value().result);
        }

        if (hasChanged) {
            for (BlockPos blockPos : mb.peripherals()) {
                if (!consumedFuel.isEmpty() && level.getBlockEntity(blockPos) instanceof FoundryTankBlockEntity tankBlockEntity) {
                    var drainedFluid = tankBlockEntity.getFluidHandler().drain(consumedFuel, IFluidHandler.FluidAction.EXECUTE);
                    consumedFuel.shrink(drainedFluid.getAmount());
                }
            }
            blockEntity.sync(level);
        }
    }
}


class EnergyMelter implements IMelterProcessor {
    public int GetFuelLevel(){
        return 0;
    }

    public int ConsumeFuel(){
        return 0;
    }

    public void Tick(Level level, BlockPos pos, BlockState state, FoundryControllerBlockEntity blockEntity) {

    }
}