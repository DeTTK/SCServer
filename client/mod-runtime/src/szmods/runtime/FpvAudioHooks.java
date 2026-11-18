package szmods.runtime;
/** Published on the game thread, consumed by the native audio thread without mod callbacks. */
public final class FpvAudioHooks {
 private static volatile float[] listener;
 public static void setListener(double[] values){
  if(values==null){listener=null;return;}
  if(values.length!=12)throw new IllegalArgumentException("Listener needs position, velocity, forward and up");
  float[] copy=new float[12];for(int i=0;i<12;i++){copy[i]=(float)values[i];if(!Float.isFinite(copy[i]))throw new IllegalArgumentException("Nonfinite listener");}listener=copy;
 }
 public static float[] listener(){return listener;}
}
