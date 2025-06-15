package app.soundcheck.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ImageScrub} and {@link JpegOrientation} on hand-made files: a phone-style JPEG whose EXIF holds
 * an orientation and a stand-in for a GPS position, a large PNG with a text chunk, and WebP containers
 * with EXIF and XMP chunks. "SECRET" stands for anything the metadata could give away.
 */
class ImageScrubTest {

    static final String SECRET = "SECRET 51.5072N 0.1276W";

    @TempDir
    Path dir;

    @Test
    void aJpegLosesItsExifAndIsTurnedTheWayTheExifSaid() throws Exception {
        // 300 × 200, landscape as stored, with a red corner top-left; EXIF orientation 6 = rotate 90° clockwise.
        byte[] jpeg = withExif(jpeg(cornered(300, 200)), 6, ByteOrder.BIG_ENDIAN);
        assertThat(JpegOrientation.of(jpeg)).isEqualTo(6);
        assertThat(contains(jpeg, SECRET)).isTrue();

        byte[] served = scrub(jpeg, "image/jpeg");

        assertThat(contains(served, SECRET)).isFalse();
        assertThat(contains(served, "Exif")).isFalse();
        assertThat(JpegOrientation.of(served)).isEqualTo(1);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(300);
        // Turned clockwise, the top-left corner is now the top-right one.
        assertThat(isRed(image.getRGB(image.getWidth() - 5, 5))).isTrue();
        assertThat(isRed(image.getRGB(5, 5))).isFalse();
    }

    @Test
    void orientationIsReadInBothByteOrdersAndMissingOrBrokenExifReadsAsUpright() throws Exception {
        assertThat(JpegOrientation.of(withExif(jpeg(cornered(40, 30)), 8, ByteOrder.LITTLE_ENDIAN)))
                .isEqualTo(8);
        assertThat(JpegOrientation.of(jpeg(cornered(40, 30)))).isEqualTo(1);
        assertThat(JpegOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 2}))
                .isEqualTo(1);
        assertThat(JpegOrientation.of("not a jpeg".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(1);
    }

    @Test
    void everyOrientationGivesTheRightShape() {
        BufferedImage wide = cornered(30, 20);
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage out = ImageScrub.upright(wide, orientation);
            boolean turned = orientation >= 5;
            assertThat(out.getWidth()).as("orientation %d", orientation).isEqualTo(turned ? 20 : 30);
            assertThat(out.getHeight()).as("orientation %d", orientation).isEqualTo(turned ? 30 : 20);
        }
        // 3 (upside down) puts the red corner bottom-right; 8 (counter-clockwise) bottom-left.
        BufferedImage flipped = ImageScrub.upright(wide, 3);
        assertThat(isRed(flipped.getRGB(29, 19))).isTrue();
        BufferedImage left = ImageScrub.upright(wide, 8);
        assertThat(isRed(left.getRGB(0, 29))).isTrue();
    }

    @Test
    void aLargePngIsScaledToTheMaximumEdgeAndLosesItsTextChunks() throws Exception {
        byte[] png = withTextChunk(png(cornered(3000, 1000)));
        assertThat(contains(png, SECRET)).isTrue();

        byte[] served = scrub(png, "image/png");

        assertThat(contains(served, SECRET)).isFalse();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(image.getWidth()).isEqualTo(ImageScrub.MAX_EDGE);
        assertThat(image.getHeight()).isEqualTo(341);
        // A small image is not scaled up.
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(scrub(png(cornered(64, 48)), "image/png")));
        assertThat(small.getWidth()).isEqualTo(64);
    }

    @Test
    void aWebpLosesItsExifAndXmpChunksAndTheirFlagsAndKeepsItsImageData() {
        byte[] vp8x = new byte[10];
        vp8x[0] = 0x0C | 0x10; // EXIF and XMP flags set, and the alpha flag, which must stay
        byte[] imageData = "VP8-bytes!".getBytes(StandardCharsets.US_ASCII); // 10 bytes, even
        byte[] webp = riff(
                chunk("VP8X", vp8x),
                chunk("VP8 ", imageData),
                chunk("EXIF", SECRET.getBytes(StandardCharsets.US_ASCII)),
                chunk("XMP ", ("<x>" + SECRET + "</x>").getBytes(StandardCharsets.US_ASCII)));

        byte[] served = ImageScrub.withoutWebpMetadata(webp);

        assertThat(contains(served, SECRET)).isFalse();
        assertThat(served).isEqualTo(riff(chunk("VP8X", flags(0x10)), chunk("VP8 ", imageData)));
        assertThat(ByteBuffer.wrap(served, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                .isEqualTo(served.length - 8);
    }

    @Test
    void aMalformedWebpIsCopiedPastTheBrokenChunkWithoutFailing() {
        byte[] fake = new byte[200]; // the upload test's minimal container: a signature and zeros
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, fake, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, fake, 8, 4);
        assertThat(ImageScrub.withoutWebpMetadata(fake)).hasSize(200);
    }

    // ── hand-made files ─────────────────────────────────────────────────────

    private byte[] scrub(byte[] file, String contentType) throws Exception {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        Files.write(in, file);
        Files.deleteIfExists(out);
        Files.createFile(out);
        ImageScrub.scrub(in, contentType, out);
        return Files.readAllBytes(out);
    }

    /** A grey image with a red 10 × 10 corner at the top left. */
    private static BufferedImage cornered(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        return image;
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60;
    }

    private static byte[] jpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * Inserts an APP1 "Exif" segment after the start marker: a TIFF header and one directory with two
     * entries, the orientation (SHORT) and an ImageDescription (ASCII) holding the secret, as a phone's
     * GPS block would hold a position.
     */
    private static byte[] withExif(byte[] jpeg, int orientation, ByteOrder order) {
        byte[] text = (SECRET + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + text.length).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.putShort((short) 42).putInt(8); // IFD0 right after the header
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0);
        int textOffset = 8 + 2 + 2 * 12 + 4;
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(text.length).putInt(textOffset);
        tiff.putInt(0); // no next directory
        tiff.put(text);
        byte[] body = tiff.array();
        int length = 2 + 6 + body.length;
        // After the start marker and the JFIF segment ImageIO writes (APP0), where a reader expects it.
        int insertAt = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            insertAt = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, insertAt);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body);
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }

    /** Inserts a tEXt chunk ("Comment", the secret) before IEND. */
    private static byte[] withTextChunk(byte[] png) {
        byte[] data = ("Comment\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put(type).put(data).putInt((int) crc.getValue());
        int iend = png.length - 12;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, iend);
        out.writeBytes(chunk.array());
        out.write(png, iend, 12);
        return out.toByteArray();
    }

    private static byte[] flags(int flags) {
        byte[] vp8x = new byte[10];
        vp8x[0] = (byte) flags;
        return vp8x;
    }

    private static byte[] chunk(String name, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(8 + data.length + data.length % 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(name.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
        return b.array();
    }

    private static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        for (byte[] c : chunks) {
            body.writeBytes(c);
        }
        ByteBuffer out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(body.size()).put(body.toByteArray());
        return out.array();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
