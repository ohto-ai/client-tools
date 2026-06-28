package indi.ohtoai.tool.client_tools.client.p2p;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * AES-256-GCM encryption utility for P2P chat.
 *
 * <p>Key is derived from a user-provided password via PBKDF2.
 * Each message gets a random 12-byte IV prepended to the ciphertext.
 * The 16-byte GCM authentication tag is appended automatically by the cipher.
 */
public final class P2pEncryption {

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;       // bytes
    private static final int GCM_TAG_LEN = 128;     // bits
    private static final int KEY_LEN = 256;          // bits
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final byte[] SALT = "ClientToolsP2Pv1".getBytes(StandardCharsets.UTF_8);

    private P2pEncryption() {}

    /**
     * Derive a 256-bit AES key from a password using PBKDF2.
     */
    public static SecretKey deriveKey(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_LEN);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive key", e);
        }
    }

    /**
     * Encrypt a plaintext string with AES-256-GCM.
     *
     * @return IV + ciphertext (with appended GCM tag), as raw bytes
     */
    public static byte[] encrypt(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV
            ByteBuffer buffer = ByteBuffer.allocate(GCM_IV_LEN + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return buffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a ciphertext produced by {@link #encrypt}.
     *
     * @param data IV + ciphertext (with GCM tag)
     * @return decrypted plaintext, or {@code null} if decryption/authentication fails
     */
    public static String decrypt(byte[] data, SecretKey key) {
        try {
            if (data.length < GCM_IV_LEN + 16) return null; // too short

            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] iv = new byte[GCM_IV_LEN];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // authentication or decryption failure
        }
    }
}
