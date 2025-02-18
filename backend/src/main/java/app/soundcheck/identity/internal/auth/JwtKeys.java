package app.soundcheck.identity.internal.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ES256 (P-256) key pair that signs access tokens. Loaded from PEM text containing a
 * {@code PRIVATE KEY} (PKCS#8) block and a {@code PUBLIC KEY} (X.509) block — exactly what {@code make keys}
 * writes. {@code kid} is derived from the public key so rotation can keep two keys side by side.
 */
public record JwtKeys(ECPrivateKey privateKey, ECPublicKey publicKey, String kid) {

    private static final Pattern BLOCK =
            Pattern.compile("-----BEGIN (PRIVATE KEY|PUBLIC KEY)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    public static JwtKeys fromPem(String pem) {
        byte[] priv = null;
        byte[] pub = null;
        Matcher m = BLOCK.matcher(pem);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            if (m.group(1).equals("PRIVATE KEY")) {
                priv = der;
            } else {
                pub = der;
            }
        }
        if (priv == null || pub == null) {
            throw new IllegalArgumentException(
                    "JWT key PEM must contain a PRIVATE KEY (PKCS#8) and a PUBLIC KEY block (run `make keys`)");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPrivateKey privateKey = (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(priv));
            ECPublicKey publicKey = (ECPublicKey) kf.generatePublic(new X509EncodedKeySpec(pub));
            return of(privateKey, publicKey);
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalArgumentException("JWT key PEM is not a P-256 EC key pair", e);
        }
    }

    public static JwtKeys fromFile(Path path) {
        try {
            return fromPem(Files.readString(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read JWT key file " + path, e);
        }
    }

    /** A throwaway pair for tests and for local runs without {@code make keys} (tokens die with the process). */
    public static JwtKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return of((ECPrivateKey) pair.getPrivate(), (ECPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static JwtKeys of(ECPrivateKey privateKey, ECPublicKey publicKey) {
        return new JwtKeys(privateKey, publicKey, kidOf(publicKey));
    }

    /** First 8 bytes of SHA-256 over the encoded public key, as hex. */
    static String kidOf(ECPublicKey publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PEM encoding of this pair (private PKCS#8 + public X.509) — used by tests and key tooling. */
    public String toPem() {
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes());
        return "-----BEGIN PRIVATE KEY-----\n" + enc.encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----\n" + "-----BEGIN PUBLIC KEY-----\n"
                + enc.encodeToString(publicKey.getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    @Override
    public String toString() {
        return "JwtKeys[kid=" + kid + "]"; // never the key material
    }
}
