package com.trollmods.adgif;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.texture.NativeImageBackedTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decodes an animated gif (via javax.imageio) into a sequence of Minecraft
 * textures we can blit to the HUD, and keeps track of playback timing.
 *
 * NOTE ON MINECRAFT 1.21.11:
 * This version of Minecraft was the last one shipped with (now unsupported)
 * Yarn mappings, and Mojang's own mappings are used here directly via Loom's
 * officialMojangMappings(). Some low-level texture/rendering classes were
 * reworked around this time as part of Mojang's ongoing GPU pipeline
 * refactor. If this file fails to compile, the most likely culprits are:
 *   - the NativeImageBackedTexture constructor (handled defensively below
 *     via reflection, so it should adapt automatically to either the old
 *     "(NativeImage)" or newer "(Supplier<String>, NativeImage)" signature)
 *   - the ResourceManager#getResource(...) return type
 * See https://mappings.dev/1.21.11/ to look up current signatures.
 */
public class GifAnimation {

    public static class Frame {
        public final ResourceLocation textureId;
        public final int delayMs;

        Frame(ResourceLocation textureId, int delayMs) {
            this.textureId = textureId;
            this.delayMs = delayMs;
        }
    }

    private final List<Frame> frames = new ArrayList<>();
    private int totalDurationMs;
    public final int width;
    public final int height;

    private GifAnimation(List<Frame> frames, int width, int height) {
        this.frames.addAll(frames);
        this.width = width;
        this.height = height;
        for (Frame f : frames) {
            totalDurationMs += f.delayMs;
        }
        if (totalDurationMs <= 0) {
            totalDurationMs = 100;
        }
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /** Picks the correct frame for a given elapsed time, looping the gif if needed. */
    public ResourceLocation frameAt(long elapsedMs) {
        if (frames.isEmpty()) {
            return null;
        }
        long t = elapsedMs % totalDurationMs;
        for (Frame f : frames) {
            if (t < f.delayMs) {
                return f.textureId;
            }
            t -= f.delayMs;
        }
        return frames.get(frames.size() - 1).textureId;
    }

    /**
     * Loads and decodes the gif at the given resource location (e.g.
     * "adgif:textures/gui/ad.gif"). Returns null (and logs) on failure so the
     * mod can simply skip showing anything instead of crashing the game.
     */
    public static GifAnimation load(ResourceManager manager, ResourceLocation location) {
        try {
            Optional<Resource> resourceOpt = manager.getResource(location);
            if (resourceOpt.isEmpty()) {
                System.err.println("[adgif] Could not find gif resource: " + location
                        + " -- make sure it's at src/main/resources/assets/" + location.getNamespace()
                        + "/" + location.getPath());
                return null;
            }
            try (InputStream in = resourceOpt.get().open()) {
                return decode(in, location);
            }
        } catch (Exception e) {
            System.err.println("[adgif] Failed to load/decode gif: " + e);
            e.printStackTrace();
            return null;
        }
    }

    private static GifAnimation decode(InputStream in, ResourceLocation location) throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("gif");
        if (!readers.hasNext()) {
            System.err.println("[adgif] No GIF ImageReader available on this JVM.");
            return null;
        }
        ImageReader reader = readers.next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            reader.setInput(iis, false);
            int count = reader.getNumImages(true);
            if (count <= 0) {
                return null;
            }

            int canvasWidth = reader.getWidth(0);
            int canvasHeight = reader.getHeight(0);
            // Try to read the logical screen descriptor for the true canvas size.
            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
                    IIOMetadataNode lsd = getChild(root, "LogicalScreenDescriptor");
                    if (lsd != null) {
                        canvasWidth = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                        canvasHeight = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                    }
                }
            } catch (Exception ignored) {
                // fall back to first-frame size, good enough for most gifs
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            BufferedImage savedForRestore = null;

            List<Frame> frames = new ArrayList<>();
            long uniquePrefix = System.nanoTime();

            for (int i = 0; i < count; i++) {
                BufferedImage rawFrame = reader.read(i);
                IIOMetadata metadata = reader.getImageMetadata(i);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");

                int left = 0, top = 0;
                IIOMetadataNode imgDesc = getChild(root, "ImageDescriptor");
                if (imgDesc != null) {
                    left = parseIntSafe(imgDesc.getAttribute("imageLeftPosition"), 0);
                    top = parseIntSafe(imgDesc.getAttribute("imageTopPosition"), 0);
                }

                int delayCentiseconds = 10; // default 100ms
                String disposal = "none";
                IIOMetadataNode gce = getChild(root, "GraphicControlExtension");
                if (gce != null) {
                    delayCentiseconds = parseIntSafe(gce.getAttribute("delayTime"), 10);
                    String d = gce.getAttribute("disposalMethod");
                    if (d != null && !d.isEmpty()) {
                        disposal = d;
                    }
                }
                int delayMs = delayCentiseconds * 10;
                if (delayMs < 20) {
                    delayMs = 100; // many gifs use 0 to mean "as fast as possible"; clamp to something sane
                }

                if ("restoreToPrevious".equals(disposal)) {
                    savedForRestore = deepCopy(canvas);
                }

                g.drawImage(rawFrame, left, top, null);

                BufferedImage composited = deepCopy(canvas);

                NativeImage nativeImage = toNativeImage(composited);
                String label = "adgif_frame_" + uniquePrefix + "_" + i;
                NativeImageBackedTexture texture = createTexture(label, nativeImage);
                ResourceLocation texId = ResourceLocation.fromNamespaceAndPath("adgif", "dynamic/" + label);
                Minecraft.getInstance().getTextureManager().register(texId, texture);
                frames.add(new Frame(texId, delayMs));

                if ("restoreToBackground".equals(disposal)) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(left, top, rawFrame.getWidth(), rawFrame.getHeight());
                    g.setComposite(AlphaComposite.SrcOver);
                } else if ("restoreToPrevious".equals(disposal) && savedForRestore != null) {
                    canvas = savedForRestore;
                    g.dispose();
                    g = canvas.createGraphics();
                }
                // "none" / "doNotDispose" -> leave canvas as-is for next frame
            }
            g.dispose();

            if (frames.isEmpty()) {
                return null;
            }
            return new GifAnimation(frames, canvasWidth, canvasHeight);
        } finally {
            reader.dispose();
        }
    }

    private static IIOMetadataNode getChild(IIOMetadataNode parent, String name) {
        if (parent == null) return null;
        for (int i = 0; i < parent.getLength(); i++) {
            org.w3c.dom.Node n = parent.item(i);
            if (n.getNodeName().equals(name)) {
                return (IIOMetadataNode) n;
            }
        }
        return null;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int gg = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (gg << 8) | r;
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }
        return nativeImage;
    }

    /**
     * Constructs a NativeImageBackedTexture without hard-coding which
     * constructor overload exists in this Minecraft version. Tries the
     * modern "(Supplier<String> label, NativeImage image)" signature first,
     * then falls back to the classic "(NativeImage image)" signature.
     */
    @SuppressWarnings("unchecked")
    private static NativeImageBackedTexture createTexture(String label, NativeImage image) {
        for (Constructor<?> ctor : NativeImageBackedTexture.class.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            try {
                if (params.length == 2 && Supplier.class.isAssignableFrom(params[0]) && params[1] == NativeImage.class) {
                    Supplier<String> supplier = () -> label;
                    return (NativeImageBackedTexture) ctor.newInstance(supplier, image);
                }
                if (params.length == 2 && params[0] == String.class && params[1] == NativeImage.class) {
                    return (NativeImageBackedTexture) ctor.newInstance(label, image);
                }
                if (params.length == 1 && params[0] == NativeImage.class) {
                    return (NativeImageBackedTexture) ctor.newInstance(image);
                }
            } catch (ReflectiveOperationException ignored) {
                // try next candidate
            }
        }
        throw new RuntimeException("[adgif] No compatible NativeImageBackedTexture constructor found. "
                + "Minecraft's texture API changed in this version - check https://mappings.dev/1.21.11/ "
                + "for NativeImageBackedTexture and update GifAnimation#createTexture accordingly.");
    }
}
