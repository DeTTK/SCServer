package environment;

import com.google.gson.JsonObject;
import environment.protocol.EnvironmentWire;
import imgui.ImGui;
import imgui.type.ImBoolean;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Map coverage in the Regions tab, in the native editor's world coordinates. */
public final class WeatherChunkOverlay {
    private static final ImBoolean visible=new ImBoolean(false);
    private static final int COLOR=0x759de650;
    private static Map<Long,BitSet> loading=new HashMap<>(),regions=Map.of();
    private static int page,pages;
    private static long nextRequest;

    private WeatherChunkOverlay(){}

    static void reset(){
        loading.clear();regions=Map.of();page=0;pages=0;nextRequest=0;
    }

    static void receive(JsonObject message)throws IOException{
        int index=message.get("index").getAsInt(),total=message.get("pages").getAsInt();
        if(index==0){loading.clear();page=0;pages=total;}
        int perPage=EnvironmentWire.PAGE_BYTES/EnvironmentWire.COVERAGE_REGION_BYTES;
        if(total<1||total>(EnvironmentWire.MAX_COVERAGE_REGIONS+perPage-1)/perPage||total!=pages||index!=page||index>=total)
            throw new IOException("weather_chunk_coverage_sequence");
        byte[] data=Base64.getDecoder().decode(message.get("data").getAsString());
        if(data.length>EnvironmentWire.PAGE_BYTES||data.length%EnvironmentWire.COVERAGE_REGION_BYTES!=0)
            throw new IOException("weather_chunk_coverage_size");
        var in=ByteBuffer.wrap(data);
        while(in.hasRemaining()){
            long key=in.getLong();var bits=new byte[128];in.get(bits);
            if(loading.putIfAbsent(key,BitSet.valueOf(bits))!=null||loading.size()>EnvironmentWire.MAX_COVERAGE_REGIONS)
                throw new IOException("weather_chunk_coverage_region");
        }
        if(++page==pages){regions=Map.copyOf(loading);loading.clear();}
    }

    /** Called inside the native Regions controls. */
    public static void controls(){
        if(!EnvironmentBridge.chunkCoverageAvailable())return;
        ImGui.checkbox("Чанки на сервере",visible);
        if(visible.get()&&System.nanoTime()>=nextRequest){
            nextRequest=System.nanoTime()+15_000_000_000L;
            EnvironmentBridge.requestChunkCoverage();
        }
    }

    /** Called after the native map image. Keep the map as the last interactive ImGui item. */
    public static void draw(float centerX,float centerZ,float blocksPerPixel,int width,int height,float left,float top){
        if(!visible.get()||!EnvironmentBridge.chunkCoverageAvailable()||regions.isEmpty()
                ||!Float.isFinite(blocksPerPixel)||blocksPerPixel<=0||width<=0||height<=0)return;
        double pixels=16.0/blocksPerPixel;
        double x0=left+width*.5-centerX/blocksPerPixel,z0=top+height*.5-centerZ/blocksPerPixel;
        float right=left+width,bottom=top+height;
        var draw=ImGui.getWindowDrawList();
        draw.pushClipRect(left,top,right,bottom,true);
        try{
            for(var region:regions.entrySet()){
                double x=x0+(int)(region.getKey()>>32)*32.0*pixels;
                double z=z0+(int)(long)region.getKey()*32.0*pixels;
                if(x>=right||z>=bottom||x+32*pixels<=left||z+32*pixels<=top)continue;
                var mask=region.getValue();
                for(int bit=mask.nextSetBit(0);bit>=0;){
                    int end=Math.min(mask.nextClearBit(bit),(bit/32+1)*32);
                    double y=z+bit/32*pixels;
                    if(y+pixels>top&&y<bottom)
                        draw.addRectFilled((float)(x+(bit%32)*pixels),(float)y,
                                (float)(x+(end-bit+bit%32)*pixels),(float)(y+pixels),COLOR);
                    bit=mask.nextSetBit(end);
                }
            }
        }finally{draw.popClipRect();}
    }
}
