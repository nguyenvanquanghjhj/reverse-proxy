package com.example.store.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;
import com.example.store.validation.Input;

/** JDK PBKDF2-HMAC-SHA256, 600,000 iterations, per-password 128-bit salt. */
public final class Passwords {
    private static final SecureRandom RANDOM=new SecureRandom();
    private Passwords() {}
    public static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return HexFormat.of().formatHex(b);}
    public static String hash(String password){
        if(password.length()<12||password.length()>128)throw Input.bad("Mật khẩu cần từ 12 đến 128 ký tự.");
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        return "pbkdf2$600000$"+HexFormat.of().formatHex(salt)+"$"+HexFormat.of().formatHex(derive(password,salt));
    }
    private static byte[] derive(String password,byte[] salt){
        var spec=new PBEKeySpec(password.toCharArray(),salt,600000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(GeneralSecurityException e){throw new IllegalStateException("Password KDF unavailable",e);}finally{spec.clearPassword();}
    }
    public static boolean verify(String password,String encoded){
        try {String[] p=encoded.split("\\$");return p.length==4&&p[0].equals("pbkdf2")&&p[1].equals("600000")&&password.length()<=128
            &&MessageDigest.isEqual(derive(password,HexFormat.of().parseHex(p[2])),HexFormat.of().parseHex(p[3]));}
        catch(IllegalArgumentException e){return false;}
    }
    public static String sha(String text){return com.example.shop.OrderRequest.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
