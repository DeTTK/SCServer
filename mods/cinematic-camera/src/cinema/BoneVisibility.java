package cinema;
import java.util.*;
import imgui.*;
import imgui.type.ImBoolean;
import static cinema.Native.*;
/** Native per-bone render visibility, restored before the next animation tick. */
final class BoneVisibility {
    static Object tpPose;
    static void track(CinematicCamera c){try{Native.Bones b=Native.bones(c.player,0);tpPose=b==null?null:b.pose();}catch(Exception unavailable){tpPose=null;}}
    static boolean hideTp(CinematicCamera c,Object component)throws Exception{
        if(!c.detachedFp())return false;
        BodyVisibility.load(c);
        // Both primary TP and secondary FP body components inherit this owner field.
        // Resolve it on its declaring class: subclasses also have a static 'assert'.
        boolean hide=!BodyVisibility.show("TP тело персонажа")&&component!=null&&type("1078236163").getDeclaredField("assert").get(component)==(c.replayFp?c.subject:c.player);
        if(hide&&tpPose!=component){tpPose=component;c.context.log("Local actor body, clothes and attachments hidden in FP scene");}
        return hide;
    }
    static String part(String name){
        String n=name.toLowerCase(Locale.ROOT).replace(" ","");
        if(n.contains("head")||n.contains("neck"))return "Голова";
        boolean left=n.contains("left")||n.endsWith("_l"),right=n.contains("right")||n.endsWith("_r");
        if(n.contains("arm")||n.contains("hand")||n.contains("shoulder"))return left?"Левая рука":right?"Правая рука":null;
        if(n.contains("leg")||n.contains("thigh")||n.contains("calf")||n.contains("foot"))return left?"Левая нога":right?"Правая нога":null;
        if(n.contains("spine")||n.contains("chest")||n.contains("pelvis"))return "Торс";
        return null;
    }
    static void draw(CinematicCamera c)throws Exception{
        ImBoolean tp=new ImBoolean(BodyVisibility.show("TP тело персонажа"));if(ImGui.checkbox("Тело персонажа / броня",tp))BodyVisibility.set("TP тело персонажа",tp.get());
        if(ImGui.treeNode("Части тела и ветви скелета")){
            for(String p:List.of("Голова","Торс","Левая рука","Правая рука","Левая нога","Правая нога")){ImBoolean b=new ImBoolean(BodyVisibility.show("part:"+p));if(ImGui.checkbox(p,b))BodyVisibility.set("part:"+p,b.get());}
            if(ImGui.treeNode("Скрыть ветвь по кости")){
                for(Native.Bones bones:new Native.Bones[]{c.bones,c.otherFpBones})if(bones!=null)for(String name:bones.names()){
                    String key="bone:"+name;ImBoolean b=new ImBoolean(BodyVisibility.show(key));if(ImGui.checkbox(name+"###hide-"+name+"-"+System.identityHashCode(bones.skeleton()),b))BodyVisibility.set(key,b.get());
                }
                ImGui.treePop();
            }ImGui.treePop();
        }
    }
    static Runnable mask(Object context,String category)throws Exception{
        Object data=call(get(context,"double"),"byte","java.lang.Object");
        String accessor=category.equals("Руки")?"instanceof":category.equals("Рукава брони")?"for":"interface";
        Object rig=call(data,accessor,"387875964");if(rig==null)return ()->{};
        Object skeleton=call(rig,"strictfp","1612003613"),pose=call(rig,"extends","1118769848");
        float[] alpha=(float[])get(pose,"strictfp"),saved=alpha.clone();
        try { for(Object bone:(Object[])get(skeleton,"case")){
            boolean visible=true;Object ancestor=bone;
            Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
            while(ancestor!=null&&visited.add(ancestor)){
                String name=(String)get(ancestor,"this"),part=part(name);
                if(!BodyVisibility.show("bone:"+name)||(part!=null&&!BodyVisibility.show("part:"+part))){visible=false;break;}
                ancestor=get(ancestor,"native");
            }
            int index=((Number)get(bone,"const")).intValue();if(!visible&&index>=0&&index<alpha.length)alpha[index]=0;
        }}catch(Exception failure){System.arraycopy(saved,0,alpha,0,saved.length);throw failure;}
        return ()->System.arraycopy(saved,0,alpha,0,saved.length);
    }
}
