package replay;

import java.util.*;

/** Source progress clip on the promoted client's existing animation controller. */
final class SourceExtractorAnimation {
    static boolean apply(Object controller,String name,SourceExtractorState.Phase phase,float progress)throws Exception {
        if(phase!=SourceExtractorState.Phase.EXTRACTING&&phase!=SourceExtractorState.Phase.FINISHED)return false;
        Object layer=null;for(Object wrapper:(List<?>)Native.call(controller,"new","java.util.List")){Object candidate=Native.field(wrapper,"float");if(Native.type("510119352").isInstance(candidate)&&name.equals(Native.field(candidate,"short"))){layer=candidate;break;}}
        if(phase==SourceExtractorState.Phase.EXTRACTING){
            if(layer==null){layer=Native.type("510119352").getConstructor(String.class).newInstance(name);Native.call(layer,"szFp_do","510119352",0f);Native.call(layer,"class","510119352",Enum.valueOf((Class)Native.type("125158170"),"CLAMP"));Native.call(layer,"szFp_finally","510119352",progress);Object wrapper=Native.type("1254265885").getConstructor(Native.type("1809954121"),Native.type("1391506621")).newInstance(Native.field(Native.type("292512748"),"float"),layer);Native.call(controller,"public","void",wrapper);}
            else Native.call(layer,"szFp_finally","510119352",progress);return true;
        }
        if(phase==SourceExtractorState.Phase.FINISHED&&layer!=null){Native.call(layer,"szFp_finally","510119352",1f);return true;}return false;
    }
}
