package com.urahara.autofish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/**
 * AutoFish (Minecraft 26.3, Mojang names) - casts, watches the bobber, and
 * reels in automatically the moment a fish bites, then recasts.
 *
 * Casting/reeling is simulated with gameMode.useItem(...), which sends the
 * same "use item" packet a real right-click sends. Bite detection watches
 * the bobber's vertical position each tick for the sharp downward jerk
 * right before a catch.
 */
public class AutoFishClient implements ClientModInitializer {

	public static final String MOD_ID = "autofish";

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

	private static KeyMapping toggleKey;
	private static KeyMapping menuKey;

	private FishingHook trackedBobber = null;
	private double lastBobberY = 0;
	private int settleTicks = 0;
	private int recastCooldown = 0;

	@Override
	public void onInitializeClient() {
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.autofish.toggle", 74 /* J */, CATEGORY));
		menuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.autofish.menu", 75 /* K */, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(MOD_ID, "status"),
				(graphics, tickCounter) -> drawHud(graphics));
	}

	private void onTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			AutoFishConfig.enabled = !AutoFishConfig.enabled;
			trackedBobber = null;
			recastCooldown = 0;
		}

		while (menuKey.consumeClick()) {
			client.setScreenAndShow(new AutoFishScreen());
		}

		if (!AutoFishConfig.enabled || client.player == null || client.level == null
				|| client.gameMode == null) {
			return;
		}

		if (recastCooldown > 0) {
			recastCooldown--;
			return;
		}

		if (!isHoldingFishingRod(client)) {
			return;
		}

		if (trackedBobber == null || !trackedBobber.isAlive()) {
			trackedBobber = findOwnBobber(client);
			if (trackedBobber == null) {
				castLine(client);
			} else {
				settleTicks = 0;
				lastBobberY = trackedBobber.getY();
			}
			return;
		}

		double currentY = trackedBobber.getY();
		double deltaY = currentY - lastBobberY;
		lastBobberY = currentY;

		// While the bobber is still flying/falling after the cast, its Y drops
		// fast and would look like a bite. Only watch once it is in water.
		if (!trackedBobber.isInWater()) {
			settleTicks = 0;
			return;
		}
		settleTicks++;

		if (settleTicks > AutoFishConfig.minSettleTicks && deltaY < AutoFishConfig.biteThreshold) {
			useRod(client);
			trackedBobber = null;
			recastCooldown = AutoFishConfig.recastDelayTicks;
		}
	}

	private void drawHud(GuiGraphicsExtractor graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		String label = "AutoFish: " + (AutoFishConfig.enabled ? "ON" : "OFF");
		int color = AutoFishConfig.enabled ? 0xFF55FF55 : 0xFFAAAAAA;
		if (AutoFishConfig.enabled && trackedBobber != null) {
			label += " (waiting...)";
		}

		graphics.text(client.font, label, 6, 6, color, true);
	}

	private boolean isHoldingFishingRod(Minecraft client) {
		ItemStack main = client.player.getMainHandItem();
		ItemStack off = client.player.getOffhandItem();
		return main.getItem() instanceof FishingRodItem || off.getItem() instanceof FishingRodItem;
	}

	private FishingHook findOwnBobber(Minecraft client) {
		for (Entity e : client.level.entitiesForRendering()) {
			if (e instanceof FishingHook bobber && bobber.getOwner() == client.player) {
				return bobber;
			}
		}
		return null;
	}

	private void castLine(Minecraft client) {
		useRod(client);
		// give the server time to spawn the bobber; without this the next tick
		// sees no bobber yet and "casts" again, which reels the new cast straight back in
		recastCooldown = 20;
	}

	private void useRod(Minecraft client) {
		InteractionHand hand = client.player.getMainHandItem().getItem() instanceof FishingRodItem
				? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		client.gameMode.useItem(client.player, hand);
	}
}
