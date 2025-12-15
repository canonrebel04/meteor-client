/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

public class AutoTrader extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> tradeItem = sgGeneral.add(new ItemSetting.Builder()
        .name("trade-item")
        .description("The item you want to obtain.")
        .defaultValue(Items.EMERALD)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for traders.")
        .defaultValue(20.0)
        .min(0)
        .sliderMax(50)
        .build()
    );

    private Entity target;
    private int pathTimer = 0;

    public AutoTrader() {
        super(Categories.World, "auto-trader", "Automatically finds and trades with Villagers.");
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) {
            BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
        }
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // If GUI is open, handle trading
        if (mc.currentScreen instanceof MerchantScreen screen) {
            handleTrading(screen);
            return;
        }

        if (!BaritoneUtils.IS_AVAILABLE) return;

        // Find Target
        target = TargetUtils.get(entity -> {
            if (!(entity instanceof MerchantEntity)) return false;
            // if (merchant.isBaby()) return false; // Optional check
            if (mc.player.distanceTo(entity) > range.get()) return false;
            return true;
        }, SortPriority.LowestDistance);

        if (target == null) {
            if (BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()))) {
                BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
            }
            return;
        }

        // Pathfind
        if (pathTimer++ >= 20 || !BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()))) {
            pathTimer = 0;
            Object baritone = BaritoneUtils.getPrimaryBaritone();
            Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
            BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(target.getBlockPos()));
        }

        // Open Interaction
        if (mc.player.distanceTo(target) <= 3.5f) {
            mc.interactionManager.interactEntity(mc.player, target, Hand.MAIN_HAND);
        }
    }

    private int tradeStage = 0;
    private int tradeTimer = 0;

    private void handleTrading(MerchantScreen screen) {
        if (tradeTimer > 0) {
            tradeTimer--;
            return;
        }

        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList offers = handler.getRecipes();
        
        // Find best trade for desired item
        int tradeIndex = -1;
        
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer offer = offers.get(i);
            if (offer.isDisabled()) continue;
            
            // Check output matches desired item
            if (offer.getSellItem().getItem() == tradeItem.get()) {
                tradeIndex = i;
                break;
            }
        }
        
        if (tradeIndex != -1) {
            // Stage 0: Select Trade
            if (tradeStage == 0) {
                mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(tradeIndex));
                tradeStage = 1;
                tradeTimer = 2; // Wait 2 ticks for server to update container
                return;
            }
            
            // Stage 1: Click Output
            if (tradeStage == 1) {
                InvUtils.shiftClick().slotId(2);
                tradeStage = 0; // Reset for next trade
                tradeTimer = 5; // Delay before next cycle
            }
        } else {
            // No valid trades found, close
            mc.player.closeHandledScreen();
            tradeStage = 0;
        }
    }
}
