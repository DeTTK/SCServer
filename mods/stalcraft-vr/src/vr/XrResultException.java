package vr;
import static org.lwjgl.openxr.XR10.*;

final class XrResultException extends IllegalStateException {
    final int result;
    XrResultException(int result){super("OpenXR result "+result);this.result=result;}
    static boolean disconnected(Throwable error){for(int i=0;error!=null&&i<16;i++,error=error.getCause())if(error instanceof XrResultException xr&&(xr.result==XR_ERROR_INSTANCE_LOST||xr.result==XR_ERROR_SESSION_LOST))return true;return false;}
}
