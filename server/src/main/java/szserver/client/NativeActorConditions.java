package szserver.client;

import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import szserver.world.ActorControllerEngine;

/** Original condition properties and handler lifecycle; called inside a map transaction. */
final class NativeActorConditions {
    private final Method knockdown,writeProperty,currentHandler,enterLogout,leaveLogout;
    private final Field active;
    private final Method status,leaveFlight;private final Field flight;
    NativeActorConditions(Path runtime,ClassLoader loader)throws Exception{
        for(var entry:Map.ofEntries(Map.entry("208724657","05c64ef2793ef4bad14cf83e2004e3a6fb6aa3844b76eec043c6cef5fb322ca1"),Map.entry("1382005406","40895683a915568167e5cb21f636088600a6a11e747d690933d03ab9de1552f3"),Map.entry("1252319110","3640061a9b2c2a66f3ec04d7fbf84238b327c677503a785ccf3d674e915b43c5"),Map.entry("1966713080","ffa01dd93d9a2c6e6dcc4829d464069313406cd43954d313aa3a2f63ef0105ef"),Map.entry("1704556748","45db02249b76fc459139305fa8b06649d3be2ac3119bf9fc2ba153ba49904d58"),Map.entry("430277821","f10d63fc7159e9a2dd82d363ee14c546ca8a244828c74ccdaac58102b2ebe6ff"),Map.entry("1798539107","f6600d5d1e29f4d96acf525c1c4e348f7de5b1f867f0dcd3cf5b7fbfe3c7f3e5")).entrySet())
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(entry.getKey()+".class")))).equals(entry.getValue()))throw new IllegalStateException("actor_condition_release_mismatch:"+entry.getKey());
        Class<?> player=Class.forName("200241649",true,loader),component=Class.forName("208724657",true,loader),property=Class.forName("1798539107",true,loader),controller=Class.forName("1704556748",true,loader),handler=Class.forName("1382005406",true,loader);
        knockdown=component.getMethod("char",player);active=component.getField("implements");writeProperty=property.getMethod("assert",Object.class);
        currentHandler=controller.getMethod("super");enterLogout=handler.getMethod("package");leaveLogout=Class.forName("1252319110",true,loader).getMethod("new");
        Class<?> state=Class.forName("1409908300",true,loader);status=state.getMethod("protected",player);flight=state.getField("transient");leaveFlight=controller.getMethod("throw");
    }
    void apply(Object controller,Object actor,ActorControllerEngine.Condition request)throws Exception{
        if(request==null)return;
        if(request.kind()==ActorControllerEngine.ConditionKind.KNOCKDOWN){writeProperty.invoke(active.get(knockdown.invoke(null,actor)),request.enabled());return;}
        if(request.kind()==ActorControllerEngine.ConditionKind.FLY){writeProperty.invoke(flight.get(status.invoke(null,actor)),request.enabled());if(!request.enabled())leaveFlight.invoke(controller);return;}
        Object handler=currentHandler.invoke(controller);boolean loggedOut=handler.getClass().getName().equals("1252319110");
        if(request.enabled()&&!loggedOut)enterLogout.invoke(handler);
        else if(!request.enabled()&&loggedOut)leaveLogout.invoke(handler);
    }
}
