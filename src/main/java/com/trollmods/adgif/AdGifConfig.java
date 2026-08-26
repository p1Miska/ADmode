package com.trollmods.adgif;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config stored at config/adgif.json.
 * Edit the file directly (in the "config" folder of your instance) to change
 * the gif, its size, how long it stays on screen, and how likely each
 * trigger is to fire.
 */
public class AdGifConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "adgif.json";

    // ---- configurable fields (all public so Gson can read/write them) ----

    /** Path to the gif inside your resources, e.g. "adgif:textures/gui/ad.gif" */
    public String gifPath = "adgif:textures/gui/ad.gif";

    /**
     * Size multiplier applied to the gif's native pixel size.
     * 1.0 = original size, 2.0 = double size, 0.5 = half size, etc.
     * This is the "настроить размер" knob.
     */
    public double scale = 1.0;

    /** How long the overlay stays on screen, in seconds. */
    public double durationSeconds = 7.0;

    /** Chance [0..1] to trigger at a random moment, checked every randomCheckIntervalSeconds. */
    public double chanceRandom = 0.40;

    /** Chance [0..1] to trigger when the player takes damage. */
    public double chanceOnDamage = 0.60;

    /** Chance [0..1] to trigger when the player attacks something. */
    public double chanceOnAttack = 0.50;

    /** Chance [0..1] to trigger on a generic action (open a block/GUI, break a block, use an item). */
    public double chanceOnAction = 0.15;

    /** How often (in seconds) the "random moment" roll is attempted. */
    public double randomCheckIntervalSeconds = 30.0;

    /** Whether to play the accompanying sound (assets/adgif/sounds/ad.ogg) when the ad starts. */
    public boolean playSound = true;

    /** Volume for the ad sound, 0.0 - 1.0 (uses the Master volume slider). */
    public double soundVolume = 1.0;

    // ------------------------------------------------------------------

    public static AdGifConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                AdGifConfig loaded = GSON.fromJson(reader, AdGifConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[adgif] Failed to read config, using defaults: " + e);
            }
        }
        AdGifConfig defaults = new AdGifConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[adgif] Failed to save config: " + e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
