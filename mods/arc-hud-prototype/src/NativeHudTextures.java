import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.nanovg.NSVGImage;
import org.lwjgl.nanovg.NanoSVG;
import static org.lwjgl.opengl.GL11.*;

/** Owns only prototype SVG textures; assigns them to the native Figma image widgets.
 * No global asset registry changes and no per-frame SVG parsing or uploads.
 */
public final class NativeHudTextures implements AutoCloseable {
    private final List<Integer> textures = new ArrayList<>();

    public int bind(Object gui, Path assetRoot, Map<String, String> assetFiles) throws Exception {
        Path allowed = assetRoot.toAbsolutePath().normalize();
        List<?> images = (List<?>) gui.getClass().getMethod("finally").invoke(gui);
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int oldUnpack = glGetInteger(GL_UNPACK_ALIGNMENT);
        long rasterizer = NanoSVG.nsvgCreateRasterizer();
        if (rasterizer == 0) throw new IllegalStateException("Cannot create native SVG rasterizer");
        try {
            for (Object image : images) {
                Object key = image.getClass().getField("float").get(image);
                String relative = assetFiles.get(key.toString());
                if (relative == null) throw new IllegalArgumentException("Unmapped HUD SVG: " + key);
                Path svgPath = allowed.resolve(relative).normalize();
                if (!svgPath.startsWith(allowed)) throw new IllegalArgumentException("SVG outside asset root");
                boolean vector=svgPath.toString().endsWith(".svg");NSVGImage svg=vector?NanoSVG.nsvgParse(Files.readString(svgPath), "px", 96f):null;
                if(vector&&svg==null)throw new IllegalArgumentException("Invalid SVG: "+svgPath);
                try {
                    Object size = image.getClass().getField("new").get(image);
                    int w = Math.max(1, size.getClass().getField("try").getInt(size));
                    int h = Math.max(1, size.getClass().getField("enum").getInt(size));
                    int scale = vector?4:1;
                    if (w > 2048 || h > 2048) throw new IllegalArgumentException("Unexpected HUD icon size");
                    ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * scale * scale * 4);
                    if(vector){NanoSVG.nsvgRasterize(rasterizer, svg, 0f, 0f,(w * scale) / svg.width(), pixels, w * scale, h * scale, w * scale * 4);}else{java.awt.image.BufferedImage png=javax.imageio.ImageIO.read(svgPath.toFile());if(png==null)throw new IllegalArgumentException("Invalid game icon: "+svgPath);w=png.getWidth();h=png.getHeight();pixels=BufferUtils.createByteBuffer(w*h*4);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=png.getRGB(x,y);pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));}pixels.flip();}
                    int texture = glGenTextures();
                    textures.add(texture);
                    glBindTexture(GL_TEXTURE_2D, texture);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE_VALUE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE_VALUE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w * scale, h * scale,
                        0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    Method assign = image.getClass().getMethod("enum", int.class,
                        float.class, float.class, float.class, float.class);
                    assign.invoke(image, texture, 0f, 0f, 1f, 1f);
                } finally {
                    if(svg!=null)NanoSVG.nsvgDelete(svg);
                }
            }
            return images.size();
        } catch (Throwable failure) {
            close();
            throw failure;
        } finally {
            NanoSVG.nsvgDeleteRasterizer(rasterizer);
            glPixelStorei(GL_UNPACK_ALIGNMENT, oldUnpack);
            glBindTexture(GL_TEXTURE_2D, oldTexture);
        }
    }

    private static final int GL_CLAMP_TO_EDGE_VALUE = 0x812F;

    @Override public void close() {
        for (int texture : textures) glDeleteTextures(texture);
        textures.clear();
    }
}
