package szserver.net;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Domain identity rules from the old Store, shared by the native HTTP facade. */
public final class AccountIdentity {
    private AccountIdentity(){}
    public static String hash(String token){
        if(token==null||token.isEmpty()||token.codePointCount(0,token.length())>512||!unicode(token))throw new IllegalArgumentException("invalid_token");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String nativeHash(String login){return hash("stalcraft-http:"+login);}
    private static boolean space(int point){return point==0x85||Character.isWhitespace(point)||Character.isSpaceChar(point);}
    public static String strip(String value){if(value==null)return "";int first=0,last=value.length();while(first<last&&space(value.codePointAt(first)))first+=Character.charCount(value.codePointAt(first));while(last>first&&space(value.codePointBefore(last)))last-=Character.charCount(value.codePointBefore(last));return value.substring(first,last);}
    public static String name(String raw){return label(raw,3,24,false,"invalid_character");}
    public static String label(String raw,int min,int max,boolean lower,String error){String name=strip(raw);if(lower)name=name.toLowerCase(Locale.ROOT);int count=name.codePointCount(0,name.length());if(count<min||count>max||!unicode(name)||!name.codePoints().allMatch(point->Character.isLetter(point)||switch(Character.getType(point)){case Character.DECIMAL_DIGIT_NUMBER,Character.LETTER_NUMBER,Character.OTHER_NUMBER->true;default->point=='_'||point=='-';}))throw new IllegalArgumentException(error);return name;}
    private static boolean unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    public static int compareNames(String a,String b){var x=a.codePoints().iterator();var y=b.codePoints().iterator();while(x.hasNext()&&y.hasNext()){int difference=Integer.compare(x.nextInt(),y.nextInt());if(difference!=0)return difference;}return Boolean.compare(x.hasNext(),y.hasNext());}
}
