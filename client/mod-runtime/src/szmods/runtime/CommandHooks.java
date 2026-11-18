package szmods.runtime;
import java.io.*;
/** Optional command-completion mod bridge; no replacement when the mod is disabled. */
public final class CommandHooks {
    public static byte[] read(DataInput in)throws IOException {
        int n=in.readInt();if(n<0||n>65536)throw new IOException("Invalid command catalog length");
        byte[] data=new byte[n];in.readFully(data);return data;
    }
    public static void receive(Object connection,byte[] data){ModLoader.clientHook("chat.catalog.v1",connection,data);}
    public static boolean key(Object chat,int key){return Boolean.TRUE.equals(ModLoader.clientHook("chat.key.v1",chat,key));}
    public static void render(Object chat){ModLoader.clientHook("chat.render.v1",chat);}
    public static boolean inventoryTab(boolean original){return Boolean.TRUE.equals(ModLoader.clientHook("chat.replace-tab.v1"))?false:original;}
}
