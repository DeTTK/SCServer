package gamecompat;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Keep the native map interaction and title rendering for the local world. */
final class WorldMapTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        boolean map = "1883402698".equals(name);
        boolean radar = "1276284578".equals(name);
        boolean markers = "712731596".equals(name);
        boolean party = "1069186728".equals(name);
        boolean online = "960191912".equals(name);
        boolean profile = "1158484606".equals(name);
        boolean mapBinding = "1154824968".equals(name);
        boolean presence = "1137802667".equals(name);
        if (!map && !radar && !markers && !party && !online && !profile && !mapBinding && !presence && !"1009703493".equals(name)) return null;
        try {
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int edits = 0;
            for (var method : node.methods) {
                if (online && (method.name.equals("try") && method.desc.equals("()V")
                        || method.desc.equals("(Ljava/lang/String;)V") && switch(method.name) {
                            case "native", "throws", "strictfp", "this", "interface" -> true;
                            default -> false;
                        })) {
                    // While connected, the server owns the online directory.
                    var original = new LabelNode();
                    var guard = new InsnList();
                    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"szmods/runtime/NearbyPlayers",
                        "localOnline","()Z",false));
                    guard.add(new JumpInsnNode(Opcodes.IFEQ,original));
                    guard.add(new InsnNode(Opcodes.RETURN));
                    guard.add(original);
                    guard.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
                    method.instructions.insert(guard);
                    edits++;
                }
                if (markers && method.name.equals("interface") && method.desc.equals("(L1743927504;)Z")) {
                    for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.IRETURN) {
                        var hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD,0));
                        hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"szmods/runtime/NearbyPlayers",
                            "mapChanged","(ZLjava/lang/Object;Ljava/lang/Object;)Z",false));
                        method.instructions.insertBefore(instruction,hook);
                        edits++;
                    }
                }
                for (var instruction : method.instructions.toArray()) {
                    if (party && instruction instanceof MethodInsnNode call && call.name.equals("break")
                            && call.desc.equals("()V") && switch(call.owner) {
                                case "1689493828", "548323788", "1996782385" -> true;
                                default -> false;
                            }) {
                        // Party buttons target the official account service in the stock client.
                        // Send their original packets through the local game connection instead.
                        method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "1894232030","float","(L1884662238;)V",false));
                        edits++;
                    }
                    if (profile && method.name.equals("long") && method.desc.equals("(ZL789817320;)L272165572;")
                            && instruction instanceof MethodInsnNode call && call.owner.equals("789817320")
                            && call.name.equals("volatile") && call.desc.equals("()Ljava/lang/String;")) {
                        var equipment = new InsnList();
                        equipment.add(new VarInsnNode(Opcodes.ALOAD,2));
                        equipment.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"szmods/runtime/ProfileEquipment",
                            "describe","(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;",false));
                        method.instructions.insert(call,equipment);
                        edits++;
                    }
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
                    if (markers && method.name.equals("private") && method.desc.equals("(L1743927504;)L374207088;")
                            && instruction.getOpcode() == Opcodes.ARETURN) {
                        var hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD,0));
                        hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"szmods/runtime/NearbyPlayers",
                            "appendMapPlayers","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(instruction, new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                        method.instructions.insertBefore(instruction,hook);
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
            if (edits != (online ? 6 : map || party ? 3 : markers ? 2 : 1)) throw new IllegalStateException("Unexpected native map seam count: " + edits);
            var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException error) {
            System.err.println("[World map] Native UI adapter unavailable: " + error);
            return null;
        }
    }


}
