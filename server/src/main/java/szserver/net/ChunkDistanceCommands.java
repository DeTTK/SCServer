package szserver.net;

import java.util.*;

/** Python split()/decimal int command compatibility, including Unicode spaces and digits. */
public final class ChunkDistanceCommands {
    private ChunkDistanceCommands(){}
    private static List<String> words(String text){
        List<String> parts=new ArrayList<>();int start=-1;
        for(int at=0;at<text.length();){int cp=text.codePointAt(at),next=at+Character.charCount(cp);boolean space=Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0x85;
            if(space){if(start>=0){parts.add(text.substring(start,at));start=-1;}}else if(start<0)start=at;at=next;
        }
        if(start>=0)parts.add(text.substring(start));return parts;
    }
    public static boolean matches(String text){var parts=words(text);return !parts.isEmpty()&&parts.get(0).equalsIgnoreCase("/chunkdistance");}
    public static int parse(String text){
        var parts=words(text);if(parts.isEmpty()||!parts.get(0).equalsIgnoreCase("/chunkdistance"))throw new IllegalArgumentException("not_chunk_distance_command");
        if(parts.size()!=2)throw new IllegalArgumentException("usage_chunkdistance_radius");String number=parts.get(1);int[] cps=number.codePoints().toArray();int at=0,sign=1;
        if(cps.length>0&&(cps[0]=='+'||cps[0]=='-')){sign=cps[0]=='-'?-1:1;at++;}
        long value=0;boolean digit=false,underscore=false;
        for(;at<cps.length;at++){int cp=cps[at];if(cp=='_'&&digit&&!underscore){underscore=true;continue;}
            int d=Character.digit(cp,10);if(d<0||Character.getType(cp)!=Character.DECIMAL_DIGIT_NUMBER)throw new IllegalArgumentException("invalid_chunk_distance");
            value=Math.min((long)ChunkWindow.MAX_RADIUS+1,value*10+d);digit=true;underscore=false;
        }
        if(!digit||underscore)throw new IllegalArgumentException("invalid_chunk_distance");
        if(sign<0||value<1||value>ChunkWindow.MAX_RADIUS)throw new IllegalArgumentException("chunk_distance_out_of_bounds");return (int)value;
    }
}
