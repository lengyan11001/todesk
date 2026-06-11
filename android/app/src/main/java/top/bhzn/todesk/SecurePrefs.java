package top.bhzn.todesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePrefs {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "bhzn_todesk_prefs";
    private static final String PREFIX = "v1:";

    private SecurePrefs() {
    }

    static String getString(Context context, SharedPreferences prefs, String key, String fallback) {
        String current = prefs.getString(key, null);
        if (current == null || current.isEmpty()) {
            return fallback;
        }
        if (!current.startsWith(PREFIX)) {
            putString(context, prefs, key, current);
            return current;
        }
        try {
            byte[] packed = Base64.decode(current.substring(PREFIX.length()), Base64.NO_WRAP);
            if (packed.length < 13) return fallback;
            byte[] iv = new byte[12];
            byte[] cipherText = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(context), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fallback;
        }
    }

    static void putString(Context context, SharedPreferences prefs, String key, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(context));
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            prefs.edit().putString(key, PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            prefs.edit().putString(key, value).apply();
        }
    }

    private static SecretKey key(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null);
        if (entry != null) {
            return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
        }
        return generator.generateKey();
    }
}
