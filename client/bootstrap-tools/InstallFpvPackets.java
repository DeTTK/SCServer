import java.nio.file.*;
import org.objectweb.asm.*;

/** Native dynamic envelope supplies registry ID 5; no global packet IDs change. */
public final class InstallFpvPackets implements Opcodes {
    public static void main(String[] args) throws Exception {
        String name="szmods/bridge/FpvInput";
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17,ACC_PUBLIC|ACC_FINAL,name,null,"499058674",null);
        w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","([B)V",null,null);
        m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,name,"payload","[B");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"switch","(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});
        m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,name,"payload","[B");
        m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);
        m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);m.visitMaxs(0,0);m.visitEnd();
        w.visitEnd();
        Path out=Path.of(args[0]).resolve(name+".class");Files.createDirectories(out.getParent());Files.write(out,w.toByteArray());
        snapshot(Path.of(args[0]));
        animation(Path.of(args[0]));
        medicine(Path.of(args[0]));
        vrWeapon(Path.of(args[0]),"VrWeapon",false);
        vrWeapon(Path.of(args[0]),"VrWeaponReply",true);
        vrWeapon(Path.of(args[0]),"VrBolt",false);
    }
    static void vrWeapon(Path root,String packet,boolean reply)throws Exception {
        String n="szmods/bridge/"+packet;
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);
        w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>",reply?"()V":"([B)V",null,null);m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);
        if(!reply){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}
        m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,reply?"extends":"switch",reply?"(Ljava/io/DataInput;)V":"(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});m.visitCode();
        if(reply){m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/VrPackets","read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,n,"payload","[B");}
        else {m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);}
        m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        if(reply){m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/VrPackets","receive","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);}
        else {m=w.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);}
        m.visitMaxs(0,0);m.visitEnd();w.visitEnd();Path file=root.resolve(n+".class");Files.createDirectories(file.getParent());Files.write(file,w.toByteArray());
    }
    static void animation(Path root) throws Exception {
        String n="szmods/bridge/PlayerAnimation";
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);
        w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null); m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"extends","(Ljava/io/DataInput;)V",null,new String[]{"java/io/IOException"});m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/AnimationHooks","read","(Ljava/io/DataInput;)[B",false);m.visitFieldInsn(PUTFIELD,n,"payload","[B");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/FpvPackets","receiveAnimation","(Ljava/lang/Object;[B)V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        w.visitEnd();Files.write(root.resolve(n+".class"),w.toByteArray());
    }
    static void medicine(Path root) throws Exception {
        String n="szmods/bridge/MedicineUse";
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);
        w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","([B)V",null,null); m.visitCode();
        m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,n,"payload","[B");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"switch","(Ljava/io/DataOutput;)V",null,new String[]{"java/io/IOException"});m.visitCode();
        m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");
        m.visitMethodInsn(INVOKEINTERFACE,"java/io/DataOutput","write","([B)V",true);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"finally","()Z",null,null);m.visitCode();m.visitInsn(ICONST_1);m.visitInsn(IRETURN);m.visitMaxs(0,0);m.visitEnd();
        w.visitEnd();Files.write(root.resolve(n+".class"),w.toByteArray());
    }

    static void snapshot(Path root) throws Exception {
        String n="szmods/bridge/FpvSnapshot";
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17,ACC_PUBLIC|ACC_FINAL,n,null,"499058674",null);
        w.visitField(ACC_PUBLIC,"payload","[B",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);
        m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"499058674","<init>","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"extends","(Ljava/io/DataInput;)V",null,new String[]{"java/io/IOException"});
        m.visitCode();m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);
        m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/FpvPackets","readSnapshot","(Ljava/io/DataInput;)[B",false);
        m.visitFieldInsn(PUTFIELD,n,"payload","[B");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"class","(L1219970978;Z)V",null,null);
        m.visitCode();m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,n,"payload","[B");
        m.visitMethodInsn(INVOKESTATIC,"szmods/runtime/FpvPackets","receive","(Ljava/lang/Object;[B)V",false);
        m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        w.visitEnd();Files.write(root.resolve(n+".class"),w.toByteArray());
    }
}

