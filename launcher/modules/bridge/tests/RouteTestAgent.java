package bscp.next;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
/** Installs only the production account transformer for the asset-free backend test. */
public final class RouteTestAgent {
    public static void premain(String args,Instrumentation instrumentation){
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(!"szserver/net/LocalAccounts".equals(name))return null;
                try{return ServerPatches.transformClass(name,bytes,false);}catch(Exception failure){throw new IllegalStateException(failure);}
            }
        });
    }
}
