package com.netessx.watchdashboard.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Small AES/GCM helper backed by the Android Keystore. The watch holds a
 * bearer token that can approve agent actions, so it must not sit in plain
 * SharedPreferences.
 */
final class SecurePrefs {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "watch_agent_token_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecurePrefs() {
    }

    static String encrypt(String plain) throws Exception {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[1 + iv.length + ciphertext.length];
        packed[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packed, 1, iv.length);
        System.arraycopy(ciphertext, 0, packed, 1 + iv.length, ciphertext.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static String decrypt(String encoded) throws Exception {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length < 3) {
            return "";
        }
        int ivLength = packed[0] & 0xFF;
        if (ivLength <= 0 || packed.length < 1 + ivLength) {
            return "";
        }
        byte[] iv = Arrays.copyOfRange(packed, 1, 1 + ivLength);
        byte[] ciphertext = Arrays.copyOfRange(packed, 1 + ivLength, packed.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** Deletes the encryption key. Used when the key was invalidated by the OS. */
    static void reset() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
            // Best effort only. The caller will ask the user to re-enter the token.
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) {
                return (SecretKey) key;
            }
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
