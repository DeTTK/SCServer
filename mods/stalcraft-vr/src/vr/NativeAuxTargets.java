package vr;

import java.lang.reflect.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;
import static vr.NativeAccess.*;

/** Own smaller images for an auxiliary view, preserving native wrapper identities and aliases. */
final class NativeAuxTargets implements AutoCloseable {
    private final IdentityHashMap<Object,Image> images=new IdentityHashMap<>();
    private final IdentityHashMap<Object,Image> buffers=new IdentityHashMap<>();
    private final IdentityHashMap<Object,Image> framebuffers=new IdentityHashMap<>();
    private final AuxHistory history=new AuxHistory();
    private final Map<Integer,Output> outputs=new HashMap<>();
    record Output(int framebuffer,int[] viewport,List<Integer> draws){}
    Output originalOutput(int owned){return outputs.get(owned);}
    private boolean entered;
    private int viewWidth,viewHeight;
    boolean verifyRestoration;
    int restoredStates;
    String description="not entered";
    int passes;
    int fallbacks;
    record Storage(int target,int width,int height,int format,int samples,boolean fixed,List<Integer> parameters){}
    record Image(int original,int width,int height,int owned,Storage storage){}
    private record Original(boolean renderbuffer,int id,Storage storage){}
    private record Framebuffer(int id,List<Integer> attachments,List<Integer> draws,int read,int status){}
    private static final int[] PARAMETERS={GL_TEXTURE_MIN_FILTER,GL_TEXTURE_MAG_FILTER,GL_TEXTURE_WRAP_S,GL_TEXTURE_WRAP_T,GL_TEXTURE_COMPARE_MODE,GL_TEXTURE_COMPARE_FUNC,GL_TEXTURE_BASE_LEVEL,GL_TEXTURE_MAX_LEVEL};
    static Storage textureStorage(int id,int target){
        int binding=target==GL_TEXTURE_2D_MULTISAMPLE?GL_TEXTURE_BINDING_2D_MULTISAMPLE:GL_TEXTURE_BINDING_2D;
        int previous=glGetInteger(binding);try{glBindTexture(target,id);
            List<Integer> parameters=new ArrayList<>();if(target==GL_TEXTURE_2D)for(int p:PARAMETERS)parameters.add(glGetTexParameteri(target,p));
            return new Storage(target,glGetTexLevelParameteri(target,0,GL_TEXTURE_WIDTH),glGetTexLevelParameteri(target,0,GL_TEXTURE_HEIGHT),glGetTexLevelParameteri(target,0,GL_TEXTURE_INTERNAL_FORMAT),
                target==GL_TEXTURE_2D_MULTISAMPLE?glGetTexLevelParameteri(target,0,GL_TEXTURE_SAMPLES):0,target!=GL_TEXTURE_2D_MULTISAMPLE||glGetTexLevelParameteri(target,0,GL_TEXTURE_FIXED_SAMPLE_LOCATIONS)!=0,List.copyOf(parameters));
        }finally{glBindTexture(target,previous);}
    }
    static Storage bufferStorage(int id){
        int previous=glGetInteger(GL_RENDERBUFFER_BINDING);try{glBindRenderbuffer(GL_RENDERBUFFER,id);return new Storage(GL_RENDERBUFFER,
            glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_WIDTH),glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_HEIGHT),glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_INTERNAL_FORMAT),glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_SAMPLES),true,List.of());
        }finally{glBindRenderbuffer(GL_RENDERBUFFER,previous);}
    }
    private record Value(Object receiver,Field field,Object value) {
        void restore()throws IllegalAccessException {field.set(receiver,value);}
    }
    private static void snapshotMetadata(Graph graph,List<Value> saved)throws Exception {
        Set<Object> arrays=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object object:graph.seen){
            String name=object.getClass().getName();
            // Pool slots carry allocation/release bookkeeping updated by draw.
            // Rolling those counters back leaves current-frame textures marked
            // busy while the pool's available lists have already advanced.
            if(!name.equals("105717861")&&!name.equals("1183745312")&&!name.equals("390236344"))continue;
            for(Class<?> c=object.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())for(Field field:c.getDeclaredFields()){
                int flags=field.getModifiers();if(Modifier.isStatic(flags))continue;field.setAccessible(true);Object value=field.get(object);
                if(!Modifier.isFinal(flags))saved.add(new Value(object,field,value));
                if(value!=null&&value.getClass().isArray()&&arrays.add(value)){
                    int n=Array.getLength(value);Object copy=Array.newInstance(value.getClass().getComponentType(),n);System.arraycopy(value,0,copy,0,n);saved.add(new Value(value,null,copy));
                }
            }
        }
    }
    private static boolean same(Value value)throws Exception {
        if(value.field()!=null){Object actual=value.field().get(value.receiver());return value.field().getType().isPrimitive()?Objects.equals(actual,value.value()):actual==value.value();}
        int n=Array.getLength(value.value());if(Array.getLength(value.receiver())!=n)return false;
        for(int i=0;i<n;i++)if(!Objects.equals(Array.get(value.receiver(),i),Array.get(value.value(),i)))return false;return true;
    }
    private static final class Graph {
        final Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Object> targets=new ArrayList<>(),textures=new ArrayList<>(),renderbuffers=new ArrayList<>();
        void visit(Object object)throws Exception {
            if(object==null||!seen.add(object))return;
            if(object instanceof Object[] array){for(Object item:array)visit(item);return;}
            if(object instanceof Iterable<?> list){for(Object item:list)visit(item);return;}
            String name=object.getClass().getName();
            if(name.equals("1183745312")){textures.add(object);return;}
            if(name.equals("390236344")){renderbuffers.add(object);return;}
            boolean target=name.equals("105717861");
            if(target)targets.add(object);
            if(!target&&!name.equals("1561067009")&&!name.equals("787627046")&&!name.equals("1232038730")&&!name.equals("241511502")&&!name.equals("221705413")&&!name.equals("60626231")&&!type("555212254").isInstance(object))return;
            for(Class<?> c=object.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())for(Field field:c.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive())continue;
                field.setAccessible(true);visit(field.get(object));
            }
        }
    }
    private static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void replace(List<Value> saved,Object receiver,String name,Object value)throws Exception {
        Class<?> type=receiver instanceof Class<?> c?c:receiver.getClass();Field f=field(type,name);Object instance=receiver instanceof Class<?>?null:receiver;
        saved.add(new Value(instance,f,f.get(instance)));f.set(instance,value);
    }
    /** Returns null for unsupported native multisample/renderbuffer layouts. */
    AutoCloseable enter(Object renderer,int width,int height)throws Exception {
        if(entered)throw new IllegalStateException("Nested auxiliary target scope");
        outputs.clear();
        int previousError=glGetError();if(previousError!=GL_NO_ERROR)throw new IllegalStateException("OpenGL error before auxiliary target setup: 0x"+Integer.toHexString(previousError));
        Class<?> manager=type("449323257"),dimensions=type("989099940");
        int originalWidth=((Number)get(dimensions,"implements")).intValue(),originalHeight=((Number)get(dimensions,"enum")).intValue();
        if(originalWidth<1||originalHeight<1)return null;
        int maximumTexture=glGetInteger(GL_MAX_TEXTURE_SIZE),maximumBuffer=glGetInteger(GL_MAX_RENDERBUFFER_SIZE);int[] maximumViewport=new int[2];glGetIntegerv(GL_MAX_VIEWPORT_DIMS,maximumViewport);
        if(width<1||height<1||width>maximumViewport[0]||height>maximumViewport[1]){fallbacks++;description="full native view: requested viewport exceeds GPU limits";return null;}
        Graph graph=new Graph();for(Class<?> root:new Class<?>[]{manager,type("1565336263"),type("1228927706")})for(Field f:root.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&!f.getType().isPrimitive()){f.setAccessible(true);graph.visit(f.get(null));}
        graph.visit(get(renderer,"transient"));
        Object scene=call(call(type("1402131154"),"try","1402131154"),"implements","1376962110");graph.visit(get(scene,"short"));graph.visit(get(scene,"transient"));
        Object specials=get(type("1311607083"),"short");if(specials!=null){Object refractive=get(specials,"super");if(refractive!=null)graph.visit(get(refractive,"short"));}
        // This prototype only owns 2D native texture attachments. Preserve full
        // native rendering if any required target uses an unknown layout.
        for(Object target:graph.targets) {
            Object depth=get(target,"enum");if(depth!=null&&!type("1183745312").isInstance(depth)&&!type("390236344").isInstance(depth)){description="unsupported depth "+get(target,"const")+" "+depth.getClass().getName();return null;}
            for(Object image:(Object[])get(target,"double"))if(image!=null&&!type("1183745312").isInstance(image)&&!type("390236344").isInstance(image)){description="unsupported colour "+get(target,"const")+" "+image.getClass().getName();return null;}
        }
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D),oldMs=glGetInteger(GL_TEXTURE_BINDING_2D_MULTISAMPLE),oldBuffer=glGetInteger(GL_RENDERBUFFER_BINDING);
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);List<Value> saved=new ArrayList<>();
        entered=true;
        try {
            // A native pool may retain a lazily bound attachment which is no
            // longer represented by the current Java image graph, especially
            // during resize. Check every target BEFORE changing any wrapper.
            // The full native view can then rebuild its attachments normally.
            Set<Integer> availableTextures=new HashSet<>(),availableBuffers=new HashSet<>();
            for(Object texture:graph.textures){int target=((Number)call(texture,"enum","int")).intValue();if(target==GL_TEXTURE_2D||target==GL_TEXTURE_2D_MULTISAMPLE)availableTextures.add(((Number)get(texture,"static")).intValue());}
            for(Object buffer:graph.renderbuffers)availableBuffers.add(((Number)get(buffer,"enum")).intValue());
            IdentityHashMap<Object,Framebuffer> layouts=new IdentityHashMap<>();
            for(Object target:graph.targets){int id=((Number)get(target,"catch")).intValue();if(id<=0)continue;Framebuffer layout=framebuffer(id);layouts.put(target,layout);
                String missing=missingAttachment(layout,availableTextures,availableBuffers);if(missing!=null){entered=false;fallbacks++;description="full native view: "+get(target,"const")+" "+missing;return null;}
            }
            IdentityHashMap<Object,Storage> imageLayouts=new IdentityHashMap<>(),bufferLayouts=new IdentityHashMap<>();
            for(Object texture:graph.textures){int id=((Number)get(texture,"static")).intValue(),target=((Number)call(texture,"enum","int")).intValue();if(id<=0||target!=GL_TEXTURE_2D&&target!=GL_TEXTURE_2D_MULTISAMPLE)continue;
                Storage storage=textureStorage(id,target);imageLayouts.put(texture,storage);if(storage.width()<1||storage.height()<1||!AuxHistory.supported(storage)){entered=false;fallbacks++;description="full native view: unsupported image history "+storage;return null;}
            }
            for(Object buffer:graph.renderbuffers){int id=((Number)get(buffer,"enum")).intValue();if(id<=0)continue;Storage storage=bufferStorage(id);bufferLayouts.put(buffer,storage);
                if(storage.width()<1||storage.height()<1||!AuxHistory.supported(storage)){entered=false;fallbacks++;description="full native view: unsupported buffer history "+storage;return null;}
            }
            for(Object image:graph.textures)if(imageLayouts.containsKey(image)){
                double w=(double)((Number)get(image,"default")).intValue()*width/originalWidth,h=(double)((Number)get(image,"case")).intValue()*height/originalHeight;
                if(w>maximumTexture||h>maximumTexture){entered=false;fallbacks++;description="full native view: scaled texture exceeds GPU limits";return null;}
            }
            for(Object image:graph.renderbuffers)if(bufferLayouts.containsKey(image)){
                double w=(double)((Number)get(image,"new")).intValue()*width/originalWidth,h=(double)((Number)get(image,"abstract")).intValue()*height/originalHeight;
                if(w>maximumBuffer||h>maximumBuffer){entered=false;fallbacks++;description="full native view: scaled renderbuffer exceeds GPU limits";return null;}
            }
            // Native target cursors, attachment arrays and history-slot metadata
            // can change during a draw even when dimensions remain unchanged.
            snapshotMetadata(graph,saved);
            int snapshotCount=saved.size();
            // Allocate everything before changing a single native object.
            IdentityHashMap<Object,Image> planned=new IdentityHashMap<>();List<Original> originals=new ArrayList<>();List<Framebuffer> originalFramebuffers=new ArrayList<>();
            for(Object texture:graph.textures) {
                int id=((Number)get(texture,"static")).intValue();if(id<=0)continue;
                int textureTarget=((Number)call(texture,"enum","int")).intValue();if(textureTarget!=GL_TEXTURE_2D&&textureTarget!=GL_TEXTURE_2D_MULTISAMPLE)continue;
                int w=((Number)get(texture,"default")).intValue(),h=((Number)get(texture,"case")).intValue();if(w<1||h<1)continue;
                int nw=Math.max(1,(int)Math.round((double)w*width/originalWidth)),nh=Math.max(1,(int)Math.round((double)h*height/originalHeight));
                Storage storage=imageLayouts.get(texture);if(storage==null)continue;
                originals.add(new Original(false,id,storage));Image owned=images.get(texture);
                if(owned==null||owned.original()!=id||owned.width()!=nw||owned.height()!=nh||!owned.storage().equals(storage)) {
                    int copy=cloneImage(nw,nh,storage);try{history.copy(id,copy,false,storage,nw,nh);}catch(RuntimeException|Error error){glDeleteTextures(copy);throw error;}if(owned!=null)glDeleteTextures(owned.owned());
                    owned=new Image(id,nw,nh,copy,storage);images.put(texture,owned);
                }
                planned.put(texture,owned);
            }
            IdentityHashMap<Object,Image> plannedBuffers=new IdentityHashMap<>();
            for(Object buffer:graph.renderbuffers) {
                int id=((Number)get(buffer,"enum")).intValue();if(id<=0)continue;
                int w=((Number)get(buffer,"new")).intValue(),h=((Number)get(buffer,"abstract")).intValue();if(w<1||h<1)continue;
                int nw=Math.max(1,(int)Math.round((double)w*width/originalWidth)),nh=Math.max(1,(int)Math.round((double)h*height/originalHeight));
                Storage storage=bufferLayouts.get(buffer);if(storage==null)continue;
                originals.add(new Original(true,id,storage));Image owned=buffers.get(buffer);if(owned==null||owned.original()!=id||owned.width()!=nw||owned.height()!=nh||!owned.storage().equals(storage)) {
                    int copy=glGenRenderbuffers();glBindRenderbuffer(GL_RENDERBUFFER,copy);glRenderbufferStorageMultisample(GL_RENDERBUFFER,storage.samples(),storage.format(),nw,nh);
                    if(glGetError()!=GL_NO_ERROR){glDeleteRenderbuffers(copy);throw new IllegalStateException("Auxiliary renderbuffer allocation");}
                    try{history.copy(id,copy,true,storage,nw,nh);}catch(RuntimeException|Error error){glDeleteRenderbuffers(copy);throw error;}
                    if(owned!=null)glDeleteRenderbuffers(owned.owned());owned=new Image(id,nw,nh,copy,storage);buffers.put(buffer,owned);
                }
                plannedBuffers.put(buffer,owned);
            }
            replace(saved,dimensions,"implements",width);replace(saved,dimensions,"enum",height);
            replace(saved,dimensions,"private",width);replace(saved,dimensions,"finally",height);replace(saved,dimensions,"try",1f);
            replace(saved,manager,"const",false);
            for(var entry:planned.entrySet()) {
                Object texture=entry.getKey();Image image=entry.getValue();replace(saved,texture,"static",image.owned());
                replace(saved,texture,"default",image.width());replace(saved,texture,"case",image.height());
                replace(saved,texture,"const",type("61438557").getConstructor(int.class,int.class).newInstance(image.width(),image.height()));
            }
            for(var entry:plannedBuffers.entrySet()) {
                Object buffer=entry.getKey();Image image=entry.getValue();replace(saved,buffer,"enum",image.owned());
                replace(saved,buffer,"new",image.width());replace(saved,buffer,"abstract",image.height());
                replace(saved,buffer,"private",type("61438557").getConstructor(int.class,int.class).newInstance(image.width(),image.height()));
            }
            for(Object target:graph.targets) {
                int w=((Number)get(target,"public")).intValue(),h=((Number)get(target,"native")).intValue();if(w<1||h<1)continue;
                int nw=Math.max(1,(int)Math.round((double)w*width/originalWidth)),nh=Math.max(1,(int)Math.round((double)h*height/originalHeight));
                int id=((Number)get(target,"catch")).intValue();if(id>0) {
                    Image owned=framebuffers.get(target);if(owned==null||owned.original()!=id){if(owned!=null)glDeleteFramebuffers(owned.owned());owned=new Image(id,nw,nh,glGenFramebuffers(),null);framebuffers.put(target,owned);}
                    // Native attachments can rotate or change storage without
                    // changing the framebuffer wrapper or its original GL id.
                    Framebuffer layout=layouts.get(target);originalFramebuffers.add(layout);try{seedFramebuffer(layout,owned.owned(),planned,plannedBuffers);}catch(RuntimeException error){throw new IllegalStateException("Auxiliary native target "+get(target,"const")+" original="+id+" status="+layout.status(),error);}
                    replace(saved,target,"catch",owned.owned());
                }
                replace(saved,target,"public",nw);replace(saved,target,"native",nh);
                replace(saved,target,"break",type("61438557").getConstructor(int.class,int.class).newInstance(nw,nh));
                int[] viewport=(int[])get(target,"else");int[] original=viewport.clone();saved.add(new Value(viewport,null,original));viewport[0]=viewport[1]=0;viewport[2]=nw;viewport[3]=nh;
                if(id>0)outputs.put(framebuffers.get(target).owned(),new Output(id,original,layouts.get(target).draws()));
            }
            viewWidth=width;viewHeight=height;passes++;description="owned "+width+"x"+height+" images="+planned.size()+" buffers="+plannedBuffers.size()+" framebuffers="+graph.targets.size()+" histories="+history.copies;return ()->{restore(saved);if(verifyRestoration){
                int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int originalRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),originalDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                try{for(int i=0;i<snapshotCount;i++)if(!same(saved.get(i)))throw new IllegalStateException("Auxiliary target metadata changed: "+saved.get(i).field());
                    for(Original original:originals){Storage actual=original.renderbuffer()?bufferStorage(original.id()):textureStorage(original.id(),original.storage().target());if(!actual.equals(original.storage()))throw new IllegalStateException("Original GPU image storage changed: "+original.id()+" expected="+original.storage()+" actual="+actual);}
                    for(Framebuffer original:originalFramebuffers)if(!original.equals(framebuffer(original.id())))throw new IllegalStateException("Original GPU framebuffer changed: "+original.id());restoredStates++;
                }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,originalRead);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,originalDraw);glActiveTexture(active);}
            }};
        }catch(Exception|Error error){try{restore(saved);}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
        finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindTexture(GL_TEXTURE_2D,oldTexture);glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,oldMs);glBindRenderbuffer(GL_RENDERBUFFER,oldBuffer);glActiveTexture(oldActive);set(type("105717861"),"throws",null);}
    }
    private void restore(List<Value> saved)throws Exception {
        Exception failure=null;
        for(int i=saved.size()-1;i>=0;i--)try {
            Value v=saved.get(i);if(v.field()==null)System.arraycopy(v.value(),0,v.receiver(),0,Array.getLength(v.value()));else v.restore();
        }catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        entered=false;set(type("105717861"),"throws",null);
        if(failure!=null)throw failure;
    }
    void verifyOutput(int[] viewport) {
        if(!entered)return;
        if(viewport[2]!=viewWidth||viewport[3]!=viewHeight)throw new IllegalStateException("Auxiliary viewport differs: "+Arrays.toString(viewport));
        if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_TEXTURE)throw new IllegalStateException("Auxiliary output has no native texture");
        int id=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(images.values().stream().noneMatch(image->image.owned()==id))throw new IllegalStateException("Auxiliary output still uses original native image "+id);
        int old=glGetInteger(GL_TEXTURE_BINDING_2D);try{glBindTexture(GL_TEXTURE_2D,id);
            if(glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH)!=viewWidth||glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT)!=viewHeight)throw new IllegalStateException("Auxiliary native texture storage differs");
        }finally{glBindTexture(GL_TEXTURE_2D,old);}
        description+=" output="+id+" storage="+viewWidth+"x"+viewHeight;
    }
    static int cloneImage(int width,int height,Storage storage) {
        int target=storage.target(),format=storage.format();
        if(target==GL_TEXTURE_2D_MULTISAMPLE) {
            int copy=glGenTextures();glBindTexture(target,copy);glTexImage2DMultisample(target,storage.samples(),format,width,height,storage.fixed());
            if(glGetError()!=GL_NO_ERROR){glDeleteTextures(copy);throw new IllegalStateException("Auxiliary multisample image allocation");}return copy;
        }
        int result=glGenTextures(),unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
            glBindTexture(GL_TEXTURE_2D,result);boolean stencil=format==GL_DEPTH24_STENCIL8||format==GL_DEPTH32F_STENCIL8;
            boolean depth=stencil||format==GL_DEPTH_COMPONENT16||format==GL_DEPTH_COMPONENT24||format==GL_DEPTH_COMPONENT32||format==GL_DEPTH_COMPONENT32F||format==GL_DEPTH_COMPONENT;
            int colour=AuxHistory.colourKind(format);
            glTexImage2D(GL_TEXTURE_2D,0,format,width,height,0,stencil?GL_DEPTH_STENCIL:depth?GL_DEPTH_COMPONENT:colour!=0?GL_RGBA_INTEGER:GL_RGBA,format==GL_DEPTH32F_STENCIL8?GL_FLOAT_32_UNSIGNED_INT_24_8_REV:stencil?GL_UNSIGNED_INT_24_8:colour==1?GL_INT:colour==2?GL_UNSIGNED_INT:GL_FLOAT,0L);
            for(int i=0;i<PARAMETERS.length;i++)glTexParameteri(GL_TEXTURE_2D,PARAMETERS[i],storage.parameters().get(i));
            if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Auxiliary image allocation, internal format="+format);
            return result;
        }catch(RuntimeException|Error error){glDeleteTextures(result);throw error;}finally{glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);}
    }
    private static Framebuffer framebuffer(int id){
        glBindFramebuffer(GL_FRAMEBUFFER,id);int status=glCheckFramebufferStatus(GL_FRAMEBUFFER),maximum=glGetInteger(GL_MAX_COLOR_ATTACHMENTS);List<Integer> attachments=new ArrayList<>(),draws=new ArrayList<>();
        for(int i=0;i<maximum;i++)draws.add(glGetInteger(GL_DRAW_BUFFER0+i));
        for(int i=0;i<maximum+2;i++){int a=i<maximum?GL_COLOR_ATTACHMENT0+i:i==maximum?GL_DEPTH_ATTACHMENT:GL_STENCIL_ATTACHMENT;
            int kind=glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,a,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE),image=0,level=0,face=0;
            if(kind!=GL_NONE){image=glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,a,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);if(kind==GL_TEXTURE){level=glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,a,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);face=glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,a,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);}}
            attachments.addAll(List.of(a,kind,image,level,face));
        }return new Framebuffer(id,List.copyOf(attachments),List.copyOf(draws),glGetInteger(GL_READ_BUFFER),status);
    }
    private static String missingAttachment(Framebuffer layout,Set<Integer> textures,Set<Integer> buffers){
        for(int i=0;i<layout.attachments().size();i+=5){int kind=layout.attachments().get(i+1),id=layout.attachments().get(i+2);
            if(kind==GL_TEXTURE&&!textures.contains(id)||kind==GL_RENDERBUFFER&&!buffers.contains(id))return "unrepresented GPU attachment "+id;
        }return null;
    }
    static boolean mappedFramebuffer(int id,Set<Integer> textures,Set<Integer> buffers){
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try{return missingAttachment(framebuffer(id),textures,buffers)==null;}finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);}
    }
    static void seedFramebuffer(int original,int owned,Map<Object,Image> textures,Map<Object,Image> buffers){seedFramebuffer(framebuffer(original),owned,textures,buffers);}
    private static void seedFramebuffer(Framebuffer original,int owned,Map<Object,Image> textures,Map<Object,Image> buffers){
        Map<Integer,Integer> textureIds=new HashMap<>(),textureTargets=new HashMap<>(),bufferIds=new HashMap<>();for(Image image:textures.values()){textureIds.put(image.original(),image.owned());textureTargets.put(image.original(),image.storage().target());}for(Image image:buffers.values())bufferIds.put(image.original(),image.owned());
        glBindFramebuffer(GL_FRAMEBUFFER,owned);
        for(int i=0;i<original.attachments().size();i+=5) {
            int attachment=original.attachments().get(i),kind=original.attachments().get(i+1),source=original.attachments().get(i+2),level=original.attachments().get(i+3),face=original.attachments().get(i+4);
            if(kind==GL_TEXTURE){Integer id=textureIds.get(source);if(id==null)throw new IllegalStateException("Unowned native framebuffer texture "+source+" valid="+glIsTexture(source)+" cachedOwned="+textureIds.containsValue(source)+" originalStatus="+original.status()+" known="+textureIds);glFramebufferTexture2D(GL_FRAMEBUFFER,attachment,face==0?textureTargets.get(source):face,id,level);}
            else if(kind==GL_RENDERBUFFER){Integer id=bufferIds.get(source);if(id==null)throw new IllegalStateException("Unowned native renderbuffer "+source);glFramebufferRenderbuffer(GL_FRAMEBUFFER,attachment,GL_RENDERBUFFER,id);}
            else glFramebufferRenderbuffer(GL_FRAMEBUFFER,attachment,GL_RENDERBUFFER,0);
        }
        int count=1;for(int i=0;i<original.draws().size();i++)if(original.draws().get(i)!=GL_NONE)count=i+1;int[] draws=new int[count];for(int i=0;i<count;i++)draws[i]=original.draws().get(i);glDrawBuffers(draws);glReadBuffer(original.read());
        int error=glGetError(),status=glCheckFramebufferStatus(GL_FRAMEBUFFER);if(error!=GL_NO_ERROR||original.status()==GL_FRAMEBUFFER_COMPLETE&&status!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Owned native framebuffer is incomplete: original="+original.id()+" error="+error+" status="+status+" originalStatus="+original.status());
    }
    public void close(){if(entered)throw new IllegalStateException("Auxiliary target scope still active");outputs.clear();for(Image image:images.values())glDeleteTextures(image.owned());images.clear();for(Image image:buffers.values())glDeleteRenderbuffers(image.owned());buffers.clear();for(Image image:framebuffers.values())glDeleteFramebuffers(image.owned());framebuffers.clear();history.close();}
}
