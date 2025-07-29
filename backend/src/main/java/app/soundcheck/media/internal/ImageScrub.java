package app.soundcheck.media.internal;

import app.soundcheck.media.internal.transcode.BlurHash;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * What an uploaded image becomes before anyone is shown it:
 * a copy with none of the file's metadata. A phone photo carries EXIF (the camera, the time and often
 * the GPS position) and XMP; an avatar or a photo in a post served as uploaded would publish them.
 *
 * <ul>
 *   <li><b>JPEG and PNG</b> are decoded and written again, which keeps only the pixels. Large photos are
 *       decoded with subsampling (never the full 8192-pixel canvas {@link ImageCheck} allows) and scaled
 *       so the long edge is at most {@link #MAX_EDGE}, enough for a photo filling a phone screen.
 *       The EXIF orientation is applied to the pixels first, otherwise a portrait photo would come out
 *       on its side once the tag that rotated it is gone. A second, small copy (long edge at most
 *       {@link #THUMB_EDGE}) is the thumbnail for grids and avatars, and a BlurHash of it is the
 *       placeholder; the size returned is the served copy's, upright.
 *   <li><b>WebP</b> cannot be decoded by the JDK, so its container is rewritten without the {@code EXIF}
 *       and {@code XMP } chunks (and their flags in {@code VP8X}); the image data is copied unchanged.
 *       There is no thumbnail, BlurHash or measured size: the served copy is its own thumbnail.
 * </ul>
 */
final class ImageScrub {

    /** The longest edge of a served image, in pixels (was 1024 for avatars only; photos are now shown full screen). */
    static final int MAX_EDGE = 1600;

    /** The longest edge of the thumbnail: a grid tile or an avatar. */
    static final int THUMB_EDGE = 480;

    static final float JPEG_QUALITY = 0.9f;

    private static final Set<String> WEBP_METADATA = Set.of("EXIF", "XMP ");

    /** A decoded image's served copy: its size (upright) and the BlurHash of its thumbnail. */
    record Served(int width, int height, String blurhash) {}

    private ImageScrub() {}

    /**
     * Writes the scrubbed copy of {@code source} (an image already accepted by {@link ImageCheck}) to
     * {@code target} and, for JPEG and PNG, its thumbnail to {@code thumb}.
     *
     * @return what was served, or null for a WebP (nothing decoded, no thumbnail written)
     */
    static @Nullable Served scrub(Path source, String contentType, Path target, Path thumb) throws IOException {
        return switch (contentType) {
            case "image/jpeg" -> reencode(source, target, thumb, "jpeg", JpegOrientation.of(source));
            case "image/png" -> reencode(source, target, thumb, "png", 1);
            case "image/webp" -> {
                Files.write(target, withoutWebpMetadata(Files.readAllBytes(source)));
                yield null;
            }
            default -> throw new IllegalArgumentException("not an image type: " + contentType);
        };
    }

    private static Served reencode(Path source, Path target, Path thumb, String format, int orientation)
            throws IOException {
        BufferedImage image = upright(fit(read(source), MAX_EDGE), orientation);
        BufferedImage small = fit(image, THUMB_EDGE);
        write(image, target, format);
        write(small, thumb, format);
        return new Served(image.getWidth(), image.getHeight(), blurhash(small));
    }

    private static void write(BufferedImage image, Path target, String format) throws IOException {
        if (format.equals("jpeg")) {
            writeJpeg(withoutAlpha(image), target);
        } else if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("no PNG writer");
        }
    }

    /** 4×3 components, as the worker gives a clip's poster, from a copy at most 64 pixels wide. */
    private static String blurhash(BufferedImage image) {
        BufferedImage tiny = fit(image, 64);
        BufferedImage rgb = new BufferedImage(tiny.getWidth(), tiny.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(tiny, 0, 0, null);
        g.dispose();
        return BlurHash.encode(rgb, 4, 3);
    }

    /** Decodes with subsampling, so the decoded image is at most about twice {@link #MAX_EDGE} wide. */
    private static BufferedImage read(Path source) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(source.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("not a readable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int longEdge = Math.max(reader.getWidth(0), reader.getHeight(0));
                // Rounded up, so the decoded edge is at most 2 × MAX_EDGE (a 4032-pixel phone photo is read
                // at 2016, not in full) and still at least MAX_EDGE, which the scaling then brings down.
                int step = Math.max(1, Math.ceilDiv(longEdge, 2 * MAX_EDGE));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Scales down (never up) so the long edge is at most {@code maxEdge}. */
    private static BufferedImage fit(BufferedImage image, int maxEdge) {
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (longEdge <= maxEdge) {
            return image;
        }
        double scale = (double) maxEdge / longEdge;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /**
     * Applies an EXIF orientation (1–8) to the pixels. 1 is upright; 3 is upside down; 6 and 8 are a
     * phone held upright (rotate 90° clockwise, or counter-clockwise); 2, 4, 5 and 7 are mirrored.
     */
    static BufferedImage upright(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> t.setTransform(-1, 0, 0, 1, w, 0); // mirror horizontally
            case 3 -> t.setTransform(-1, 0, 0, -1, w, h); // rotate 180°
            case 4 -> t.setTransform(1, 0, 0, -1, 0, h); // mirror vertically
            case 5 -> t.setTransform(0, 1, 1, 0, 0, 0); // mirror along the main diagonal
            case 6 -> t.setTransform(0, 1, -1, 0, h, 0); // rotate 90° clockwise
            case 7 -> t.setTransform(0, -1, -1, 0, h, w); // mirror along the other diagonal
            case 8 -> t.setTransform(0, -1, 1, 0, 0, w); // rotate 90° counter-clockwise
            default -> {
                return image;
            }
        }
        boolean turned = orientation >= 5;
        BufferedImage out = new BufferedImage(turned ? h : w, turned ? w : h, BufferedImage.TYPE_INT_ARGB);
        new AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(withAlpha(image), out);
        return out;
    }

    private static BufferedImage withAlpha(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** JPEG has no transparency: the image is drawn onto white RGB. */
    private static BufferedImage withoutAlpha(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * The WebP container without its metadata chunks. A RIFF file is "RIFF", a little-endian size, "WEBP",
     * then chunks of a four-letter name, a little-endian length and the data (padded to an even length).
     * Chunks are walked as far as they are well-formed; anything after a malformed one is copied as it
     * is, and the RIFF size is written again for the new length.
     */
    static byte[] withoutWebpMetadata(byte[] file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length);
        out.write(file, 0, Math.min(12, file.length));
        int at = 12;
        while (at + 8 <= file.length) {
            String name = new String(file, at, 4, StandardCharsets.US_ASCII);
            long length = ByteBuffer.wrap(file, at + 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt()
                    & 0xFFFFFFFFL;
            long end = at + 8 + length + (length % 2);
            if (end > file.length) {
                break; // malformed: copy the rest unchanged
            }
            if (!WEBP_METADATA.contains(name)) {
                int start = out.size();
                out.write(file, at, (int) (end - at));
                if (name.equals("VP8X") && length >= 1) {
                    byte[] written = out.toByteArray();
                    written[start + 8] &= (byte) ~0x0C; // clear the EXIF (bit 3) and XMP (bit 2) flags
                    out.reset();
                    out.write(written, 0, written.length);
                }
            }
            at = (int) end;
        }
        if (at < file.length) {
            out.write(file, at, file.length - at);
        }
        byte[] result = out.toByteArray();
        if (result.length >= 8) {
            ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(result.length - 8);
        }
        return result;
    }
}
