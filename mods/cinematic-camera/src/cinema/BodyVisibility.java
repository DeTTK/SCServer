package cinema;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import imgui.*;
import imgui.type.ImBoolean;
/** Local render filter. No shared model/animation data is modified. */
final class BodyVisibility {
    static String group;static CinematicCamera owner;
    static final Properties hidden=new Properties();static final Set<String> seen=new TreeSet<>();static Path file;
    static void load(CinematicCamera c)throws IOException {
        Path next=c.context.configDirectory().resolve("visibility.properties");if(next.equals(file))return;
        file=next;hidden.clear();seen.clear();if(Files.exists(file))try(var r=Files.newBufferedReader(file)){hidden.load(r);}
    }
    static boolean show(String key){return !Boolean.parseBoolean(hidden.getProperty(key,"false"));}
    static void set(String key,boolean show)throws IOException {
        if(show)hidden.remove(key);else hidden.setProperty(key,"true");
        Path tmp=file.resolveSibling("visibility.properties.tmp");try(var w=Files.newBufferedWriter(tmp)){hidden.store(w,"Cinematic camera mesh visibility");}Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
    }
    static Object visible(CinematicCamera c,Object mesh)throws Exception {
        if(owner!=c||group==null)return null;
        if(mesh==null)return Boolean.FALSE;
        // The derived mesh class also has an int field called char; use its
        // native base class explicitly to read the String name.
        String name=(String)Native.type("1639883046").getDeclaredField("char").get(Native.get(mesh,"short")),key=group+" / "+name;
        seen.add(key);return show(group)&&show(key)?null:Boolean.FALSE;
    }
    static void draw(CinematicCamera c)throws Exception {
        load(c);ImGui.separator();ImGui.text("Видимость FP моделей");
        for(String g:List.of("Руки","Рукава брони","Оружие")){ImBoolean b=new ImBoolean(show(g));if(ImGui.checkbox(g+"###visibility-"+g,b))set(g,b.get());}
        if(ImGui.treeNode("Отдельные части модели")){
            if(seen.isEmpty())ImGui.textWrapped("Список появится после отрисовки FP моделей в мире.");
            for(String key:new ArrayList<>(seen)){ImBoolean b=new ImBoolean(show(key));if(ImGui.checkbox(key,b))set(key,b.get());}
            if(ImGui.button("Показать все части")){hidden.clear();set("__refresh__",true);}
            ImGui.treePop();
        }
    }
    static void scope(CinematicCamera c,String category,Object renderer,String method,Object... args)throws Exception {
        if(!show(category))return;
        owner=c;group=category;
        Runnable restore=null;
        try{restore=BoneVisibility.mask(args[args.length-1],category);Native.call(renderer,method,"void",args);}finally{try{if(restore!=null)restore.run();}finally{owner=null;group=null;}}
    }
}
