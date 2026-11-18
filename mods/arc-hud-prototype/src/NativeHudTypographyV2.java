import java.util.*;
import com.google.gson.*;

/** One-time fitting against the native font, after the native GUI resolves its cells.
 * Native Text widgets retain responsibility for horizontal and vertical alignment.
 */
public final class NativeHudTypographyV2 {
    private static Object call(Object o,String name,String result,Object...args)throws Exception {
        return HudNative.call(o,name,result,args);
    }
    private static Object get(Object o,String name)throws Exception {
        return HudNative.get(o,name);
    }
    public static JsonArray prepare(Object gui)throws Exception {
        JsonArray report=new JsonArray();
        for(Object widget:((Map<?,?>)get(gui,"continue")).values()) {
            if(!widget.getClass().getName().equals("1554818945"))continue;
            String text=(String)get(widget,"return");
            if(text==null||text.isEmpty())continue;
            Object node=call(widget,"else","1287567536");
            Object style=call(node,"new","695281613");
            double size=((Number)call(style,"this","java.lang.Double")).doubleValue();
            Object cell=call(widget,"switch","61438557");
            int width=cell.getClass().getField("try").getInt(cell);
            int height=cell.getClass().getField("enum").getInt(cell);
            String[] lines=text.split("\\n",-1);
            double measured=0,lineHeight=0;
            for(int iteration=0;iteration<6;iteration++) {
                Object font=call(get(widget,"class"),"native","186969209");
                measured=0;
                for(String line:lines)measured=Math.max(measured,((Number)call(font,"boolean","int",line)).doubleValue());
                lineHeight=((Number)call(font,"continue","double")).doubleValue();
                double textHeight=lines.length*lineHeight+Math.max(0,lines.length-1)*4;
                double ratio=Math.min(1,(width-.5)/Math.max(1,measured));
                if(lines.length>1)ratio=Math.min(ratio,height/Math.max(1,textHeight));
                if(ratio>=.999)break;
                size*=Math.max(.5,ratio*.985);
                call(widget,"native","void",size);
            }
            if(measured>width+.5)throw new IllegalStateException("Native label exceeds its cell: "+text);
            JsonObject entry=new JsonObject();
            entry.addProperty("id",get(node,"int").toString());
            entry.addProperty("text",text);
            entry.addProperty("fontSize",size);
            entry.addProperty("nativeTextWidth",measured);
            entry.addProperty("cellWidth",width);
            entry.addProperty("cellHeight",height);
            entry.addProperty("nativeLineHeight",lineHeight);
            report.add(entry);
        }
        return report;
    }
}
