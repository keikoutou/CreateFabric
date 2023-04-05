package com.simibubi.create.content.contraptions.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nonnull;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.contraptions.processing.ProcessingRecipeBuilder.ProcessingRecipeParams;
import com.simibubi.create.content.contraptions.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import com.simibubi.create.foundation.item.SmartInventory;
import com.simibubi.create.foundation.tileEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.fluid.SmartFluidTankBehaviour.TankSegment;
import com.simibubi.create.foundation.utility.recipe.DummyCraftingContainer;
import com.simibubi.create.foundation.utility.recipe.IRecipeTypeInfo;

import io.github.fabricators_of_create.porting_lib.transfer.TransferUtil;
import io.github.fabricators_of_create.porting_lib.transfer.callbacks.TransactionCallback;
import io.github.fabricators_of_create.porting_lib.util.FluidStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

public class BasinRecipe extends ProcessingRecipe<SmartInventory> {

	public static boolean match(BasinTileEntity basin, Recipe<?> recipe) {
		FilteringBehaviour filter = basin.getFilter();
		if (filter == null)
			return false;

		boolean filterTest = filter.test(recipe.getResultItem());
		if (recipe instanceof BasinRecipe) {
			BasinRecipe basinRecipe = (BasinRecipe) recipe;
			if (basinRecipe.getRollableResults()
				.isEmpty()
				&& !basinRecipe.getFluidResults()
					.isEmpty())
				filterTest = filter.test(basinRecipe.getFluidResults()
					.get(0));
		}

		if (!filterTest)
			return false;

		return apply(basin, recipe, true);
	}

	public static boolean apply(BasinTileEntity basin, Recipe<?> recipe) {
		return apply(basin, recipe, false);
	}

	private static boolean apply(BasinTileEntity basin, Recipe<?> recipe, boolean test) {
		boolean isBasinRecipe = recipe instanceof BasinRecipe;
		Storage<ItemVariant> availableItems = basin.getItemStorage(null);
		Storage<FluidVariant> availableFluids = basin.getFluidStorage(null);

		if (availableItems == null || availableFluids == null)
			return false;

		HeatLevel heat = BasinTileEntity.getHeatLevelOf(basin.getLevel()
			.getBlockState(basin.getBlockPos()
				.below(1)));
		if (isBasinRecipe && !((BasinRecipe) recipe).getRequiredHeat()
			.testBlazeBurner(heat))
			return false;

		List<ItemStack> recipeOutputItems = new ArrayList<>();
		List<FluidStack> recipeOutputFluids = new ArrayList<>();

		List<Ingredient> ingredients = new LinkedList<>(recipe.getIngredients());
		List<FluidIngredient> fluidIngredients =
			isBasinRecipe ? ((BasinRecipe) recipe).getFluidIngredients() : Collections.emptyList();

		// fabric: track consumed items to get remainders later
		NonNullList<ItemStack> consumedItems = NonNullList.create();

		try (Transaction t = TransferUtil.getTransaction()) {
			Ingredients: for (Ingredient ingredient : ingredients) {
				for (StorageView<ItemVariant> view : TransferUtil.getNonEmpty(availableItems)) {
					ItemVariant var = view.getResource();
					ItemStack stack = var.toStack();
					if (!ingredient.test(stack)) continue;
					// Catalyst items are never consumed
					ItemStack remainder = stack.getRecipeRemainder();
					if (!remainder.isEmpty() && remainder.sameItem(stack))
						continue Ingredients;
					long extracted = view.extract(var, 1, t);
					if (extracted == 0) continue;
					consumedItems.add(stack);
					continue Ingredients;
				}
				// something wasn't found
				return false;
			}

			boolean fluidsAffected = false;
			FluidIngredients: for (FluidIngredient fluidIngredient : fluidIngredients) {
			long amountRequired = fluidIngredient.getRequiredAmount();
				for (StorageView<FluidVariant> view : TransferUtil.getNonEmpty(availableFluids)) {
					FluidStack fluidStack = new FluidStack(view);
					if (!fluidIngredient.test(fluidStack)) continue;
					long drainedAmount = Math.min(amountRequired, fluidStack.getAmount());
					if (view.extract(fluidStack.getType(), drainedAmount, t) == drainedAmount) {
						fluidsAffected = true;
						amountRequired -= drainedAmount;
						if (amountRequired != 0) continue;
						continue FluidIngredients;
					}
				}
				// something wasn't found
				return false;
			}

			if (fluidsAffected) {
				TransactionCallback.onSuccess(t, () -> {
					basin.getBehaviour(SmartFluidTankBehaviour.INPUT)
							.forEach(TankSegment::onFluidStackChanged);
					basin.getBehaviour(SmartFluidTankBehaviour.OUTPUT)
							.forEach(TankSegment::onFluidStackChanged);
				});
			}

			if (recipe instanceof BasinRecipe basinRecipe) {
				recipeOutputItems.addAll(basinRecipe.rollResults());
				recipeOutputFluids.addAll(basinRecipe.getFluidResults());
				recipeOutputItems.addAll(basinRecipe.getRemainingItems(basin.getInputInventory()));
			} else {
				recipeOutputItems.add(recipe.getResultItem());

				if (recipe instanceof CraftingRecipe craftingRecipe) {
					recipeOutputItems.addAll(craftingRecipe.getRemainingItems(new DummyCraftingContainer(consumedItems)));
				}
			}

			// fabric: bad
			recipeOutputItems.removeIf(ItemStack::isEmpty);

			if (!basin.acceptOutputs(recipeOutputItems, recipeOutputFluids, t))
				return false;

			if (!test)
				t.commit();
			return true;
		}
	}

	public static BasinRecipe convertShapeless(Recipe<?> recipe) {
		BasinRecipe basinRecipe =
			new ProcessingRecipeBuilder<>(BasinRecipe::new, recipe.getId()).withItemIngredients(recipe.getIngredients())
				.withSingleItemOutput(recipe.getResultItem())
				.build();
		return basinRecipe;
	}

	protected BasinRecipe(IRecipeTypeInfo type, ProcessingRecipeParams params) {
		super(type, params);
	}

	public BasinRecipe(ProcessingRecipeParams params) {
		this(AllRecipeTypes.BASIN, params);
	}

	@Override
	protected int getMaxInputCount() {
		return 9;
	}

	@Override
	protected int getMaxOutputCount() {
		return 4;
	}

	@Override
	protected int getMaxFluidInputCount() {
		return 2;
	}

	@Override
	protected int getMaxFluidOutputCount() {
		return 2;
	}

	@Override
	protected boolean canRequireHeat() {
		return true;
	}

	@Override
	public boolean matches(SmartInventory inv, @Nonnull Level worldIn) {
		return false;
	}

}
