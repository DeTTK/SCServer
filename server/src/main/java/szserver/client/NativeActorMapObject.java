package szserver.client;

import szserver.world.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;

/** Real scene objects in the controller loader. Native readers run only on workers. */
final class NativeActorMapObject {
    record Prepared(Object value,int x,int z,List<ActorSeat> seats,ActorCarouselState carousel,Object carouselOwned){}
    private final NativeActorSeats seats;
    private final ClassLoader loader;private final Object unsafe;private final Method allocate,read;
    private final Class<?> objectType,factoryType,matrixType;private final Constructor<?> matrixCopy;
    private final Field matrix;private final Field[] objectFields;
    NativeActorMapObject(ClassLoader loader,Object unsafe,Method allocate,java.util.function.BiFunction<String,byte[],Class<?>> define)throws Exception{
        this.loader=loader;this.unsafe=unsafe;this.allocate=allocate;
        seats=new NativeActorSeats(loader);
        objectType=type("1886271463");factoryType=type("1814125595");matrixType=type("447252483");
        matrix=objectType.getDeclaredField("m");matrix.setAccessible(true);matrixCopy=matrixType.getConstructor(type("1303032651"));
        read=objectType.getMethod("char",DataInput.class);
        Class<?> carousel=define.apply("szserver.bridge.ActorCarousel",NativeActorBridge.carousel());factoryType.getMethod("protected",String.class,Class.class).invoke(null,"carousel",carousel);
        for(var entry:Map.of("zipline","1122096288","zipline_vertical","829855000","seat","24366313","obstacle_living","958782514").entrySet())factoryType.getMethod("protected",String.class,Class.class).invoke(null,entry.getKey(),type(entry.getValue()));
        var fields=new ArrayList<Field>();for(Class<?> c=objectType;c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);fields.add(f);}objectFields=fields.toArray(Field[]::new);
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private Method objectBehavior()throws Exception{Class<?> expected=type("1079929180");return Arrays.stream(objectType.getMethods()).filter(m->m.getName().equals("private")&&m.getParameterCount()==0&&!Modifier.isStatic(m.getModifiers())&&m.getReturnType()==expected).findFirst().orElseThrow(()->new NoSuchMethodException("1886271463.private()L1079929180;"));}
    ActorContacts.Obstacle livingObstacle(SceneRegistry.Definition definition,SceneObject source)throws Exception {
        SceneCollision.worker();if(!definition.behaviour().equals("obstacle_living")||source.networkId()!=definition.id()||!source.asset().equals(definition.name())||source.recordSize()>512*1024)throw new IllegalArgumentException("actor_living_obstacle_source");
        Object parsed=type("com.google.gson.JsonParser").getMethod("parseString",String.class).invoke(null,definition.definition().toString());Object properties=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        Object descriptor=type("1422486348").getConstructor(String.class,type("com.google.gson.JsonObject"),int.class,String.class,Integer.class,String.class).newInstance(definition.name(),properties,definition.id(),null,null,null);
        Object factory=factoryType.getConstructor(String.class,type("1669874697"),boolean.class).newInstance(definition.name(),null,false);factoryType.getField("void").set(factory,descriptor);factoryType.getField("volatile").set(factory,"obstacle_living");
        Object object=objectType.getConstructor(UUID.class,factoryType).newInstance(source.identity(),factory);byte[] bytes=source.record();var input=new BoundedNativeInput(Arrays.copyOfRange(bytes,2,bytes.length));read.invoke(object,input);if(input.available()!=0)throw new IOException("actor_living_obstacle_trailing");
        Object behavior=objectBehavior().invoke(object);if(!type("958782514").isInstance(behavior))throw new IOException("actor_living_obstacle_behavior");
        Class<?> living=type("1562480994");float health=living.getField("abstract").getFloat(behavior),maximum=living.getField("if").getFloat(behavior);Object effect=type("958782514").getField("public").get(behavior);
        return new ActorContacts.Obstacle(type("612654711").getField("public").getFloat(effect),new ActorContacts.Living(health,maximum));
    }
    Prepared prepare(SceneRegistry.Definition definition,SceneObject source)throws Exception{
        SceneCollision.worker();if(!Set.of("zipline","zipline_vertical","seat","carousel").contains(definition.behaviour())||source.networkId()!=definition.id()||!source.asset().equals(definition.name()))throw new IllegalArgumentException("actor_map_object_kind");
        Object parsed=type("com.google.gson.JsonParser").getMethod("parseString",String.class).invoke(null,definition.definition().toString());
        Object properties=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        Object descriptor=type("1422486348").getConstructor(String.class,type("com.google.gson.JsonObject"),int.class,String.class,Integer.class,String.class).newInstance(definition.name(),properties,definition.id(),null,null,null);
        Object factory=factoryType.getConstructor(String.class,type("1669874697"),boolean.class).newInstance(definition.name(),null,false);
        factoryType.getField("void").set(factory,descriptor);factoryType.getField("volatile").set(factory,definition.behaviour());
        int x=(int)Math.floor(source.x()/16)*16,z=(int)Math.floor(source.z()/16)*16;byte[] body=source.record();int at=SceneObject.prefix(body).transformAt();ByteBuffer.wrap(body).putFloat(at+36,source.x()-x).putFloat(at+44,source.z()-z);
        Object object=objectType.getConstructor(UUID.class,factoryType).newInstance(source.identity(),factory);
        var input=new BoundedNativeInput(Arrays.copyOfRange(body,2,body.length));read.invoke(object,input);if(input.available()!=0)throw new IOException("actor_map_object_trailing");
        boolean carousel=definition.behaviour().equals("carousel");
        Object actualBehavior=objectBehavior().invoke(object);
        // A FULL_SAVE instance may explicitly select the empty behavior name. The
        // original reader then creates generic 1079929180, even for a seat asset.
        var prefix=SceneObject.prefix(source.record());
        if(actualBehavior!=null&&actualBehavior.getClass()==type("1079929180")&&(prefix.flags()&0x200)!=0&&Arrays.equals(prefix.nativeName(),new byte[]{0,0}))return null;
        if(!type("789923706").isInstance(actualBehavior)&&!seats.isSeat(object)&&!carousel)throw new IOException("actor_map_object_behavior:"+definition.name()+":"+definition.behaviour()+":"+(actualBehavior==null?"null":actualBehavior.getClass().getName()));
        Object owned=null;ActorCarouselState captureSource=null;
        if(carousel){captureSource=new ActorCarouselState(source.asset(),source.networkId(),definition.profile(),definition.definition().toString(),Base64.getEncoder().encodeToString(source.record()),-1);owned=objectType.getConstructor(UUID.class,factoryType).newInstance(source.identity(),factory);var again=new BoundedNativeInput(Arrays.copyOfRange(body,2,body.length));read.invoke(owned,again);if(again.available()!=0)throw new IOException("actor_carousel_trailing");}
        return new Prepared(object,x,z,carousel?List.of():seats.units(object,source.identity(),x,z),captureSource,owned);
    }
    private Object path(Prepared prepared,UUID identity)throws Exception{
        SceneCollision.worker();Object forest=allocate.invoke(unsafe,type("szserver.bridge.ActorForest"));
        Field registry=type("493563411").getDeclaredField("import");registry.setAccessible(true);registry.set(forest,Map.of(identity,prepared.value()));
        Object world=allocate.invoke(unsafe,type("szserver.bridge.ActorWorld"));world.getClass().getField("forest").set(world,forest);
        Object actor=allocate.invoke(unsafe,type("szserver.bridge.ActorSpeedProfile"));Field worldField=type("1217489680").getDeclaredField("interface");worldField.setAccessible(true);worldField.set(actor,world);
        Object module=type("1600012472").getConstructor().newInstance();module.getClass().getField("abstract").set(module,actor);
        Object path=module.getClass().getMethod("try",UUID.class).invoke(module,identity);if(path==null)throw new IOException("actor_scene_cable_missing");
        return path;
    }
    private World.Vec point(Object vector,Prepared prepared)throws Exception{return new World.Vec(vector.getClass().getField("case").getDouble(vector)+prepared.x(),vector.getClass().getField("void").getDouble(vector),vector.getClass().getField("import").getDouble(vector)+prepared.z());}
    ActorZipline guide(Object value)throws Exception{
        SceneCollision.worker();var prepared=(Prepared)value;UUID identity=(UUID)objectType.getMethod("const").invoke(prepared.value());Object cable=path(prepared,identity),behavior=objectBehavior().invoke(prepared.value());
        String direction=((Enum<?>)type("789923706").getMethod("void").invoke(behavior)).name();
        boolean vertical=cable.getClass().getField("static").getBoolean(cable);Class<?> constants=type("1827030006");double bodyOffset=constants.getField(vertical?"interface":"extends").getFloat(null);
        return new ActorZipline(identity,point(cable.getClass().getField("default").get(cable),prepared),point(cable.getClass().getField("char").get(cable),prepared),!direction.equals("TO_ORIGIN"),!direction.equals("FROM_ORIGIN"),vertical,bodyOffset,cable.getClass().getField("const").getDouble(cable));
    }
    SceneCollision.Box bounds(Prepared prepared,UUID identity)throws Exception{
        if(prepared.carousel()!=null){var source=new SceneObject(prepared.carousel().asset(),Base64.getDecoder().decode(prepared.carousel().record()));double radius=source.scale()*6+2;return new SceneCollision.Box(source.x()-radius,source.y()-radius,source.z()-radius,source.x()+radius,source.y()+radius,source.z()+radius);}
        if(!prepared.seats().isEmpty()){double x=Double.POSITIVE_INFINITY,y=x,z=x,xx=-x,yy=-x,zz=-x;for(var unit:prepared.seats()){var p=unit.point();x=Math.min(x,p.x());y=Math.min(y,p.y());z=Math.min(z,p.z());xx=Math.max(xx,p.x());yy=Math.max(yy,p.y());zz=Math.max(zz,p.z());}return new SceneCollision.Box(x-4,y-4,z-4,xx+4,yy+4,zz+4);}
        Object path=path(prepared,identity);
        Class<?> cable=path.getClass();Object first=cable.getField("default").get(path),last=cable.getField("char").get(path);
        double x0=first.getClass().getField("case").getDouble(first)+prepared.x(),x1=last.getClass().getField("case").getDouble(last)+prepared.x();
        double y0=first.getClass().getField("void").getDouble(first),y1=last.getClass().getField("void").getDouble(last);
        double z0=first.getClass().getField("import").getDouble(first)+prepared.z(),z1=last.getClass().getField("import").getDouble(last)+prepared.z();
        // Conservative hull of the original cubic's controls and bounded sag term.
        // This only selects authored sources; the original controller executes the curve.
        double half=0,sag=0,margin=Math.max(4,Math.abs(cable.getField("const").getDouble(path)));
        if(!cable.getField("static").getBoolean(path)){
            double distance=Math.sqrt((x1-x0)*(x1-x0)+(y1-y0)*(y1-y0)+(z1-z0)*(z1-z0))+.001;
            double length=cable.getField("int").getDouble(path),rigidity=cable.getField("switch").getDouble(path);
            half=Math.abs(Math.max(distance,length)*rigidity*.5);sag=Math.abs(cable.getField("protected").getDouble(path)*(1-rigidity)*Math.max(0,length-distance));
        }
        return new SceneCollision.Box(Math.min(x0,x1)-half-margin,Math.min(y0,y1)-sag-margin,Math.min(z0,z1)-half-margin,Math.max(x0,x1)+half+margin,Math.max(y0,y1)+sag+margin,Math.max(z0,z1)+half+margin);
    }
    /** Query-private transform; immutable behavior/settings retain their worker-created object. */
    List<ActorSeat> seatGuides(Object value){return ((Prepared)value).seats();}
    Object seatAction(Object prepared,ActorControllerEngine.Seat request,ActorTraversal.Seat current,World.Vec actor,Object world,int x,int z,ActorWorldAccess access)throws Exception{return seats.action(prepared==null?List.of():((Prepared)prepared).seats(),request,current,actor,world,x,z,access);}
    Object local(Object prepared,int x,int z)throws Exception{
        var source=(Prepared)prepared;Object copy=allocate.invoke(unsafe,objectType);
        for(var field:objectFields)field.set(copy,field.get(source.value()));
        Object transform=matrixCopy.newInstance(matrix.get(source.value()));
        // Original synchronized(FFF) writes public/void/this and invalidates matrix flags.
        float tx=matrixType.getField("public").getFloat(transform),ty=matrixType.getField("void").getFloat(transform),tz=matrixType.getField("this").getFloat(transform);
        matrixType.getMethod("synchronized",float.class,float.class,float.class).invoke(transform,tx+source.x()-x,ty,tz+source.z()-z);
        matrix.set(copy,transform);return copy;
    }
}
