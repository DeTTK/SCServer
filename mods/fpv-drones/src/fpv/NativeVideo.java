package fpv;
import java.lang.reflect.*;
import java.util.Locale;
import static fpv.NativeFlight.call;
import static fpv.NativeFlight.get;

final class NativeVideo {
    private Object master;
    Object photo(String profile)throws Exception {
        return Class.forName("1058277441").getField(profile.equals("Аналоговый")||profile.equals("Ночная камера")?"LOFI":profile.equals("Цифровой")?"CCTV":"CLEAN").get(null);
    }
    void night(Object parameters)throws Exception {
        if(master==null) master=findMaster();
        // Apply the native NVD configuration to this frame's postprocessing.
        // Player equipment, its activation flag and photo-mode state stay owned by the game.
        call(call(parameters,"new","153304200"),"synchronized","153304200",get(master,"private"));
        call(parameters,"for","void",(Double)call(parameters,"this","double")+.5);
        String[] target={"abstract","new","switch","break","native","static","assert","long","enum"};
        String[] source={"strictfp","switch","super","do","protected","transient","assert","void","final"};
        for(int i=0;i<target.length;i++)call(parameters,target[i],"void",get(master,source[i]));
        call(parameters,"public","void",Math.max((Double)get(master,"volatile"),(Double)call(parameters,"extends","double")));
    }
    private Object findMaster()throws Exception {
        Object factories=Class.forName("1366800141").getField("import").get(null);
        Object presets=Class.forName("1576730323").getField("char").get(null);
        Method title=Class.forName("1366800141").getMethod("continue",Class.forName("211097523"));
        for(int id=0;id<Array.getLength(factories);id++) {
            Object factory=Array.get(factories,id);if(factory==null)continue;
            Object config=call(presets,"double","1758300595",id);if(config==null)continue;
            Object rich=title.invoke(factory,new Object[]{null});
            String name=((String)call(rich,"throws","java.lang.String")).toLowerCase(Locale.ROOT);
            Object rank=call(factory,"default","1148454204",null,null);
            if(((Enum<?>)rank).name().equals("RANK_MASTER")&&(name.contains("пнв")||name.contains("night vision")||name.contains("nvg"))
                    &&(name.contains("бел")||name.contains("white"))) {
                Object value=Class.forName("1081228723").getConstructor().newInstance();
                call(value,"int","void",config);
                System.err.println("[FPV NVD] Selected master preset "+id+": "+name);
                return value;
            }
        }
        throw new IllegalStateException("White master NVD was not found in the active item registry");
    }
}
