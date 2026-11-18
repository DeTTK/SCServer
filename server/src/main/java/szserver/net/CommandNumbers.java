package szserver.net;
import java.math.BigInteger;
/** Python decimal int syntax; validate magnitude before narrowing a wire/domain integer. */
public final class CommandNumbers {
    private CommandNumbers(){}
    public static BigInteger integer(String raw,String error){
        String value=AccountIdentity.strip(raw);if(value.length()>16384)throw new IllegalArgumentException(error);StringBuilder number=new StringBuilder();boolean previous=false;int at=0;
        if(!value.isEmpty()&&(value.charAt(0)=='+'||value.charAt(0)=='-')){number.append(value.charAt(0));at=1;}
        for(;at<value.length();){int cp=value.codePointAt(at);at+=Character.charCount(cp);if(cp=='_'){if(!previous||at==value.length())throw new IllegalArgumentException(error);previous=false;continue;}
            int digit=Character.getType(cp)==Character.DECIMAL_DIGIT_NUMBER?Character.digit(cp,10):-1;if(digit<0)throw new IllegalArgumentException(error);number.append((char)('0'+digit));previous=true;}
        if(!previous)throw new IllegalArgumentException(error);return new BigInteger(number.toString());
    }
    public static int bounded(String value,int minimum,int maximum,String error){BigInteger integer=integer(value,error);if(integer.compareTo(BigInteger.valueOf(minimum))<0||integer.compareTo(BigInteger.valueOf(maximum))>0)throw new IllegalArgumentException(error);return integer.intValue();}
}
