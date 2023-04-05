package com.simibubi.create.compat.rei;

import java.util.function.Supplier;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.element.GuiGameElement;

import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import net.minecraft.world.item.ItemStack;

public class ItemIcon implements Renderer {

	private Supplier<ItemStack> supplier;
	private ItemStack stack;
	private int z;

	public ItemIcon(Supplier<ItemStack> stack) {
		this.supplier = stack;
	}

	@Override
	public void render(PoseStack matrixStack, Rectangle bounds, int mouseX, int mouseY, float delta) {
		if (stack == null) {
			stack = supplier.get();
		}

		int xOffset = bounds.x;
		int yOffset = bounds.y;

		RenderSystem.enableDepthTest();
		matrixStack.pushPose();
		matrixStack.translate(xOffset, yOffset, 0);

		GuiGameElement.of(stack)
				.render(matrixStack);

		matrixStack.popPose();
	}

	@Override
	public int getZ() {
		return z;
	}

	@Override
	public void setZ(int z) {
		this.z = z;
	}
}
