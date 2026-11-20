import java.nio.file.*;import java.lang.reflect.*;import java.util.*;
public class NativeLoaderProbe {
 public static void main(String[] args)throws Exception {
  String root=Path.of(args[0]).toAbsolutePath().toString();System.setProperty("org.lwjgl.librarypath",root);
  Class.forName("1487310445").getMethod("instanceof").invoke(null);
  Field field=Class.forName("jdk.internal.loader.NativeLibraries").getDeclaredField("loadedLibraryNames");field.setAccessible(true);Set<?> names=(Set<?>)field.get(null);
  String[] required={"fmod/x64/fmod.dll","fmod/x64/fmodstudio.dll","eos/x64/EOSSDK-Win64-Shipping.dll","eos/x64/xaudio2_9redist.dll","stove/BaseSDK.dll","stove/GameSupportSDK.dll","stove/IAPSDK.dll","stove/LogSDK.dll","stove/OwnershipSDK.dll","stove/PCBangSDK.dll","stove/WebView2Loader.dll"};
  boolean loaded=java.util.Arrays.stream(required).allMatch(suffix->names.stream().anyMatch(n->n.toString().replace('\\','/').endsWith(suffix)));
  if(loaded!=Boolean.parseBoolean(args[1]))throw new AssertionError("All nested dependencies loaded="+loaded+"; loaded="+names);
  System.out.println("PASS all 11 nested native libraries loaded="+loaded);System.exit(0);
 }
}
