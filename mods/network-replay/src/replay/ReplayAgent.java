package replay;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Transforms only classes being loaded, never edits the promoted class base. */
public final class ReplayAgent {
    static boolean numericClass(String name){if(name.isEmpty())return false;for(int n=0;n<name.length();n++){char c=name.charAt(n);if(c<'0'||c>'9')return false;}return true;}
    /** An owned QA DLL can replace only Effekseer, preserving FMOD/native paths. */
    public static String nativeEffekseerPath(String original){
        String path=System.getProperty("sz.replay.native.effekseer","");
        if(path.isBlank()||!original.replace('\\','/').endsWith("/stalcraft-effekseer-jni64.dll"))return original;
        java.nio.file.Path selected=java.nio.file.Path.of(path).toAbsolutePath().normalize();
        if(!java.nio.file.Files.isRegularFile(selected))throw new IllegalStateException("Selected Effekseer native library is missing: "+selected);
        return selected.toString();
    }
    public static void premain(String args,Instrumentation instrumentation){
        System.setProperty("sz.replay.agent","true");
        if(!System.getProperty("sz.replay.native.effekseer","").isBlank()){
            // Bootstrap and the game have separate load sites. Both must use
            // one library: mixing versions can bind handles to the wrong ABI.
            System.setProperty("stalcraft.effekseer.path",nativeEffekseerPath("/stalcraft-effekseer-jni64.dll"));
        }
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> cls,ProtectionDomain domain,byte[] bytes){
                if(name==null||!numericClass(name)&&!name.equals("szgmod/SourceTpWeaponLayer")&&!name.equals("szgmod/SourceRigHooks")&&!name.equals("szgmod/SourceWeaponLayer")&&!name.equals("StandardJvmBootstrap")&&!name.equals("exbo/stalcraft/engine/efk/client/Effekseer"))return null;
                boolean clock=name.equals("1591066367");boolean[] changed={false};
                ClassReader r=new ClassReader(bytes);ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
                r.accept(new ClassVisitor(Opcodes.ASM9,w){
                    public void visitEnd(){
                        if(name.equals("685555948")||name.equals("963495584")||name.equals("289750496")){
                            MethodVisitor lookup=super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"szreplay$weaponLookup","()Ljava/lang/invoke/MethodHandles$Lookup;",null,null);
                            lookup.visitCode();lookup.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/invoke/MethodHandles","lookup","()Ljava/lang/invoke/MethodHandles$Lookup;",false);lookup.visitInsn(Opcodes.ARETURN);lookup.visitMaxs(0,0);lookup.visitEnd();changed[0]=true;
                        }
                        if(name.equals("exbo/stalcraft/engine/efk/client/Effekseer")){
                            for(String[] bridge:new String[][]{{"szReplayParticleVersion","()I"},{"szReplayParticleRegisterEffect","(JJ[B)V"},{"szReplayParticleRegisterModel","(JJ[B)V"},{"szReplayParticleCapture","(JII)[B"},{"szReplayParticlePrepare","(J[B)J"},{"szReplayParticlePublish","(J[J)V"},{"szReplayParticleRelease","(J)V"},{"szReplayParticleClear","(J)V"},{"szReplayParticleBytes","(J)J"},{"szReplayParticlePrepareStages","(J[[B)[J"}})
                                super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,bridge[0],bridge[1],null,null).visitEnd();
                            super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,"szReplayTextureExport","(IZ)[B",null,null).visitEnd();
                            super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,"szReplayTextureImport","(J[B)J",null,null).visitEnd();
                            super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,"szReplayTextureExportRef","(J)[B",null,null).visitEnd();
                            super.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,"szReplayEffectSetTextureRef","(JIIJ)V",null,null).visitEnd();
                            changed[0]=true;
                        }
                        if(name.equals("390888342")){
                            super.visitField(Opcodes.ACC_PUBLIC,"szreplay$overrideProgress","Z",null,null).visitEnd();
                            super.visitField(Opcodes.ACC_PUBLIC,"szreplay$userProgress","S",null,null).visitEnd();
                            super.visitField(Opcodes.ACC_PUBLIC,"szreplay$progress","S",null,null).visitEnd();changed[0]=true;
                        }
                        super.visitEnd();
                    }
                    public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions){
                        if(name.equals("1810277368")&&method.equals("static")&&desc.equals("(L200241649;)L1810277368;")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneFpOwnerOverride","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitInsn(Opcodes.DUP);Label normal=new Label();super.visitJumpInsn(Opcodes.IFNULL,normal);super.visitTypeInsn(Opcodes.CHECKCAST,"1810277368");super.visitInsn(Opcodes.ARETURN);super.visitLabel(normal);super.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Object"});super.visitInsn(Opcodes.POP);}};
                        }
                        if(name.equals("1311607083")&&method.equals("strictfp")&&desc.equals("(L353488815;Ljava/util/List;Ljava/util/List;F)V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitFieldInsn(int op,String owner,String field,String descriptor){super.visitFieldInsn(op,owner,field,descriptor);if(op==Opcodes.GETFIELD&&owner.equals("1311607083")&&field.equals("for")&&descriptor.equals("Ljava/util/List;")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneLegacySprites","(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;",false);}}};
                        }
                        if(name.equals("168071319")&&method.equals("assert")&&desc.equals("(L211097523;)Z")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitInsn(int op){if(op==Opcodes.IRETURN){super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneFpStaticModel","(ZLjava/lang/Object;)Z",false);}super.visitInsn(op);}};
                        }
                        boolean ownedWeather=name.equals("569545768")&&desc.equals("()V")&&java.util.Set.of("abstract","while","double","short","throw","extends","void").contains(method);
                        if(ownedWeather){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneIndependentVisibility","()Z",false);Label nativePath=new Label();super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(Opcodes.RETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        if(name.equals("765374079")&&method.equals("assert")&&desc.equals("()D")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneIndependentVisibility","()Z",false);Label nativePath=new Label();super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(Opcodes.DCONST_0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneDayPhase","(D)D",false);super.visitInsn(Opcodes.DRETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        if(name.equals("205471779")&&(method.equals("return")||method.equals("char"))&&desc.equals("()L1987590071;")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitInsn(int op){if(op==Opcodes.ARETURN){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneSun","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"1987590071");}super.visitInsn(op);}};
                        }
                        if(name.equals("671883312")&&method.equals("run")&&desc.equals("()V")){
                            MethodVisitor watchdog=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,watchdog){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                                if(owner.equals("1231017137")&&called.equals("do")&&descriptor.equals("()D"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","watchdogSeconds","(D)D",false);
                            }};
                        }
                        if(name.equals("1492523895")&&method.equals("break")&&desc.equals("(L655143549;ZFLjava/util/EnumSet;Ljava/util/EnumSet;Z)V")){
                            MethodVisitor sorted=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,sorted){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                if(owner.equals("1170268755")&&called.equals("boolean")&&descriptor.equals("(L1122815079;)V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneBatchSort","(Ljava/lang/Object;Ljava/lang/Object;)V",false);return;}
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                            }};
                        }
                        if(Boolean.getBoolean("sz.replay.qa.performance")&&name.equals("1626966273")&&method.equals("import")&&desc.equals("()V")){
                            MethodVisitor measured=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,measured){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                boolean swap=called.equals("glPushAttrib")||called.equals("glPopAttrib")||called.equals("glfwSwapBuffers");String phase="1626966273/"+called;
                                if(swap){super.visitLdcInsn(phase);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance","enter","(Ljava/lang/String;)V",false);}
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                                if(swap){super.visitLdcInsn(phase);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance","exit","(Ljava/lang/String;)V",false);}
                            }};
                        }
                        if(name.equals("722078779")&&method.equals("synchronized")&&desc.equals("()Z")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","textureTrim","(Ljava/lang/Object;)Z",false);wrapper.visitInsn(Opcodes.IRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$synchronized",desc,sig,exceptions);
                        }
                        if(name.equals("645389218")&&method.equals("byte")&&desc.equals("(L1376962110;)V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){super.visitMethodInsn(op,owner,called,descriptor,itf);if(owner.equals("1591066367")&&called.equals("static")&&descriptor.equals("()F"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","shadowFrameDelta","(F)F",false);}};
                        }
                        if(name.equals("137541400")&&method.equals("while")&&desc.equals("(L137541400;L961798934;)V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitVarInsn(int op,int slot){if(op==Opcodes.DSTORE&&(slot==4||slot==6||slot==8)){super.visitInsn(slot==4?Opcodes.ICONST_0:slot==6?Opcodes.ICONST_1:Opcodes.ICONST_2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","shadowCameraAxis","(DI)D",false);}super.visitVarInsn(op,slot);}public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){super.visitMethodInsn(op,owner,called,descriptor,itf);if(owner.equals("249504617")&&called.equals("for")&&descriptor.equals("()J"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneDayTicks","(J)J",false);}};
                        }
                        if(name.equals("137541400")&&method.equals("package")&&desc.equals("(JF)L690606234;")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){super.visitMethodInsn(op,owner,called,descriptor,itf);if((owner.equals("1231017137")||owner.equals("1591066367"))&&called.equals("class")&&descriptor.equals("()F"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneShadowAlpha","(F)F",false);}};
                        }
                        if(name.equals("1366534706")&&method.equals("import")&&desc.equals("(ZFFF)Z")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitInsn(int op){if(op==Opcodes.IRETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectCaptureVisibility","(ZLjava/lang/Object;)Z",false);}super.visitInsn(op);}};
                        }
                        boolean bodyRetire=(name.equals("1500514786")&&(method.equals("void")||method.equals("static"))||name.equals("1054393445")&&method.equals("for"))&&desc.equals("(L1217489680;)V");
                        boolean bodyAdmit=name.equals("1500514786")&&method.equals("throw")&&desc.equals("(L1217489680;)V");
                        if(bodyRetire||bodyAdmit){MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender",bodyRetire?"bodyOwnerRetired":"bodyOwnerAdmitted","(Ljava/lang/Object;)V",false);}};}
                        boolean bodyView=name.equals("1326794892")&&method.equals("native")&&desc.equals("()L1408210865;");
                        if(name.equals("1932171412")&&method.equals("new")&&desc.equals("(L544082630;)Z")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneCorpseDrawDecision","(Ljava/lang/Object;)Ljava/lang/Boolean;",false);super.visitInsn(Opcodes.DUP);Label nativePath=new Label();super.visitJumpInsn(Opcodes.IFNULL,nativePath);super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false);super.visitInsn(Opcodes.IRETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Boolean"});super.visitInsn(Opcodes.POP);}};
                        }
                        boolean bodyTick=name.equals("1326794892")&&method.equals("catch")&&desc.equals("()V");
                        if(bodyView||bodyTick){MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();if(bodyTick){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneBodyRendererFrozen","(Ljava/lang/Object;)Z",false);Label nativePath=new Label();super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(Opcodes.RETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}}public void visitInsn(int op){if(bodyView&&op==Opcodes.ARETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneBodyRenderPose","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"1408210865");}super.visitInsn(op);}};}
                        boolean tracerBorn=name.equals("715287958")&&method.equals("<init>")&&desc.equals("(L1341358987;L153304200;L153304200;Z)V");
                        boolean tracerOrigin=name.equals("715287958")&&method.equals("abstract")&&desc.equals("(FFF)V");
                        boolean tracerProgress=name.equals("715287958")&&method.equals("protected")&&desc.equals("()F");
                        boolean tracerTextures=name.equals("589727557")&&method.equals("if")&&desc.equals("()Ljava/util/Set;");
                        boolean tracerBeams=name.equals("589727557")&&method.equals("protected")&&desc.equals("(L779455578;)Ljava/util/List;");
                        if(tracerBorn||tracerOrigin||tracerProgress||tracerTextures||tracerBeams){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitInsn(int op){
                                if(op==Opcodes.RETURN&&tracerBorn){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","tracerBorn","(Ljava/lang/Object;)V",false);}
                                if(op==Opcodes.RETURN&&tracerOrigin){super.visitVarInsn(Opcodes.ALOAD,0);for(int n=1;n<=3;n++)super.visitVarInsn(Opcodes.FLOAD,n);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","tracerOrigin","(Ljava/lang/Object;FFF)V",false);}
                                if(op==Opcodes.FRETURN&&tracerProgress){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","tracerProgress","(FLjava/lang/Object;)F",false);}
                                if(op==Opcodes.ARETURN&&tracerTextures)super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","tracerTextures","(Ljava/util/Set;)Ljava/util/Set;",false);
                                if(op==Opcodes.ARETURN&&tracerBeams){super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","tracerBeams","(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;",false);}
                                super.visitInsn(op);
                            }};
                        }
                        if(name.equals("205471779")&&method.equals("synchronized")&&desc.equals("()L648055939;")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();super.visitInsn(Opcodes.ACONST_NULL);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneEnvironmentOrOriginal","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitInsn(Opcodes.DUP);Label nativePath=new Label();super.visitJumpInsn(Opcodes.IFNULL,nativePath);super.visitTypeInsn(Opcodes.CHECKCAST,"648055939");super.visitInsn(Opcodes.ARETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Object"});super.visitInsn(Opcodes.POP);changed[0]=true;}};
                        }
                        if(name.equals("976516049")&&method.equals("break")&&desc.equals("()V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitInsn(int op){if(op==Opcodes.RETURN)super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","afterUiFrame","()V",false);super.visitInsn(op);}};
                        }
                        if(name.equals("976516049")&&method.equals("throw")&&desc.equals("()V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                if(owner.equals("533013651")&&called.equals("boolean")&&descriptor.equals("()V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","beforeUiFrame","()V",false);changed[0]=true;}
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                            }};
                        }
                        if(name.equals("1754585878")&&method.equals("assert")&&desc.equals("(Z)V")
                                ||name.equals("1855282131")&&(method.equals("extends")&&desc.equals("(Z)V")||method.equals("double")&&desc.equals("(DD)V"))){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();Label nativePath=new Label();super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","cursorOwned","Z");super.visitJumpInsn(Opcodes.IFEQ,nativePath);
                                // Game focus remains available for hotkeys, but
                                // the raw listener must not disable WM_MOUSE
                                // events consumed by GLFW and ImGui.
                                if(name.equals("1754585878")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"continue","()V",false);}
                                super.visitInsn(Opcodes.RETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);
                            }};
                        }
                        if(name.equals("533013651")&&method.equals("double")&&desc.equals("()V")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","cursorUiMouseUpdate","(Ljava/lang/Object;)V",false);wrapper.visitInsn(Opcodes.RETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$double",desc,sig,exceptions);
                        }
                        boolean uiScreen=name.equals("622415611")&&(method.equals("abstract")&&desc.equals("()V")||method.equals("for")&&desc.equals("(IIF)V"));
                        boolean uiInstall=name.equals("1402131154")&&method.equals("else")&&desc.equals("(L622415611;)V");
                        if(uiScreen||uiInstall){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitLdcInsn(method);
                            Type[] params=Type.getArgumentTypes(desc);wrapper.visitLdcInsn(params.length);wrapper.visitTypeInsn(Opcodes.ANEWARRAY,"java/lang/Object");int slot=1;
                            for(int i=0;i<params.length;i++){wrapper.visitInsn(Opcodes.DUP);wrapper.visitLdcInsn(i);Type p=params[i];wrapper.visitVarInsn(p.getOpcode(Opcodes.ILOAD),slot);slot+=p.getSize();if(p.getSort()==Type.INT)wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/Integer","valueOf","(I)Ljava/lang/Integer;",false);if(p.getSort()==Type.FLOAT)wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/Float","valueOf","(F)Ljava/lang/Float;",false);wrapper.visitInsn(Opcodes.AASTORE);}
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","nativeUiInvoke","(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V",false);wrapper.visitInsn(Opcodes.RETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$ui$"+method,desc,sig,exceptions);
                        }
                        if(name.equals("1231017137")&&method.equals("do")&&desc.equals("()D")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitFieldInsn(Opcodes.GETFIELD,name,"char","D");wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","uiTimeInvoke","(Ljava/lang/Object;D)D",false);wrapper.visitInsn(Opcodes.DRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;return super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                        }
                        if(viewerToolUi(name,method,desc)){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                // Tools access is local to the tool UI. The same
                                // rank predicates also enable world debug volumes
                                // and gameplay behavior, so never replace them globally.
                                if(op==Opcodes.INVOKESTATIC&&owner.equals("353451775")&&(called.equals("boolean")||called.equals("assert"))&&descriptor.equals("(L200241649;)Z")){
                                    super.visitMethodInsn(op,"replay/ReplayRender",called.equals("boolean")?"viewerLowTools":"viewerFullTools","(Ljava/lang/Object;)Z",false);changed[0]=true;return;
                                }
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                            }};
                        }
                        if(name.equals("1231017137")&&method.equals("instanceof")&&desc.equals("()I")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitFieldInsn(Opcodes.GETFIELD,name,"else","I");
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","uiTickInvoke","(Ljava/lang/Object;I)I",false);wrapper.visitInsn(Opcodes.IRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                        }
                        if(name.equals("StandardJvmBootstrap")&&method.equals("loadBundledNatives")){
                            MethodVisitor nativeLoader=super.visitMethod(access,method,desc,sig,exceptions);
                            return new MethodVisitor(Opcodes.ASM9,nativeLoader){public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){
                                if(op==Opcodes.INVOKESTATIC&&owner.equals("java/lang/System")&&called.equals("load")&&descriptor.equals("(Ljava/lang/String;)V")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayAgent","nativeEffekseerPath","(Ljava/lang/String;)Ljava/lang/String;",false);changed[0]=true;
                                }
                                super.visitMethodInsn(op,owner,called,descriptor,itf);
                            }};
                        }
                        if(name.equals("1376962110")&&method.equals("case")&&desc.equals("(L1217489680;)Z")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();Label nativePath=new Label();super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneActorVisible","(Ljava/lang/Object;Ljava/lang/Object;)Z",false);super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(Opcodes.ICONST_1);super.visitInsn(Opcodes.IRETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        if(name.equals("1102540382")&&(method.equals("this")||method.equals("const"))&&desc.equals("(L353488815;I)I")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();Label original=new Label();wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,1);wrapper.visitVarInsn(Opcodes.ILOAD,2);wrapper.visitLdcInsn(method);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneTerrainDrawInvoke","(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/String;)I",false);wrapper.visitInsn(Opcodes.IRETURN);wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,1);wrapper.visitVarInsn(Opcodes.ILOAD,2);wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"szreplay$original$"+method,desc,false);wrapper.visitInsn(Opcodes.IRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;return super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                        }
                        if(name.equals("1102540382")&&method.equals("public")&&desc.equals("(L353488815;)V")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();Label nativePath=new Label();super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneTerrainUpdate","(Ljava/lang/Object;Ljava/lang/Object;)Z",false);super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(Opcodes.RETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        boolean selectedCell=name.equals("1102540382")&&method.equals("for")&&desc.equals("(DDD)Z");
                        boolean selectedBox=name.equals("1019832350")&&method.equals("const")&&desc.equals("(L1102540382;L334744794;)Z");
                        if(selectedCell||selectedBox){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();Label nativePath=new Label();super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneIndependentVisibility","()Z",false);super.visitJumpInsn(Opcodes.IFEQ,nativePath);super.visitInsn(selectedCell?Opcodes.ICONST_1:Opcodes.ICONST_0);super.visitInsn(Opcodes.IRETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        if(name.equals("462492221")&&(method.equals("default")&&desc.equals("(L1525692938;)Z")||(method.equals("interface")||method.equals("implements"))&&desc.equals("(Ljava/util/List;J)V"))){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitCode(){super.visitCode();Label nativePath=new Label();super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneIndependentVisibility","()Z",false);super.visitJumpInsn(Opcodes.IFEQ,nativePath);if(method.equals("default")){super.visitInsn(Opcodes.ICONST_1);super.visitInsn(Opcodes.IRETURN);}else super.visitInsn(Opcodes.RETURN);super.visitLabel(nativePath);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}};
                        }
                        if(name.equals("1019832350")&&method.equals("double")&&desc.startsWith("(L1886271463;L353488815;L1304833431;")){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){public void visitMethodInsn(int opcode,String owner,String called,String signature,boolean itf){super.visitMethodInsn(opcode,owner,called,signature,itf);if(owner.equals("1492523895")&&called.equals("boolean")&&signature.equals("(L1889305602;)V")){super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneMapMeshSubmitted","(Ljava/lang/Object;)V",false);}}};
                        }
                        if(name.equals("1500514786")&&method.equals("do")&&desc.equals("()L493563411;")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();Label original=new Label();wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneMapManagerInvoke","(Ljava/lang/Object;)Ljava/lang/Object;",false);wrapper.visitTypeInsn(Opcodes.CHECKCAST,"493563411");wrapper.visitInsn(Opcodes.ARETURN);
                            wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"szreplay$original$do",desc,false);wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;return super.visitMethod(access,"szreplay$original$do",desc,sig,exceptions);
                        }
                        if(name.equals("1554765982")&&method.equals("abstract")&&desc.equals("(II)L812508934;")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();Label original=new Label();wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ILOAD,1);wrapper.visitVarInsn(Opcodes.ILOAD,2);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneChunkInvoke","(Ljava/lang/Object;II)Ljava/lang/Object;",false);wrapper.visitTypeInsn(Opcodes.CHECKCAST,"812508934");wrapper.visitInsn(Opcodes.ARETURN);
                            wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ILOAD,1);wrapper.visitVarInsn(Opcodes.ILOAD,2);wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"szreplay$original$abstract",desc,false);wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;return super.visitMethod(access,"szreplay$original$abstract",desc,sig,exceptions);
                        }
                        boolean mapChunk=name.equals("812508934")&&((method.equals("interface")&&desc.equals("([BIIZ)V"))||desc.equals("(III)V")&&(method.equals("synchronized")||method.equals("final")||method.equals("this"))||desc.equals("(IIII)V")&&(method.equals("extends")||method.equals("super"))||method.equals("implements")&&desc.equals("(L921979641;IIII)V")||method.equals("instanceof")&&desc.equals("([B)V")||method.equals("implements")&&desc.equals("(IIIL1123841866;)V"));
                        boolean mapSection=name.equals("368005700")&&Type.getReturnType(desc).getSort()==Type.VOID&&!method.equals("<init>")&&!method.equals("<clinit>");
                        boolean mapPrefab=name.equals("1886271463")&&((method.equals("char")&&desc.equals("(Ljava/io/DataInput;)V"))||(method.equals("public")&&desc.equals("(L98008865;)V"))||method.equals("goto")&&desc.equals("(Ljava/lang/String;ZF)V"));
                        boolean mapAttributes=name.equals("1536302723")&&(method.equals("instanceof")||method.equals("const"))&&desc.equals("(L1798539107;Ljava/lang/Object;)V");
                        boolean mapBehavior=desc.equals("()V")&&(name.equals("1079929180")&&method.equals("if")||name.equals("165678416")&&method.equals("extends"));
                        if(mapChunk||mapSection||mapPrefab||mapAttributes||mapBehavior){
                            MethodVisitor original=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,original){
                                public void visitCode(){super.visitCode();Label absent=new Label();super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","mapChanges","Lreplay/ReplayRender$MapChanges;");super.visitJumpInsn(Opcodes.IFNULL,absent);super.visitVarInsn(Opcodes.ALOAD,0);if(mapAttributes)super.visitFieldInsn(Opcodes.GETFIELD,name,"for","L1667320743;");if(mapBehavior)super.visitFieldInsn(Opcodes.GETFIELD,"1079929180","for","L1886271463;");super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneMapChanged","(Ljava/lang/Object;)V",false);super.visitLabel(absent);super.visitFrame(Opcodes.F_SAME,0,null,0,null);}
                                public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean iface){
                                    if(name.equals("812508934")&&method.equals("interface")&&desc.equals("([BIIZ)V")&&owner.equals("1359994658")&&called.equals("const")&&descriptor.equals("(L812508934;[BIIZ)V"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","chunkLoadedInvoke","(Ljava/lang/Object;[BIIZ)V",false);
                                    else super.visitMethodInsn(op,owner,called,descriptor,iface);
                                }
                            };
                        }
                        if(name.equals("820497227")&&desc.equals("()F")&&(method.equals("catch")||method.equals("throws")||method.equals("class")||method.equals("volatile"))){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();Label original=new Label();
                            wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);
                            wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitLdcInsn(method);wrapper.visitInsn(method.equals("catch")||method.equals("throws")?Opcodes.ICONST_0:Opcodes.ICONST_1);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneLookInvoke","(Ljava/lang/Object;Ljava/lang/String;I)F",false);wrapper.visitInsn(Opcodes.FRETURN);
                            wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"szreplay$original$"+method,desc,false);wrapper.visitInsn(Opcodes.FRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                        }
                        boolean sceneEntities=name.equals("1500514786")&&method.equals("volatile")&&desc.equals("()Ljava/util/Collection;");
                        boolean sceneEntity=name.equals("1500514786")&&method.equals("continue")&&desc.equals("(I)L1217489680;");
                        if(sceneEntities||sceneEntity){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            Label original=new Label();wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);
                            wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);if(sceneEntity)wrapper.visitVarInsn(Opcodes.ILOAD,1);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender",sceneEntities?"sceneEntitiesInvoke":"sceneEntityInvoke",sceneEntities?"(Ljava/lang/Object;)Ljava/util/Collection;":"(Ljava/lang/Object;I)Ljava/lang/Object;",false);
                            if(sceneEntity)wrapper.visitTypeInsn(Opcodes.CHECKCAST,"1217489680");wrapper.visitInsn(Opcodes.ARETURN);
                            wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);wrapper.visitVarInsn(Opcodes.ALOAD,0);if(sceneEntity)wrapper.visitVarInsn(Opcodes.ILOAD,1);
                            wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"szreplay$original$"+method,desc,false);wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                        }
                        boolean scenePosition=name.equals("576005245")&&method.equals("char")&&desc.equals("(L1217489680;F)L153304200;");
                        boolean scenePose=(name.equals("387875964")&&(method.equals("final")||method.equals("extends"))||name.equals("577326810")&&method.equals("try"))&&desc.equals("()L1118769848;");
                        boolean sceneEnvironment=name.equals("569545768")&&method.equals("final")&&desc.equals("()L648055939;");
                        boolean sceneAttribute=name.equals("1798539107")&&method.equals("continue")&&desc.equals("()Ljava/lang/Object;");
                        boolean sceneBodyView=name.equals("961798934")&&method.equals("case")&&desc.equals("()L658914576;");
                        boolean sceneEquipment=name.equals("1619781176")&&method.equals("if")&&desc.equals("()L211097523;");
                        boolean sceneFpItem=name.equals("764970879")&&method.equals("enum")&&desc.equals("(L200241649;)L211097523;");
                        boolean sceneStyle=name.equals("916325480")&&method.equals("double")&&desc.equals("(L200241649;)L916325480;");
                        if(scenePosition||scenePose||sceneEnvironment||sceneAttribute||sceneBodyView||sceneEquipment||sceneStyle||sceneFpItem){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            // The default viewer and menu keep the original hot path.
                            // Reflection is needed only while an indexed frame is published.
                            Label original=new Label();wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");wrapper.visitJumpInsn(Opcodes.IFEQ,original);
                            if(!sceneBodyView&&!sceneEquipment&&!sceneStyle&&!sceneFpItem){wrapper.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","scenePresentation","Lreplay/ReplayRender$ScenePresentation;");wrapper.visitJumpInsn(Opcodes.IFNULL,original);}
                            if(!sceneEnvironment)wrapper.visitVarInsn(Opcodes.ALOAD,0);
                            if(scenePosition)wrapper.visitVarInsn(Opcodes.FLOAD,1);
                            if(sceneAttribute){wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitFieldInsn(Opcodes.GETFIELD,name,"do","Ljava/lang/Object;");}
                            String hook=sceneFpItem?"sceneFpItemInvoke":sceneStyle?"sceneEquipmentStyleInvoke":sceneEquipment?"sceneEquipmentItemInvoke":sceneBodyView?"sceneBodyViewInvoke":scenePosition?"scenePositionInvoke":scenePose?(method.equals("extends")?"scenePoseEvaluateInvoke":method.equals("try")?"sceneRemainsPoseInvoke":"scenePoseInvoke"):sceneEnvironment?"sceneEnvironmentInvoke":"sceneAttributeInvoke";
                            String signature=scenePosition?"(Ljava/lang/Object;F)Ljava/lang/Object;":sceneEnvironment?"()Ljava/lang/Object;":sceneAttribute?"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;":"(Ljava/lang/Object;)Ljava/lang/Object;";
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender",hook,signature,false);
                            Type returns=Type.getReturnType(desc);if(!returns.getInternalName().equals("java/lang/Object"))wrapper.visitTypeInsn(Opcodes.CHECKCAST,returns.getInternalName());
                            wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitLabel(original);wrapper.visitFrame(Opcodes.F_SAME,0,null,0,null);
                            if(!sceneEnvironment)wrapper.visitVarInsn(Opcodes.ALOAD,0);if(scenePosition)wrapper.visitVarInsn(Opcodes.FLOAD,1);
                            wrapper.visitMethodInsn((access&Opcodes.ACC_STATIC)!=0?Opcodes.INVOKESTATIC:Opcodes.INVOKEVIRTUAL,name,"szreplay$original$"+method,desc,false);
                            wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            MethodVisitor nativeOriginal=super.visitMethod(access,"szreplay$original$"+method,desc,sig,exceptions);
                            if(sceneEquipment)return new MethodVisitor(Opcodes.ASM9,nativeOriginal){
                                public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean iface){
                                    // Preserve the NPC armor correction in the renamed body.
                                    if(owner.equals("1422788721")&&called.equals("continue")&&descriptor.equals("()L211097523;")){
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","npcArmor","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"211097523");return;
                                    }
                                    super.visitMethodInsn(opcode,owner,called,descriptor,iface);
                                }
                            };
                            return nativeOriginal;
                        }
                        if(name.equals("1402131154")&&method.equals("protected")&&desc.equals("(D)V")&&Integer.getInteger("sz.replay.qa.fps",0)>0){
                            MethodVisitor paced=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,paced){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.DLOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance","interval","(D)D",false);super.visitVarInsn(Opcodes.DSTORE,1);}};
                        }
                        if(Boolean.getBoolean("sz.replay.qa.performance")&&name.equals("1402131154")&&method.equals("void")&&desc.equals("()V")){
                            MethodVisitor measured=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,measured){
                                void enter(String phase){super.visitLdcInsn(phase);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance","enter","(Ljava/lang/String;)V",false);}
                                void exit(String phase){super.visitLdcInsn(phase);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance","exit","(Ljava/lang/String;)V",false);}
                                public void visitCode(){super.visitCode();enter("frame");}
                                public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean iface){
                                    boolean measured=Type.getReturnType(descriptor).getSort()==Type.VOID&&!owner.equals("1352563015")&&!called.equals("<init>");
                                    String phase=owner+"."+called;if(measured)enter(phase);super.visitMethodInsn(op,owner,called,descriptor,iface);if(measured)exit(phase);
                                }
                                public void visitInsn(int op){if(op==Opcodes.RETURN)exit("frame");super.visitInsn(op);}
                            };
                        }
                        if(name.equals("1402131154")&&method.equals("extends")&&desc.equals("()V")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","cursorFocusInvoke","(Ljava/lang/Object;)V",false);wrapper.visitInsn(Opcodes.RETURN);
                            wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$extends",desc,sig,exceptions);
                        }
                        if(name.equals("910717201")&&method.equals("do")&&desc.equals("()D")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","actorTimeInvoke","(Ljava/lang/Object;)D",false);
                            wrapper.visitInsn(Opcodes.DRETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$do",desc,sig,exceptions);
                        }
                        if(name.equals("1100232767")&&method.equals("while")&&desc.equals("(F)V")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.FLOAD,1);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","bodyPhysicsInvoke","(Ljava/lang/Object;F)V",false);
                            wrapper.visitInsn(Opcodes.RETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$while",desc,sig,exceptions);
                        }
                        if(name.equals("211097523")&&method.equals("enum")&&desc.equals("()L211097523;")){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","nativeCopy","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                            wrapper.visitTypeInsn(Opcodes.CHECKCAST,"211097523");wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;
                            return super.visitMethod(access,"szreplay$original$enum",desc,sig,exceptions);
                        }
                        if(name.equals("757158137")&&(method.equals("do")&&desc.equals("(L1219970978;)L1630623925;")||method.equals("native")&&desc.equals("(L1219970978;)V"))){
                            String original="szreplay$original$"+method;MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();
                            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,1);wrapper.visitLdcInsn(original);String returns=Type.getReturnType(desc).getClassName();wrapper.visitLdcInsn(returns);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","task","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",false);
                            if(returns.equals("void")){wrapper.visitInsn(Opcodes.POP);wrapper.visitInsn(Opcodes.RETURN);}else{wrapper.visitTypeInsn(Opcodes.CHECKCAST,Type.getReturnType(desc).getInternalName());wrapper.visitInsn(Opcodes.ARETURN);}
                            wrapper.visitMaxs(0,0);wrapper.visitEnd();changed[0]=true;return super.visitMethod(access,original,desc,sig,exceptions);
                        }
                        boolean connectionGuard=name.equals("1219970978")&&(method.equals("for")&&desc.equals("(D)V")||(method.equals("throws")||method.equals("double"))&&desc.equals("(L1054393445;L124422489;)V"));
                        boolean transportGuard=name.equals("1318329920")&&method.equals("super")&&desc.equals("(L1884662238;)V");
                        boolean installGuard=name.equals("1402131154")&&(method.equals("protected")||method.equals("enum"))&&desc.equals("(L1219970978;)V");
                        if(connectionGuard||transportGuard||installGuard){
                            MethodVisitor guarded=super.visitMethod(access,method,desc,sig,exceptions);changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,guarded){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,installGuard?1:0);
                                if(transportGuard)super.visitFieldInsn(Opcodes.GETFIELD,name,"interface","L1219970978;");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","allowConnection","(Ljava/lang/Object;)Z",false);
                                Label allowed=new Label();super.visitJumpInsn(Opcodes.IFNE,allowed);super.visitInsn(Opcodes.RETURN);super.visitLabel(allowed);super.visitFrame(Opcodes.F_SAME,0,null,0,null);
                            }};
                        }
                        boolean weatherZone=name.equals("1049587979")&&method.equals("catch")&&desc.equals("(L1500514786;L372120312;D)L648055939;");
                        MethodVisitor m;
                        if(weatherZone){
                            MethodVisitor wrapper=super.visitMethod(access,method,desc,sig,exceptions);wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitVarInsn(Opcodes.ALOAD,1);wrapper.visitVarInsn(Opcodes.ALOAD,2);wrapper.visitVarInsn(Opcodes.DLOAD,3);
                            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherZoneInvoke","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;D)Ljava/lang/Object;",false);wrapper.visitTypeInsn(Opcodes.CHECKCAST,"648055939");wrapper.visitInsn(Opcodes.ARETURN);wrapper.visitMaxs(0,0);wrapper.visitEnd();
                            m=super.visitMethod(access,"szreplay$original$catch",desc,sig,exceptions);changed[0]=true;
                        }else m=super.visitMethod(access,method,desc,sig,exceptions);
                        if(Boolean.getBoolean("sz.replay.qa.renderprofile")&&name.equals("1535940098")&&!method.equals("<init>")&&!method.equals("<clinit>")&&Type.getReturnType(desc).getSort()==Type.VOID){
                            changed[0]=true;m=new MethodVisitor(Opcodes.ASM9,m){
                                void mark(String phase,boolean start){super.visitLdcInsn(phase);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayPerformance",start?"enter":"exit","(Ljava/lang/String;)V",false);}
                                public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean iface){
                                    boolean measured=Type.getReturnType(descriptor).getSort()==Type.VOID&&Character.isDigit(owner.charAt(0))&&!owner.equals("1352563015")&&!called.equals("<init>");
                                    String phase="3D/"+owner+"."+called+descriptor;if(measured)mark(phase,true);super.visitMethodInsn(op,owner,called,descriptor,iface);if(measured)mark(phase,false);
                                }
                            };
                        }
                        // Use the stock F1 render gates, without changing the
                        // user's saved setting or skipping the Alt tools draw
                        // at the end of the main HUD method.
                        if(name.equals("236685197")&&method.equals("strictfp")&&desc.equals("(IIF)V")
                                ||name.equals("1402131154")&&method.equals("short")&&desc.equals("()Z")
                                ||name.equals("1376962110")&&method.equals("finally")&&desc.equals("(L353488815;)V")
                                ||name.equals("1761650403")&&method.equals("void")&&desc.equals("(FL207310501;FZ)V")){
                            m=new MethodVisitor(Opcodes.ASM9,m){boolean hudFlag;
                                public void visitFieldInsn(int op,String owner,String field,String descriptor){super.visitFieldInsn(op,owner,field,descriptor);if(op==Opcodes.GETSTATIC&&owner.equals("1546247182")&&field.equals("k"))hudFlag=true;}
                                public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean itf){super.visitMethodInsn(op,owner,called,descriptor,itf);if(hudFlag&&owner.equals("847298856")&&called.equals("transient")&&descriptor.equals("()Z")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","hideGameHud","(Z)Z",false);hudFlag=false;changed[0]=true;}}
                            };
                        }
                        boolean capture=name.equals("StandardJvmBootstrap")&&method.equals("restoreWorldMouseCapture")&&desc.equals("(Ljava/lang/Object;J)V");
                        boolean testCursor=name.equals("StandardJvmBootstrap")&&method.equals("invokeCursor")&&desc.equals("(DD)V");
                        boolean testButton=name.equals("StandardJvmBootstrap")&&method.equals("invokeMouse")&&desc.equals("(III)V");
                        boolean testBackend=name.equals("533013651")&&method.equals("double")&&desc.equals("()V");
                        boolean fpBody=name.equals("656386660")&&method.equals("goto")&&desc.equals("(Z)Z");
                        boolean imported=clock&&method.equals("import")&&desc.equals("()V");
                        boolean configured=clock&&method.equals("<init>")&&desc.equals("(F)V");
                        boolean taskConstructor=name.equals("757158137")&&method.equals("<init>");
                        boolean bootstrap=name.equals("124422489")&&method.equals("const")&&desc.equals("(L200241649;L124422489;)V");
                        boolean applying=SetHolder.handler(name)&&method.equals("class")&&desc.equals("(L1219970978;Z)V");
                        boolean tail=name.equals("456314467")&&method.equals("new")&&desc.equals("(L1217489680;BL1754157392;)V");
                        boolean gun=name.equals("1745367406")&&method.equals("strictfp")&&desc.equals("(L211097523;Ljava/io/DataInput;)V");
                        boolean local=name.equals("378545641")&&method.equals("goto")&&desc.equals("()V");
                        boolean prefab=name.equals("1886271463")&&method.equals("new")&&desc.equals("(Ljava/io/DataInput;)L1886271463;");
                        boolean component=name.equals("1079929180")&&method.equals("native")&&desc.equals("(Ljava/io/DataInput;)V");
                        boolean tp=name.equals("szgmod/SourceTpWeaponLayer")&&method.equals("select");
                        boolean visualClock=name.equals("szgmod/SourceTpWeaponLayer")||name.equals("szgmod/SourceRigHooks")||name.equals("szgmod/SourceWeaponLayer");
                        boolean npcArmor=name.equals("1619781176")&&method.equals("if")&&desc.equals("()L211097523;");
                        boolean lookup=name.equals("szgmod/SourceRigHooks")&&method.equals("weaponMatrix");
                        boolean audioEvent=name.equals("1069936767")&&method.equals("native")&&desc.equals("(J)I");
                        boolean audioChannel=name.equals("1141491285")&&method.equals("import")&&desc.equals("(JJJIJ)I");
                        boolean audioConfigured=name.equals("1335989370")&&method.equals("synchronized")&&desc.equals("(IJIFFFFFFFIZ)V");
                        if(name.equals("1335989370")&&method.equals("while")&&desc.equals("(JIFFFFFFFIZ)I")){
                            changed[0]=true;
                            return new MethodVisitor(Opcodes.ASM9,m){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitFieldInsn(Opcodes.GETFIELD,"1335989370","do","I");super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","channelRequested","(I)V",false);}};
                        }
                        boolean audioPosition=name.equals("1069936767")&&method.equals("float")&&desc.equals("(JI)I")||name.equals("1141491285")&&method.equals("protected")&&desc.equals("(JII)I");
                        boolean soundResource=name.equals("1335989370")&&(method.equals("import")&&desc.equals("(Ljava/lang/String;I)J")||method.equals("default")&&desc.equals("(Ljava/lang/String;II)J"));
                        boolean soundRelease=name.equals("1141491285")&&method.equals("instanceof")&&desc.equals("(J)I");
                        boolean modelResource=name.equals("1182339164")&&method.equals("public")&&desc.equals("(L779455578;Ljava/lang/String;F)V");
                        boolean materialResource=name.equals("353540541")&&method.equals("continue")&&desc.equals("()V");
                        boolean effectMatrix=name.equals("1544747408")&&(method.equals("void")&&desc.equals("()V")||method.equals("const")&&desc.equals("(L1366534706;)V"));
                        boolean questHud=name.equals("1630029313")&&method.equals("finally")&&desc.equals("(IIIIF)V");
                        boolean worldTime=name.equals("249504617")&&method.equals("throw")&&desc.equals("(J)V");
                        boolean wallClock=name.equals("1850427543")&&method.equals("millis")&&desc.equals("()J");
                        boolean environmentPacket=name.equals("StandardJvmBootstrap")&&method.equals("prepareEnvironmentClockPacket")&&desc.equals("(Ljava/lang/Object;)V");
                        boolean skyClock=name.equals("StandardJvmBootstrap")&&(method.equals("synchronizeVisualWorldClock")||method.equals("synchronizeVisualWeather")||method.equals("restoreCurrentFrontendAfterDisconnect"))&&desc.equals("(Ljava/lang/Object;)V");
                        boolean effectClock=name.equals("221705413");
                        boolean weaponFx=name.equals("1043044427")&&method.equals("throws")&&desc.equals("(L211097523;L351062380;L461253740;)V");
                        boolean effectOrigin=name.equals("432112703")&&method.equals("new")&&desc.equals("(FFF)V");
                        boolean weatherTick=name.equals("962844222")&&method.equals("while")&&desc.equals("()V");
                        boolean shakeClock=name.equals("1427337337")&&method.equals("extends")&&desc.equals("(F)V");
                        boolean weatherShader=name.equals("569545768");
                        boolean sourceSnow=name.equals("1683205074")&&method.equals("while")&&desc.equals("(ZF)V");
                        boolean bodyPrepare=name.equals("544082630")&&method.equals("do")&&desc.equals("(L1118769848;)V");
                        boolean bodyPhysicsCreate=name.equals("241725195")&&method.equals("while")&&desc.equals("()L171384217;");
                        boolean actorCreate=name.equals("1422788721")&&method.equals("<init>");
                        boolean actorTimer=name.equals("1422788721")&&method.equals("do")&&desc.equals("()L910717201;");
                        boolean graphUpdate=Boolean.getBoolean("sz.replay.qa.tailtrace")&&name.equals("859153379")&&method.equals("package")&&desc.equals("(ZL1684091846;)Z");
                        boolean graphGate=name.equals("859153379")&&method.equals("package")&&desc.equals("(ZL1684091846;)Z")||name.equals("387875964")&&method.equals("break")&&desc.equals("()V");
                        boolean gameTick=Boolean.getBoolean("sz.replay.qa.tickloop")&&name.equals("1402131154")&&method.equals("catch")&&desc.equals("()V");
                        boolean extractorTick=name.equals("1212469801")&&method.equals("if")&&desc.equals("()V");
                        boolean extractorProgress=name.equals("1212469801")&&method.equals("volatile")&&desc.equals("()V");
                        boolean extractorConfig=name.equals("1212469801")&&method.equals("char")&&desc.equals("(L98008865;)V");
                        boolean extractorRemove=name.equals("1079929180")&&method.equals("package")&&desc.equals("(Z)V");
                        boolean extractorHudUpdate=name.equals("1651476251")&&method.equals("finally")&&desc.equals("()V");
                        boolean extractorHudDraw=name.equals("1651476251")&&method.equals("finally")&&desc.equals("(IIIIF)V");
                        boolean extractorPrompt=name.equals("1212469801")&&method.equals("catch")&&desc.equals("()Ljava/lang/String;");
                        boolean visualProgress=name.equals("390888342")&&(method.equals("extends")&&desc.equals("(Ljava/io/DataInput;)V")||method.equals("switch")&&desc.equals("(Ljava/io/DataOutput;)V"));
                        boolean visualEquals=name.equals("390888342")&&method.equals("equals")&&desc.equals("(Ljava/lang/Object;)Z");
                        boolean actionGate=name.equals("1822220185")&&method.equals("float")&&desc.equals("(L238490198;IIZ)V");
                        boolean terrainRenderer=name.equals("1376962110");boolean terrainCellDraw=name.equals("949691181")&&method.equals("continue")&&desc.equals("(I)Z");
                        boolean decoder=desc.contains("Ljava/io/DataInput;");if(!soundResource&&!soundRelease&&!terrainRenderer&&!terrainCellDraw&&!worldTime&&!actionGate&&!gameTick&&!graphGate&&!graphUpdate&&!visualClock&&!actorCreate&&!actorTimer&&!bodyPhysicsCreate&&!bodyPrepare&&!extractorPrompt&&!extractorHudUpdate&&!extractorHudDraw&&!extractorTick&&!extractorProgress&&!extractorConfig&&!extractorRemove&&!prefab&&!component&&!weatherShader&&!sourceSnow&&!shakeClock&&!weatherTick&&!weatherZone&&!visualEquals&&!visualProgress&&!audioEvent&&!audioChannel&&!audioConfigured&&!audioPosition&&!modelResource&&!materialResource&&!effectMatrix&&!effectClock&&!weaponFx&&!effectOrigin&&!wallClock&&!environmentPacket&&!skyClock&&!questHud&&!fpBody&&!testCursor&&!testButton&&!testBackend&&!capture&&!imported&&!configured&&!taskConstructor&&!decoder&&!bootstrap&&!applying&&!tail&&!gun&&!local&&!tp&&!npcArmor&&!lookup)return m;
                        return new MethodVisitor(Opcodes.ASM9,m){
                            int itemByteReads;
                            public void visitCode(){
                                super.visitCode();
                                if(effectOrigin){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectOriginFrozen","(Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;}
                                if(effectClock&&method.equals("public")&&desc.equals("(L432112703;Z)V")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectLayerSkipped","(Ljava/lang/Object;Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;}
                                if(worldTime){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.LLOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","recordedWorldTime","(Ljava/lang/Object;J)V",false);changed[0]=true;}
                                if(skyClock){
                                    // The captured native WorldInfo already owns its 160000-tick
                                    // day. The custom server bridge uses a 24000-tick convention;
                                    // applying both clocks repeatedly replaces recorded lighting.
                                    super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);
                                    super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(graphGate){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","allowNativeGraphUpdate","(Ljava/lang/Object;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFNE,original);
                                    if(desc.endsWith("Z")){super.visitInsn(Opcodes.ICONST_0);super.visitInsn(Opcodes.IRETURN);}else super.visitInsn(Opcodes.RETURN);
                                    super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(bodyPrepare){super.visitVarInsn(Opcodes.ALOAD,1);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","bodyInitialPose","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"1118769848");super.visitVarInsn(Opcodes.ASTORE,1);super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitInsn(Opcodes.ICONST_0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","bodyPrepared","(Ljava/lang/Object;Ljava/lang/Object;I)V",false);changed[0]=true;}
                                if(extractorPrompt){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractorActivationPrompt","(Ljava/lang/Object;)Ljava/lang/String;",false);Label original=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,original);super.visitInsn(Opcodes.ARETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/String"});super.visitInsn(Opcodes.POP);changed[0]=true;}
                                if(extractorConfig){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractorActive","(Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESPECIAL,"1562480994","char","(L98008865;)V",false);super.visitVarInsn(Opcodes.ALOAD,0);super.visitInsn(Opcodes.ICONST_2);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractor","(Ljava/lang/Object;ILjava/lang/Object;)Z",false);super.visitInsn(Opcodes.POP);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;}
                                if(extractorHudDraw){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ILOAD,1);super.visitVarInsn(Opcodes.FLOAD,5);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractorHudDraw","(Ljava/lang/Object;IF)Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;}
                                if(extractorTick||extractorProgress){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractorActive","(Ljava/lang/Object;)Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);
                                    if(extractorTick){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESPECIAL,"1562480994","if","()V",false);}
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitInsn(extractorTick?Opcodes.ICONST_0:Opcodes.ICONST_1);super.visitInsn(Opcodes.ACONST_NULL);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractor","(Ljava/lang/Object;ILjava/lang/Object;)Z",false);super.visitInsn(Opcodes.POP);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(extractorRemove){super.visitVarInsn(Opcodes.ALOAD,0);super.visitInsn(Opcodes.ICONST_3);super.visitInsn(Opcodes.ACONST_NULL);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractor","(Ljava/lang/Object;ILjava/lang/Object;)Z",false);super.visitInsn(Opcodes.POP);changed[0]=true;}
                                if(sourceSnow){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceWeatherEffectsActive","()Z",false);Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;}
                                if(shakeClock){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.FLOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","shakeDelta","(Ljava/lang/Object;F)F",false);super.visitVarInsn(Opcodes.FSTORE,1);changed[0]=true;}
                                if(modelResource){
                                    super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","resource","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST,"779455578");super.visitVarInsn(Opcodes.ASTORE,1);changed[0]=true;
                                }
                                if(wallClock||environmentPacket){
                                    super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);
                                    if(wallClock){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","wallMillis","()J",false);super.visitInsn(Opcodes.LRETURN);}
                                    else{super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","environmentPacket","(Ljava/lang/Object;)V",false);super.visitInsn(Opcodes.RETURN);}
                                    super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(fpBody){
                                    super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayClock","active","Z");Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.ICONST_0);super.visitInsn(Opcodes.IRETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(capture){
                                    super.visitFieldInsn(Opcodes.GETSTATIC,"replay/ReplayRender","cursorOwned","Z");Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(component){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","componentDetails","(Ljava/lang/Object;Ljava/io/DataInput;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(prefab){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","gameObject","(Ljava/io/DataInput;)Ljava/lang/Object;",false);
                                    Label original=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,original);super.visitTypeInsn(Opcodes.CHECKCAST,"1886271463");super.visitInsn(Opcodes.ARETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Object"});super.visitInsn(Opcodes.POP);changed[0]=true;
                                }
                                if(local){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","localController","(Ljava/lang/Object;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(gun){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","itemDetails","(Ljava/lang/Object;Ljava/lang/Object;Ljava/io/DataInput;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(tail){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","tail","(Ljava/lang/Object;Ljava/lang/Object;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(applying){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","apply","(Ljava/lang/Object;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                                if(((name.equals("336545457")||name.equals("866048497")||name.equals("1072250159")||name.equals("928936779")||name.equals("1570813014"))&&method.equals("extends")||name.equals("1536302723")&&method.equals("native"))&&desc.equals("(Ljava/io/DataInput;)V")){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","read","(Ljava/lang/Object;Ljava/io/DataInput;)Z",false);
                                    Label original=new Label();super.visitJumpInsn(Opcodes.IFEQ,original);super.visitInsn(Opcodes.RETURN);super.visitLabel(original);super.visitFrame(Opcodes.F_SAME,0,null,0,null);changed[0]=true;
                                }
                            }
                            public void visitVarInsn(int op,int slot){
                                // abstract() first initializes its environment local to null.
                                // A published scene fills it immediately, skipping the live
                                // world/preset lookup while retaining native light/fog setup.
                                if(weatherShader&&method.equals("abstract")&&desc.equals("()V")&&op==Opcodes.ASTORE&&slot==1){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneEnvironmentOrOriginal","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"648055939");changed[0]=true;
                                }
                                super.visitVarInsn(op,slot);
                            }
                            public void visitFieldInsn(int op,String owner,String field,String descriptor){
                                boolean terrainDraw=method.equals("native")&&(desc.equals("(L353488815;I)I")||desc.equals("(L353488815;Ljava/util/ArrayList;)V"))||method.equals("void")&&desc.equals("(L353488815;Ljava/util/List;I)I")||method.equals("if")&&desc.equals("(L1994198637;Ljava/util/ArrayList;)V");
                                if(name.equals("1376962110")&&terrainDraw&&op==Opcodes.GETFIELD&&owner.equals("1376962110")&&field.equals("strictfp")&&descriptor.equals("L1102540382;")){
                                    super.visitInsn(Opcodes.DUP);super.visitFieldInsn(op,owner,field,descriptor);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneTerrainGrid","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"1102540382");changed[0]=true;return;
                                }
                                if(weatherShader&&owner.equals("569545768")&&op==Opcodes.PUTFIELD&&field.equals("import")&&descriptor.equals("Z")&&method.equals("strictfp")&&desc.equals("(ZFZ)V")){
                                    super.visitInsn(Opcodes.DUP2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherEnabledChange","(Ljava/lang/Object;Z)V",false);changed[0]=true;
                                }
                                super.visitFieldInsn(op,owner,field,descriptor);
                                if(weatherShader&&owner.equals("569545768")&&op==Opcodes.GETFIELD&&field.equals("synchronized")&&descriptor.equals("L648055939;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneEnvironmentOrOriginal","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"648055939");changed[0]=true;
                                }
                                if(weatherShader&&owner.equals("569545768")&&op==Opcodes.GETFIELD&&field.equals("int")&&descriptor.equals("L253290967;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherSound","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"253290967");changed[0]=true;
                                }
                            }
                            public void visitMethodInsn(int op,String owner,String called,String descriptor,boolean iface){
                                if(name.equals("1043044427")&&method.equals("throws")&&desc.equals("(L211097523;L351062380;L461253740;)V")&&owner.equals("495112732")&&called.equals("char")&&descriptor.equals("(DDD)D")){
                                    super.visitMethodInsn(op,owner,called,descriptor,iface);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectCaptureWeaponDistance","(D)D",false);changed[0]=true;return;
                                }
                                if(effectClock&&method.equals("public")&&desc.equals("(L432112703;Z)V")&&owner.equals("java/util/List")&&called.equals("isEmpty")&&descriptor.equals("()Z")){
                                    super.visitMethodInsn(op,owner,called,descriptor,iface);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectLayerEmpty","(ZLjava/lang/Object;)Z",false);changed[0]=true;return;
                                }
                                if(effectClock&&method.equals("public")&&desc.equals("(L432112703;Z)V")&&owner.equals("432112703")&&called.equals("do")&&descriptor.equals("(L432112703;)I")){
                                    super.visitMethodInsn(op,owner,called,descriptor,iface);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectLayerCount","(ILjava/lang/Object;)I",false);changed[0]=true;return;
                                }
                                if(effectClock&&(method.equals("try")||method.equals("continue"))&&desc.equals("(L432112703;FFFIL1392529543;)Z")&&owner.equals("1987590071")&&called.equals("continue")&&descriptor.equals("(FFFFFF)F")){
                                    super.visitMethodInsn(op,owner,called,descriptor,iface);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectCaptureDistance","(FLjava/lang/Object;)F",false);changed[0]=true;return;
                                }
                                if(audioConfigured&&op==Opcodes.INVOKEINTERFACE&&called.equals("try")&&descriptor.equals("(IJ)J")){
                                    // The channel is fully configured when it enters the native
                                    // registry. Preserve its handle across registry.try(id,handle).
                                    super.visitInsn(Opcodes.DUP2_X2);
                                    super.visitMethodInsn(op,owner,called,descriptor,iface);
                                    super.visitInsn(Opcodes.DUP2_X2);super.visitInsn(Opcodes.POP2);
                                    super.visitVarInsn(Opcodes.ILOAD,(access&Opcodes.ACC_STATIC)!=0?0:1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","channelConfigured","(JI)V",false);changed[0]=true;return;
                                }
                                if(weatherShader&&owner.equals("929635122")&&called.equals("break")&&descriptor.equals("(L648055939;)V")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneWeatherModifierInvoke","(Ljava/lang/Object;Ljava/lang/Object;)V",false);changed[0]=true;return;
                                }
                                if(actionGate&&owner.equals("szmods/runtime/NativeFlightHooks")&&called.equals("actionBlocked")&&descriptor.equals("()Z")){
                                    super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","actionBlocked","(Ljava/lang/Object;)Z",false);changed[0]=true;return;
                                }
                                if(weatherShader&&op==Opcodes.INVOKEVIRTUAL&&owner.equals("253290967")){
                                    if(called.equals("throw")&&descriptor.equals("(FI)V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherSoundUpdate","(Ljava/lang/Object;FI)V",false);changed[0]=true;return;}
                                    if(called.equals("byte")&&descriptor.equals("()V")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherSoundStop","(Ljava/lang/Object;)V",false);changed[0]=true;return;}
                                }
                                if(weatherZone&&owner.equals("java/lang/Math")&&called.equals("max")&&descriptor.equals("(FF)F")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherStrength","(FF)F",false);changed[0]=true;return;
                                }
                                if(weatherZone&&owner.equals("java/util/Map")&&called.equals("entrySet")&&descriptor.equals("()Ljava/util/Set;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","weatherMap","(Ljava/util/Map;)Ljava/util/Map;",false);changed[0]=true;
                                }
                                if(visualProgress&&op==Opcodes.INVOKESPECIAL&&owner.equals("154675812")&&called.equals(method)&&descriptor.equals(desc)){
                                    // The existing native boolean is followed by source progress
                                    // fields, then the unchanged UUID/ownership component base.
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode",method.equals("extends")?"visualProgressRead":"visualProgressWrite",method.equals("extends")?"(Ljava/lang/Object;Ljava/io/DataInput;)V":"(Ljava/lang/Object;Ljava/io/DataOutput;)V",false);changed[0]=true;
                                }
                                if(materialResource&&owner.equals("1198595589")&&called.equals("throw")&&descriptor.equals("(L779455578;)Ljava/io/InputStream;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","resource","(Ljava/lang/Object;)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,"779455578");changed[0]=true;
                                }
                                if(visualClock&&owner.equals("java/lang/System")&&called.equals("nanoTime")&&descriptor.equals("()J")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","visualNanos","()J",false);changed[0]=true;return;
                                }
                                if(skyClock&&owner.equals("java/lang/System")&&called.equals("nanoTime")&&descriptor.equals("()J")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","environmentNanos","()J",false);changed[0]=true;return;
                                }
                                if(questHud&&owner.equals("1630029313")&&called.equals("extends")&&descriptor.equals("(L1965382740;L1500420601;I)I")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","drawQuests","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)I",false);changed[0]=true;return;
                                }
                                if(lookup&&owner.equals("java/lang/Class")&&called.equals("getMethod")&&descriptor.equals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","publicMethod","(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",false);changed[0]=true;return;
                                }
                                if(npcArmor&&owner.equals("1422788721")&&called.equals("continue")&&descriptor.equals("()L211097523;")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","npcArmor","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST,"211097523");changed[0]=true;return;
                                }
                                if(tp&&owner.equals("szgmod/SourceRigHooks")&&called.equals("call")&&descriptor.equals("(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;")){owner="replay/ReplayRender";changed[0]=true;}
                                super.visitMethodInsn(op,owner,called,descriptor,iface);
                                if(effectClock&&owner.equals("org/lwjgl/glfw/GLFW")&&called.equals("glfwGetTime")&&descriptor.equals("()D")){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","renderSeconds","(D)D",false);changed[0]=true;}
                                if(effectMatrix&&owner.equals("447252483")&&called.equals("synchronized")&&descriptor.equals("(FFF)L447252483;")){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitInsn(method.equals("void")?Opcodes.ICONST_1:Opcodes.ICONST_0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectMatrix","(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;",false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST,"447252483");changed[0]=true;
                                }
                                if(name.equals("211097523")&&method.equals("extends")&&owner.equals("java/io/DataInput")&&called.equals("readByte")&&descriptor.equals("()B")&&++itemByteReads==2){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","itemProperty","(I)I",false);super.visitInsn(Opcodes.I2B);changed[0]=true;
                                }
                                if(name.equals("1510484702")&&method.equals("extends")&&owner.equals("java/io/DataInput")&&called.equals("readByte")&&descriptor.equals("()B")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","entityType","(I)I",false);super.visitInsn(Opcodes.I2B);changed[0]=true;
                                }
                                if(name.equals("594335374")&&method.equals("package")&&owner.equals("666192130")&&called.equals("case")&&descriptor.equals("(Ljava/io/DataInput;)I")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","packet","(I)I",false);changed[0]=true;
                                }
                                if(name.equals("1139368454")&&method.equals("goto")&&owner.equals("java/io/DataInput")&&called.equals("readUnsignedShort")&&descriptor.equals("()I")){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","member","(ILjava/lang/Object;)I",false);changed[0]=true;
                                }
                                if(name.equals("1536302723")&&method.equals("native")&&owner.equals("java/io/DataInput")&&called.equals("readShort")&&descriptor.equals("()S")){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","attribute","(I)I",false);changed[0]=true;
                                }
                            }
                            public void visitInsn(int op){
                                if(effectClock&&method.equals("public")&&desc.equals("(L432112703;Z)V")&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","effectRendered","(Ljava/lang/Object;Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(name.equals("949691181")&&method.equals("continue")&&desc.equals("(I)Z")&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sceneTerrainMeshSubmitted","(ZLjava/lang/Object;)V",false);changed[0]=true;}
                                if(gameTick&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","nativeTickCompleted","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(graphUpdate&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","actorGraphUpdated","(ZLjava/lang/Object;)V",false);changed[0]=true;}
                                if(bodyPhysicsCreate&&op==Opcodes.ARETURN){super.visitInsn(Opcodes.DUP);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","bodyPhysicsCreated","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(actorCreate&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","actorCreated","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(actorTimer&&op==Opcodes.ARETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","actorTimerConfigured","(Ljava/lang/Object;Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(bodyPrepare&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitInsn(Opcodes.ICONST_1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","bodyPrepared","(Ljava/lang/Object;Ljava/lang/Object;I)V",false);changed[0]=true;}
                                if(extractorHudUpdate&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceExtractorHudUpdated","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(weatherTick&&op==Opcodes.RETURN){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","sourceWeatherTick","()V",false);changed[0]=true;}
                                if(visualEquals&&op==Opcodes.IRETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","visualProgressEquals","(ZLjava/lang/Object;Ljava/lang/Object;)Z",false);changed[0]=true;}
                                if(soundResource&&op==Opcodes.LRETURN){super.visitInsn(Opcodes.DUP2);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","soundResourceLoaded","(JLjava/lang/String;)V",false);changed[0]=true;}
                                if(soundRelease&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.LLOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","soundResourceReleased","(IJ)V",false);changed[0]=true;}
                                if(audioEvent&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.LLOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","eventStarted","(IJ)V",false);changed[0]=true;}
                                if(audioChannel&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitVarInsn(Opcodes.LLOAD,7);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","channelStarted","(IJ)V",false);changed[0]=true;}
                                if(audioPosition&&op==Opcodes.IRETURN){super.visitInsn(Opcodes.DUP);super.visitInsn(name.equals("1069936767")?Opcodes.ICONST_1:Opcodes.ICONST_0);super.visitVarInsn(Opcodes.LLOAD,0);super.visitVarInsn(Opcodes.ILOAD,2);if(name.equals("1069936767"))super.visitInsn(Opcodes.ICONST_1);else super.visitVarInsn(Opcodes.ILOAD,3);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","audioPositioned","(IZJII)V",false);changed[0]=true;}
                                if(testCursor&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.DLOAD,0);super.visitVarInsn(Opcodes.DLOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","testCursor","(DD)V",false);changed[0]=true;}
                                if(testButton&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ILOAD,0);super.visitVarInsn(Opcodes.ILOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","testButton","(II)V",false);changed[0]=true;}
                                if(testBackend&&op==Opcodes.RETURN){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","headlessMouse","()V",false);changed[0]=true;}
                                if(bootstrap&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","consumed","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(imported&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","imported","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if(configured&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.FLOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","configured","(Ljava/lang/Object;F)V",false);changed[0]=true;}
                                if(taskConstructor&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","captureTask","(Ljava/lang/Object;)V",false);changed[0]=true;}
                                if((name.equals("859236069")||name.equals("1176115659"))&&method.equals("extends")&&desc.equals("(Ljava/io/DataInput;)V")&&op==Opcodes.RETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayDecode","after","(Ljava/lang/Object;Ljava/io/DataInput;)V",false);changed[0]=true;}
                                super.visitInsn(op);
                            }
                            public void visitIntInsn(int op,int operand){
                                if(decoder&&op==Opcodes.NEWARRAY){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","boundedAllocation","(I)I",false);changed[0]=true;}super.visitIntInsn(op,operand);
                            }
                            public void visitTypeInsn(int op,String type){
                                if(decoder&&op==Opcodes.ANEWARRAY){super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayClock","boundedAllocation","(I)I",false);changed[0]=true;}super.visitTypeInsn(op,type);
                            }
                        };
                    }
                },0);
                if(changed[0]&&Boolean.getBoolean("sz.replay.qa.input"))System.setProperty("sz.replay.transform."+name,"true");
                byte[] original=changed[0]?w.toByteArray():bytes;
                byte[] spatial=spatialFields(original);
                return spatial!=null?spatial:changed[0]?original:null;
            }
        });
    }
    static byte[] spatialFields(byte[] bytes){
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);boolean[] changed={false};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,name,desc,sig,exceptions)){
            public void visitFieldInsn(int op,String owner,String field,String type){
                int slot=owner.equals("1886271463")?switch(field){case "l"->0;case "g"->1;case "new"->2;case "if"->3;default->-1;}:-1;
                if(op==Opcodes.GETFIELD&&slot>=0&&(type.equals("L334744794;")||slot==2&&type.equals("Ljava/util/List;"))){super.visitInsn(Opcodes.DUP);super.visitFieldInsn(op,owner,field,type);super.visitLdcInsn(slot);super.visitMethodInsn(Opcodes.INVOKESTATIC,"replay/ReplayRender","scenePrefabSpatial","(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;",false);super.visitTypeInsn(Opcodes.CHECKCAST,Type.getType(type).getInternalName());changed[0]=true;}else super.visitFieldInsn(op,owner,field,type);
            }
        };}},0);return changed[0]?writer.toByteArray():null;
    }
    private static boolean viewerToolUi(String owner,String method,String desc){
        return owner.equals("925863226")&&method.equals("while")&&desc.equals("()V")
            ||owner.equals("277628396")&&method.equals("throw")&&desc.equals("()Z")
            ||owner.equals("887814735")&&method.equals("package")&&desc.equals("()Z");
    }
    private static final class SetHolder {static boolean handler(String name){return name.equals("447872792")||name.equals("928936779")||name.equals("290609376")||name.equals("1072250159");}}
}




