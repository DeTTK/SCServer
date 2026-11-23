package szserver.client;

import szserver.net.*;

/** Native local-player sickness component update, without fabricated medicine effects. */
public final class SicknessPackets {
    public static final String UPDATE="1608929782";
    public static void verify(java.nio.file.Path root,java.nio.file.Path runtime)throws Exception{
        var m=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("fixtures/native-sickness.json"))).getAsJsonObject();for(var e:m.getAsJsonObject("classHashes").entrySet()){var p=runtime.resolve("classes").resolve(e.getKey()+".class");if(!e.getKey().matches("[0-9]+")||!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(p))).equals(e.getValue().getAsString()))throw new java.io.IOException("native_sickness_release_changed");}
        var p=root.resolve("fixtures/native-resources/assets/stalker/sickness.json");if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(p))).equals(m.get("resourceSha256").getAsString()))throw new java.io.IOException("native_sickness_resource_changed");
    }
    public static byte[] body(SicknessRules.Profile profile,SicknessRules.State state,float incoming,int penetration){
        return body(profile,state,incoming,incoming,penetration);
    }
    public static byte[] body(SicknessRules.Profile profile,SicknessRules.State state,float filtered,float incoming,int penetration){if(state.power()>profile.maximum()||!Float.isFinite(incoming)||!Float.isFinite(filtered)||filtered<0||incoming<0||penetration<0)throw new IllegalArgumentException("sickness_packet");return Wire.bytes(out->{out.writeUTF(profile.id());out.writeFloat(state.power());out.writeFloat(filtered);out.writeFloat(incoming);out.writeInt(penetration);out.writeInt(state.resetLeft());});}
    public static byte[] update(SicknessRules.Profile p,SicknessRules.State s,float incoming,int penetration){return Wire.dynamic(UPDATE,body(p,s,incoming,penetration));}
    private SicknessPackets(){}
}
