package app.soundcheck.media.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Checks that an uploaded image is what its content type says before it is served to anyone.
 * Only the header is read: the format's signature and, for JPEG and
 * PNG, the dimensions. Nothing is decoded into memory, so a small file that claims a huge canvas
 * (a decompression bomb) costs nothing to refuse.
 */
final class ImageCheck {

    /** No avatar or poster needs more; a larger canvas is refused. */
    static final int MAX_DIMENSION = 8192;

    private ImageCheck() {}

    /** @return why the file is not acceptable as {@code contentType}, or null when it is */
    static @Nullable String problem(Path file, String contentType) {
        try {
            return switch (contentType) {
                case "image/jpeg" -> decodable(file, "jpeg");
                case "image/png" -> decodable(file, "png");
                case "image/webp" -> webp(file);
                default -> "unsupported image type";
            };
        } catch (IOException | RuntimeException e) {
            // ImageIO readers throw assorted runtime exceptions on malformed input.
            return "not a readable image";
        }
    }

    private static @Nullable String decodable(Path file, String format) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return "not a readable image";
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return "not a readable image";
            }
            ImageReader reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(format)) {
                    return "the file is " + reader.getFormatName().toLowerCase() + ", not " + format;
                }
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    return "image dimensions " + width + "x" + height + " are not allowed";
                }
                return null;
            } finally {
                reader.dispose();
            }
        }
    }

    /** The JDK has no WebP reader: check the container signature ("RIFF" + size + "WEBP"). */
    private static @Nullable String webp(Path file) throws IOException {
        byte[] head = new byte[12];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, 12) < 12) {
                return "not a readable image";
            }
        }
        boolean riff = Arrays.equals(Arrays.copyOfRange(head, 0, 4), "RIFF".getBytes(StandardCharsets.US_ASCII));
        boolean webp = Arrays.equals(Arrays.copyOfRange(head, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
        return riff && webp ? null : "not a readable image";
    }
}
