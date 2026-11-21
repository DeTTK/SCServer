package szserver.net;

import com.google.gson.*;
import playerscale.*;
import java.util.*;

/** Server-owned character setting; shared dimensions and versioned transport are pure data. */
public final class CharacterScale {
    public static final String SETTING="character_scale_v1";
    private CharacterScale(){}
    public record Command(String target,ScaleValue scale){}
    public static Command command(String text){
        List<String> words=CommandSupport.words(text);if(words.size()<2||words.size()>3||!words.get(0).equalsIgnoreCase("/scale"))throw new IllegalArgumentException("/scale [\"ник\"] <0.25…4|reset>");
        String raw=words.get(words.size()-1);float factor;
        try{factor=raw.equalsIgnoreCase("reset")?1:Float.parseFloat(raw);}catch(NumberFormatException error){throw new IllegalArgumentException("character_scale_range");}
        return new Command(words.size()==3?words.get(1):null,new ScaleValue(factor));
    }
    public static ScaleValue saved(JsonObject state){
        // A damaged optional setting must not make a character impossible to enter.
        try{return state.has(SETTING)?new ScaleValue(state.get(SETTING).getAsFloat()):ScaleValue.NORMAL;}
        catch(IllegalArgumentException|UnsupportedOperationException|IllegalStateException bad){return ScaleValue.NORMAL;}
    }
    public static JsonObject progress(ScaleValue size){JsonObject state=new JsonObject();state.addProperty(SETTING,size.factor());return state;}
    public static byte[] frame(int entity,long revision,ScaleValue size){return Wire.dynamic(ScaleProtocol.SNAPSHOT,new ScaleProtocol.Snapshot(ScaleProtocol.SET,entity,revision,size).encode());}
    public static byte[] remove(int entity,long revision){return Wire.dynamic(ScaleProtocol.SNAPSHOT,new ScaleProtocol.Snapshot(ScaleProtocol.REMOVE,entity,revision,ScaleValue.NORMAL).encode());}
    public static double height(int posture,float factor){return (double)NativeDimensions.height(posture)*new ScaleValue(factor).factor();}
    public static double radius(float factor){return .3*new ScaleValue(factor).factor();}
    /** Graph speed is measured in character lengths; movement and damage keep their world-space velocity. */
    public static double animationSpeed(double worldSpeed,float factor){
        if(!Double.isFinite(worldSpeed)||worldSpeed<0)throw new IllegalArgumentException("character_animation_speed");
        return worldSpeed/new ScaleValue(factor).factor();
    }
    public static double[] eye(double[] position,int posture,float factor){return new ScaleValue(factor).point(position,new double[]{0,NativeDimensions.eye(posture),0});}
    public static double[] closestPoint(double[] eye,double[] position,int posture,float factor){
        double radius=radius(factor);return new double[]{Math.max(position[0]-radius,Math.min(position[0]+radius,eye[0])),Math.max(position[1],Math.min(position[1]+height(posture,factor),eye[1])),Math.max(position[2]-radius,Math.min(position[2]+radius,eye[2]))};
    }
}
