package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Keep the native map interaction and title rendering for the local world. */
final class WorldMapTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        boolean map = "1883402698".equals(name);
        boolean radar = "1276284578".equals(name);
        boolean markers = "353197775".equals(name);
        boolean online = "1920126514".equals(name);
        boolean onlinePanel = "1403321368".equals(name);
        boolean mapBinding = "1154824968".equals(name);
        boolean presence = "1137802667".equals(name);
        if (!map && !radar && !markers && !online && !onlinePanel && !mapBinding && !presence && !"1009703493".equals(name)) return null;
        try {
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int edits = 0;
            for (var method : node.methods) {
                for (var instruction : method.instructions.toArray()) {
                    if (map && instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals("508758966") && call.name.equals("throws")
                            && call.desc.equals("(FF)L1226758656;")) {
                        var replacement = new InsnList();
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "gamecompat/WorldMapView",
                            "page", "(Ljava/lang/Object;FF)Ljava/lang/Object;", false));
                        replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, "1226758656"));
                        method.instructions.insert(call, replacement);
                        method.instructions.remove(call);
                        edits++;
                    }
                    if (mapBinding && method.name.equals("finally") && method.desc.equals("()V")
                            && instruction instanceof MethodInsnNode call && call.owner.equals(name)
                            && call.name.equals("if") && call.desc.equals("()Z")) {
                        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "gamecompat/WorldMapView",
                            "suppressMap", "(Z)Z", false));
                        edits++;
                    }
                    if (radar && method.name.equals("finally") && method.desc.equals("(IIIIF)V")
                            && instruction instanceof MethodInsnNode call && call.owner.equals("351318308")
                            && call.name.equals("char") && call.desc.equals("(Ljava/lang/String;III)V")) {
                        var draw = new InsnList();
                        draw.add(new FieldInsnNode(Opcodes.GETSTATIC, "747722393", "p", "L186969209;"));
                        draw.add(new VarInsnNode(Opcodes.ALOAD, 11));
                        draw.add(new FieldInsnNode(Opcodes.GETFIELD, "1500420601", "class", "I"));
                        draw.add(new VarInsnNode(Opcodes.ALOAD, 11));
                        draw.add(new FieldInsnNode(Opcodes.GETFIELD, "1500420601", "abstract", "I"));
                        draw.add(new IntInsnNode(Opcodes.SIPUSH, 235));
                        draw.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IADD));
                        draw.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "szmods/runtime/NearbyPlayers", "render",
                            "(Ljava/lang/Object;II)V", false));
                        method.instructions.insert(call, draw);
                        edits++;
                    }
                    if (markers && method.name.equals("final") && method.desc.equals("(L1743927504;F)V")
                            && instruction instanceof MethodInsnNode call && call.owner.equals("1973624787")
                            && call.name.equals("switch") && call.desc.equals("()V")) {
                        var draw = new InsnList();
                        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        draw.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "szmods/runtime/NearbyPlayers",
                            "renderMap", "(Ljava/lang/Object;)V", false));
                        method.instructions.insert(call, draw);
                        edits++;
                    }
                    if (online && method.name.equals("super") && method.desc.equals("(L950269602;)Ljava/util/List;")
                            && instruction.getOpcode() == Opcodes.ARETURN) {
                        var rows = new InsnList();
                        rows.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        rows.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "szmods/runtime/NearbyPlayers", "online",
                            "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false));
                        method.instructions.insertBefore(instruction, rows);
                        edits++;
                    }
                    if (online && method.name.equals("import") && method.desc.equals("(L950269602;)L272165572;")
                            && instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNONNULL
                            && previousInstruction(jump) instanceof FieldInsnNode holder && holder.owner.equals("960191912")
                            && holder.name.equals("break")) {
                        method.instructions.insertBefore(jump,new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "szmods/runtime/NearbyPlayers","onlineAvailable","(Ljava/lang/Object;)Z",false));
                        method.instructions.set(jump,new JumpInsnNode(Opcodes.IFNE,jump.label));
                        edits++;
                    }
                    if (onlinePanel && method.name.equals("void") && method.desc.equals("()L272165572;")
                            && instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL
                            && previousInstruction(jump) instanceof VarInsnNode holder && holder.getOpcode() == Opcodes.ALOAD
                            && holder.var == 1) {
                        method.instructions.insertBefore(jump,new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "szmods/runtime/NearbyPlayers","onlineAvailable","(Ljava/lang/Object;)Z",false));
                        method.instructions.set(jump,new JumpInsnNode(Opcodes.IFEQ,jump.label));
                        edits++;
                    }
                    if (onlinePanel && method.name.equals("void") && method.desc.equals("()L272165572;")
                            && instruction instanceof MethodInsnNode call && call.owner.equals("87898397")
                            && call.name.equals("package") && call.desc.equals("(L1503540580;)Ljava/util/List;")) {
                        method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "szmods/runtime/NearbyPlayers","onlinePanel","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;",false));
                        edits++;
                    }
                    if (online && method.name.equals("extends") && method.desc.equals("(L950269602;)Ljava/lang/Object;")
                            && instruction.getOpcode() == Opcodes.ARETURN) {
                        method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "szmods/runtime/NearbyPlayers","onlineRevision","(Ljava/lang/Object;)Ljava/lang/Object;",false));
                        edits++;
                    }
                    if (online && method.name.equals("continue") && method.desc.equals("(L1870823086;ZZ)L272165572;")
                            && instruction instanceof MethodInsnNode call && call.owner.equals("1705320572")
                            && call.name.equals("throws") && call.desc.equals("(L1897181654;Ljava/lang/String;IZ)L272165572;")) {
                        var button = new InsnList();
                        button.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"szmods/runtime/NearbyPlayers","profileButton",
                            "(Ljava/lang/Object;Ljava/lang/String;IZ)Ljava/lang/Object;",false));
                        button.add(new TypeInsnNode(Opcodes.CHECKCAST,"272165572"));
                        method.instructions.insert(call,button);
                        method.instructions.remove(call);
                        edits++;
                    }
                    if (presence && method.name.equals("import") && method.desc.equals("(L392971106;)V")
                            && instruction instanceof MethodInsnNode call && call.name.equals("build")
                            && call.owner.equals("com/jagrosh/discordipc/entities/RichPresence$Builder")) {
                        var title = new InsnList();
                        title.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                        title.add(new VarInsnNode(Opcodes.ALOAD,5));
                        title.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"gamecompat/WorldMapView",
                            "presence","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(call,title);
                        edits++;
                    }
                    if ("1009703493".equals(name) && instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                            && field.owner.equals("1033775694") && field.name.equals("extends")
                            && field.desc.equals("L595713561;")) {
                        var replacement = new InsnList();
                        replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, "1033775694", "instanceof", "L595713561;"));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "gamecompat/WorldMapView",
                            "title", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                        replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, "595713561"));
                        method.instructions.insertBefore(field, replacement);
                        edits++;
                    }
                }
            }
            if (edits != (map ? 3 : online ? 10 : onlinePanel ? 2 : 1)) throw new IllegalStateException("Unexpected native map seam count: " + edits);
            var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException error) {
            System.err.println("[World map] Native UI adapter unavailable: " + error);
            return null;
        }
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        var previous=instruction.getPrevious();
        while(previous!=null && previous.getOpcode()<=Opcodes.NOP)previous=previous.getPrevious();
        return previous;
    }
}
