package app.soundcheck.media.internal.transcode;

import java.awt.image.BufferedImage;

/**
 * BlurHash encoder (https://blurha.sh, algorithm by Wolt, MIT): a ~28-character string from which a
 * client can paint a blurred placeholder before the real image loads. The image is reduced to a few
 * cosine components per axis (like the lowest frequencies of a JPEG) in linear light, and the
 * coefficients are packed into base-83 text. Checked against the reference JavaScript implementation in
 * {@code BlurHashTest}.
 */
public final class BlurHash {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private BlurHash() {}

    public static String encode(BufferedImage image, int componentsX, int componentsY) {
        int width = image.getWidth();
        int height = image.getHeight();
        return encode(image.getRGB(0, 0, width, height, null, 0, width), width, height, componentsX, componentsY);
    }

    /** @param rgb pixels as 0xRRGGBB (alpha ignored), row by row */
    public static String encode(int[] rgb, int width, int height, int componentsX, int componentsY) {
        if (componentsX < 1 || componentsX > 9 || componentsY < 1 || componentsY > 9) {
            throw new IllegalArgumentException("BlurHash needs 1 to 9 components per axis");
        }
        double[][] factors = new double[componentsX * componentsY][3];
        for (int j = 0; j < componentsY; j++) {
            for (int i = 0; i < componentsX; i++) {
                double normalisation = (i == 0 && j == 0) ? 1 : 2;
                double r = 0, g = 0, b = 0;
                for (int y = 0; y < height; y++) {
                    double cosY = Math.cos(Math.PI * j * y / height);
                    for (int x = 0; x < width; x++) {
                        double basis = normalisation * Math.cos(Math.PI * i * x / width) * cosY;
                        int pixel = rgb[y * width + x];
                        r += basis * srgbToLinear((pixel >> 16) & 0xFF);
                        g += basis * srgbToLinear((pixel >> 8) & 0xFF);
                        b += basis * srgbToLinear(pixel & 0xFF);
                    }
                }
                double scale = 1.0 / (width * height);
                factors[j * componentsX + i] = new double[] {r * scale, g * scale, b * scale};
            }
        }

        StringBuilder hash = new StringBuilder();
        base83(hash, (componentsX - 1) + (componentsY - 1) * 9, 1);

        double maximum = 1;
        if (factors.length > 1) {
            double actualMaximum = 0;
            for (int n = 1; n < factors.length; n++) {
                for (double channel : factors[n]) {
                    actualMaximum = Math.max(actualMaximum, Math.abs(channel));
                }
            }
            int quantised = (int) Math.max(0, Math.min(82, Math.floor(actualMaximum * 166 - 0.5)));
            maximum = (quantised + 1) / 166.0;
            base83(hash, quantised, 1);
        } else {
            base83(hash, 0, 1);
        }

        double[] dc = factors[0];
        base83(hash, (linearToSrgb(dc[0]) << 16) + (linearToSrgb(dc[1]) << 8) + linearToSrgb(dc[2]), 4);
        for (int n = 1; n < factors.length; n++) {
            int r = quantiseAc(factors[n][0], maximum);
            int g = quantiseAc(factors[n][1], maximum);
            int b = quantiseAc(factors[n][2], maximum);
            base83(hash, r * 19 * 19 + g * 19 + b, 2);
        }
        return hash.toString();
    }

    private static int quantiseAc(double value, double maximum) {
        double v = value / maximum;
        double signedSqrt = Math.signum(v) * Math.sqrt(Math.abs(v));
        return (int) Math.max(0, Math.min(18, Math.floor(signedSqrt * 9 + 9.5)));
    }

    private static double srgbToLinear(int value) {
        double v = value / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSrgb(double value) {
        double v = Math.max(0, Math.min(1, value));
        return v <= 0.0031308
                ? (int) Math.round(v * 12.92 * 255 + 0.5 - 0.5)
                : (int) Math.round((1.055 * Math.pow(v, 1 / 2.4) - 0.055) * 255 + 0.5 - 0.5);
    }

    private static void base83(StringBuilder out, int value, int length) {
        for (int i = 1; i <= length; i++) {
            int digit = (value / (int) Math.pow(83, length - i)) % 83;
            out.append(ALPHABET.charAt(digit));
        }
    }
}
