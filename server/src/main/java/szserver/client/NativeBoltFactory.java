package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import szserver.world.World;
import szserver.net.GrenadeUse;

/** Executes the installed bolt factory against a detached player and exports its native projectile. */
final class NativeBoltFactory {
    private final NativeItemFactories host;
    private final Object player, motion, thrower, world;
    private final Set<Integer> boltIds;
    private final Class<?> factoryType, projectileType, propertiesType;
    private final Constructor<?> readStack, angles, readActor;
    private final Method create, writeProperties, readProjectile, readId, aim, vx, vy, vz;
    private final Field lifetime, item, modifier, presentation, collision, breakOnContact, auxiliary, properties, motionX, motionZ;
    private final int actorType;
    private final Map<Integer,GrenadeUse.Rules> rules;

    NativeBoltFactory(NativeItemFactories host, BiFunction<String, byte[], Class<?>> define) throws Exception {
        this.host = host;
        factoryType = host.type("942610364");
        var ids = new TreeSet<Integer>();
        for (var entry : host.classes.entrySet())
            if (factoryType.isAssignableFrom(host.type(entry.getValue())) && host.definitions.containsKey(entry.getKey()))
                ids.add(entry.getKey());
        boltIds = Set.copyOf(ids);
        Class<?> registry = host.type("423259911");
        projectileType = host.type("1734232513");
        Method registered = registry.getMethod("abstract",Class.class);
        if ((Byte)registered.invoke(null,projectileType)==0) registry.getMethod("assert").invoke(null);
        actorType = Byte.toUnsignedInt((Byte)registered.invoke(null,projectileType));
        if (actorType == 0) throw new IOException("native_bolt_actor_unregistered");
        Class<?> metadata = host.type("1993742576");
        if (metadata.getField("it").get(null) == null) {
            host.type("600030218").getField("volatile").set(null,host.type("1974661934").getField("RU").get(null));
            metadata.getMethod("interface").invoke(null);
        }
        Class<?> profile = host.type("267674355");
        propertiesType = host.type("824243370");
        properties = profile.getField("static");
        lifetime = profile.getField("new");
        item = profile.getField("volatile");
        presentation = profile.getField("final");
        modifier = projectileType.getField("double");
        auxiliary = projectileType.getField("for");
        collision = host.type("939250848").getField("new");
        breakOnContact = host.type("939250848").getField("short");
        writeProperties = propertiesType.getMethod("switch", DataOutput.class);
        readProjectile = projectileType.getMethod("default", DataInput.class);
        readStack = host.stack.getConstructor(DataInput.class);
        readId = NativeMethods.instance(host.stack,"continue",int.class);
        var contextType = host.type("358035993");
        var playerType = host.type("200241649");
        var controllerType = host.type("1704556748");
        var motionType = host.type("1943200621");
        var useType = host.type("1761650403");
        var unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        world = allocate.invoke(unsafe, define.apply("szserver.bridge.ActorWorld", NativeActorBridge.world()));
        set(host.type("1500514786"),"volatile",world,host.type("740754547").getMethod("instanceof",int.class).invoke(null,0));
        set(host.type("1500514786"),"for",world,new Random());
        player = allocate.invoke(unsafe, host.type("961798934"));
        set(host.type("1217489680"),"interface",player,world);
        Class<?> entity = host.type("1217489680");
        Object anchor = entity.getConstructor(host.type("1500514786")).newInstance(world);
        entity.getMethod("throw",double.class,double.class,double.class).invoke(anchor,0d,0d,0d);
        Field coordinates = entity.getDeclaredField("enum");
        coordinates.setAccessible(true);
        coordinates.set(player,coordinates.get(anchor));
        Object context = allocate.invoke(unsafe, contextType);
        Object controller = allocate.invoke(unsafe, controllerType);
        motion = motionType.getConstructor().newInstance();
        set(controllerType,"assert",controller,motion);
        String[] names = (String[])host.type("1281655698").getField("if").get(null);
        set(contextType,"void",context,new HashMap<>(Map.of(names[21233],controller)));
        set(playerType,"protected",player,context);
        thrower = useType.getConstructor(host.type("351062380")).newInstance(player);
        create = NativeMethods.instance(factoryType,"import",host.type("939250848"),useType,host.stack);
        motionX = motionType.getField("av");
        motionZ = motionType.getField("ax");
        Class<?> living = host.type("351062380");
        Class<?> angleType = host.type("1893560513");
        angles = angleType.getConstructor(float.class,float.class);
        aim = profile.getMethod("private",living,angleType);
        readActor = projectileType.getConstructor(host.type("1500514786"));
        vx = NativeMethods.instance(profile,"transient",double.class);
        vy = NativeMethods.instance(profile,"short",double.class);
        vz = NativeMethods.instance(profile,"do",double.class);
        Map<Integer,GrenadeUse.Rules> installed = new HashMap<>();
        Method prepare = NativeMethods.instance(factoryType,"char",int.class);
        Method charge = NativeMethods.instance(factoryType,"new",int.class);
        for (int id : boltIds) {
            Object factory=host.factory(id);
            installed.put(id,new GrenadeUse.Rules((Integer)prepare.invoke(factory),
                    (Integer)charge.invoke(factory),0,0,false,true));
            forStack(host.stack.getConstructor(int.class,int.class,int.class)
                    .newInstance(id,1,0),0,0,0,0);
        }
        rules=Map.copyOf(installed);
    }

    private static void set(Class<?> type, String field, Object owner, Object value) throws Exception {
        Field member = type.getDeclaredField(field);
        member.setAccessible(true);
        member.set(owner,value);
    }

    Map<Integer,GrenadeUse.Rules> rules() {return rules;}

    BoltProjectile launch(byte[] raw, double velocityX, double velocityZ, float yaw, float pitch) throws Exception {
        return forStack(readStack.newInstance(new BoundedNativeInput(raw)),velocityX,velocityZ,yaw,pitch);
    }

    private BoltProjectile forStack(Object stack, double velocityX, double velocityZ, float yaw, float pitch) throws Exception {
        int id = (Integer)readId.invoke(stack);
        if (!boltIds.contains(id)) throw new IOException("native_bolt_item");
        Object factory = host.factory(id);
        return host.bound(() -> {
            motionX.setDouble(motion, velocityX);
            motionZ.setDouble(motion, velocityZ);
            Object projectile = create.invoke(factory,thrower,stack);
            if (!projectileType.isInstance(projectile) || item.getInt(projectile) != id)
                throw new IOException("native_bolt_actor_type");
            aim.invoke(projectile,player,angles.newInstance(yaw,pitch));
            var velocity = new World.Vec((Double)vx.invoke(projectile)*20,
                    (Double)vy.invoke(projectile)*20,(Double)vz.invoke(projectile)*20);
            Object flight = properties.get(projectile);
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(id);
            out.writeBoolean(presentation.getBoolean(projectile));
            writeProperties.invoke(flight,out);
            out.writeBoolean(collision.getBoolean(projectile));
            out.writeShort(modifier.getInt(projectile));
            out.writeBoolean(auxiliary.getBoolean(projectile));
            byte[] bytes=buffer.toByteArray();
            Object decoded=readActor.newInstance(world);
            var input=new DataInputStream(new ByteArrayInputStream(bytes));
            readProjectile.invoke(decoded,input);
            if(input.available()!=0||item.getInt(decoded)!=id||modifier.getInt(decoded)!=modifier.getInt(projectile)
                    || auxiliary.getBoolean(decoded)!=auxiliary.getBoolean(projectile)
                    || collision.getBoolean(decoded)!=collision.getBoolean(projectile))throw new IOException("native_bolt_wire");
            return new BoltProjectile(actorType,lifetime.getInt(projectile),scalar(flight,"static")*20,
                    -scalar(flight,"throws")*400,scalar(flight,"strictfp"),scalar(flight,"throw"),
                    scalar(flight,"abstract"),breakOnContact.getBoolean(projectile),velocity,bytes);
        });
    }

    private float scalar(Object profile, String field) throws Exception {
        return propertiesType.getField(field).getFloat(profile);
    }
}
