package com.trollmods.adgif;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;

import java.util.Random;

/**
 * Entry point. Wires up:
 *  - random-moment trigger (checked every N seconds)
 *  - on-damage trigger (health decreased since last tick)
 *  - on-attack trigger (player hits an entity)
 *  - on-action trigger (break block / use block / use item)
 *  - the 7s "frozen ad" state: cancels all those same actions and zeroes
 *    movement input while active, without pausing the world/game.
 *
 * See GifAnimation.java for notes about 1.21.11-specific risk areas.
 */
public class AdGifClient implements ClientModInitializer {

    private final Random random = new Random();
    private AdGifConfig config;
    private GifAnimation gif;
    private boolean gifLoadAttempted = false;

    private static final SoundEvent AD_SOUND =
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("adgif", "ad"));

    private boolean active = false;
    private long adStartMs = 0L;
    private int randomCheckTickCounter = 0;
    private float lastHealth = -1f;

    @Override
    public void onInitializeClient() {
        config = AdGifConfig.load();

        // --- rendering ---
        HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> renderIfActive(guiGraphics));

        // --- per-tick logic: random trigger, damage detection, freeze enforcement ---
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // --- attack trigger + cancel while active ---
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (active) {
                return InteractionResult.FAIL;
            }
            tryTrigger(config.chanceOnAttack);
            return InteractionResult.PASS;
        });

        // --- "any action" triggers + cancel while active ---
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (active) {
                return InteractionResult.FAIL;
            }
            tryTrigger(config.chanceOnAction);
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (active) {
                return InteractionResult.FAIL;
            }
            tryTrigger(config.chanceOnAction);
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (active) {
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            tryTrigger(config.chanceOnAction);
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (active) {
                return false; // cancel breaking while the ad is showing
            }
            tryTrigger(config.chanceOnAction);
            return true;
        });
    }

    private void onClientTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        if (active) {
            long elapsed = System.currentTimeMillis() - adStartMs;
            long durationMs = (long) (config.durationSeconds * 1000);
            if (elapsed >= durationMs) {
                active = false;
            } else {
                freezeInputThisTick(client);
            }
            // keep health tracking in sync so we don't "trigger" the moment the ad ends
            lastHealth = player.getHealth();
            return;
        }

        // damage detection
        float health = player.getHealth();
        if (lastHealth >= 0f && health < lastHealth) {
            tryTrigger(config.chanceOnDamage);
        }
        lastHealth = health;

        // periodic random roll
        randomCheckTickCounter++;
        int intervalTicks = (int) Math.max(20, config.randomCheckIntervalSeconds * 20);
        if (randomCheckTickCounter >= intervalTicks) {
            randomCheckTickCounter = 0;
            tryTrigger(config.chanceRandom);
        }
    }

    /** Zeroes out movement/action key state for this tick, without pausing the world. */
    private void freezeInputThisTick(Minecraft client) {
        var options = client.options;
        KeyMapping[] toBlock = new KeyMapping[]{
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keySneak, options.keySprint,
                options.keyAttack, options.keyUse,
                options.keyInventory, options.keyDrop,
                options.keySwapOffhand, options.keyPickItem
        };
        for (KeyMapping key : toBlock) {
            key.setDown(false);
        }
    }

    private void tryTrigger(double chance) {
        if (active) {
            return; // ignore any trigger while the ad is already showing
        }
        if (random.nextDouble() < chance) {
            startAd();
        }
    }

    private void startAd() {
        ensureGifLoaded();
        if (gif == null || gif.isEmpty()) {
            return; // nothing to show, don't "freeze" the player for no reason
        }
        active = true;
        adStartMs = System.currentTimeMillis();

        if (config.playSound) {
            try {
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(AD_SOUND, 1.0f, (float) config.soundVolume)
                );
            } catch (Exception e) {
                // Missing/invalid ad.ogg shouldn't crash the game, just skip the sound.
                System.err.println("[adgif] Could not play ad sound (did you add assets/adgif/sounds/ad.ogg "
                        + "and sounds.json?): " + e);
            }
        }
    }

    private void ensureGifLoaded() {
        if (gifLoadAttempted) {
            return;
        }
        gifLoadAttempted = true;
        Minecraft client = Minecraft.getInstance();
        ResourceLocation location = ResourceLocation.parse(config.gifPath);
        gif = GifAnimation.load(client.getResourceManager(), location);
    }

    private void renderIfActive(GuiGraphics guiGraphics) {
        if (!active || gif == null || gif.isEmpty()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - adStartMs;
        ResourceLocation frame = gif.frameAt(elapsed);
        if (frame == null) {
            return;
        }

        int w = (int) Math.max(1, gif.width * config.scale);
        int h = (int) Math.max(1, gif.height * config.scale);
        int x = (guiGraphics.guiWidth() - w) / 2;
        int y = (guiGraphics.guiHeight() - h) / 2;

        // NOTE: RenderPipelines.GUI_TEXTURED is the standard pipeline for plain
        // GUI textures on 1.21.5+. If this line fails to compile, check
        // net.minecraft.client.renderer.RenderPipelines on
        // https://mappings.dev/1.21.11/ for the current constant location/name.
        guiGraphics.blit(
                net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                frame,
                x, y,
                0, 0,
                w, h,
                gif.width, gif.height
        );
    }
}
