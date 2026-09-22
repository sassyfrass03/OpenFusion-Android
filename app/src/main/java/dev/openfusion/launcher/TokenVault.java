package dev.openfusion.launcher;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    private final SharedPreferences prefs;
    private static final String ALIAS="openfusion-refresh-v1";
    TokenVault(Context c){prefs=c.getSharedPreferences("account-vault",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
    }
    void put(String endpoint,String token) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        String encoded=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString(endpoint,encoded).apply();
    }
    String get(String endpoint) throws Exception {
        String encoded=prefs.getString(endpoint,null);if(encoded==null)return null;
        String[] parts=encoded.split(":");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        c.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    void remove(String endpoint){prefs.edit().remove(endpoint).apply();}
}
