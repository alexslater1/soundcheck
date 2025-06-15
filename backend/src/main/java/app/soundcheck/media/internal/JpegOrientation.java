package app.soundcheck.media.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads the EXIF orientation of a JPEG (tag 0x0112 in the first image directory of the APP1 "Exif"
 * segment), the one piece of metadata {@link ImageScrub} must honour before dropping the rest. Anything
 * missing or malformed reads as 1, upright.
 */
final class JpegOrientation {

    static final int ORIENTATION_TAG = 0x0112;

    /** Only the start of the file is read: the APP1 segment comes right after the start marker. */
    static final int HEAD_BYTES = 128 * 1024;

    private JpegOrientation() {}

    static int of(Path jpeg) throws IOException {
        try (InputStream in = Files.newInputStream(jpeg)) {
            return of(in.readNBytes(HEAD_BYTES));
        }
    }

    static int of(byte[] head) {
        try {
            return find(head);
        } catch (RuntimeException e) {
            return 1; // a truncated or odd segment: treat as upright
        }
    }

    private static int find(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int at = 2;
        while (at + 4 <= b.length && (b[at] & 0xFF) == 0xFF) {
            int marker = b[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // start of the image data, or its end: no EXIF before it
            }
            int length = ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
            int data = at + 4;
            if (marker == 0xE1 && data + 6 <= b.length && isExif(b, data)) {
                return fromTiff(b, data + 6, Math.min(b.length, at + 2 + length));
            }
            at += 2 + length;
        }
        return 1;
    }

    private static boolean isExif(byte[] b, int at) {
        return Arrays.equals(Arrays.copyOfRange(b, at, at + 6), "Exif\0\0".getBytes(StandardCharsets.US_ASCII));
    }

    /** The TIFF structure: byte order, 42, the offset of IFD0, then 12-byte entries. */
    private static int fromTiff(byte[] b, int tiff, int end) {
        ByteBuffer buf = ByteBuffer.wrap(b, 0, end);
        buf.order(b[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int ifd = tiff + buf.getInt(tiff + 4);
        int entries = buf.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if ((buf.getShort(entry) & 0xFFFF) == ORIENTATION_TAG) {
                int value = buf.getShort(entry + 8) & 0xFFFF; // a SHORT, stored in the value field
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }
}
