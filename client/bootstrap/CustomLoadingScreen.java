import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Presentation only: original loading counters, connection and window lifecycle remain intact. */
public final class CustomLoadingScreen {
    private static final Map<Long, View> views = new HashMap<>();
    private static final Map<String, Field> fields = new HashMap<>();
    private static volatile Animation animation;
    private static volatile String animationFailure;
    private static volatile String failure;
    private static volatile boolean preparing;

    public static synchronized void prepare() {
        if (preparing) return;
        preparing = true;
        Thread thread = new Thread(() -> {
            try (InputStream input = CustomLoadingScreen.class.getResourceAsStream("/szcustom/loading.gif")) {
                if (input == null) throw new IOException("Loading image unavailable");
                animation = decode(input);
            } catch (Exception e) {
                animationFailure = e.toString();
                System.err.println("[loading-screen] Could not load startup animation: " + animationFailure);
            }
        }, "Loading image");
        thread.setDaemon(true);
        thread.start();
    }

    static final class Animation {
        final BufferedImage[] frames;
        final int[] ends;
        final int duration;
        Animation(List<BufferedImage> frames, List<Integer> ends, int duration) {
            this.frames = frames.toArray(new BufferedImage[0]);
            this.ends = ends.stream().mapToInt(Integer::intValue).toArray();
            this.duration = duration;
        }
        int index(long ms) {
            int position = (int)(ms % duration);
            for (int i = 0; i < ends.length; i++) if (position < ends[i]) return i;
            return ends.length - 1;
        }
    }

    private static int attribute(IIOMetadataNode node, String key, int fallback) {
        String value = node.getAttribute(key);
        return value.isEmpty() ? fallback : Integer.parseInt(value);
    }
    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        return (IIOMetadataNode)root.getElementsByTagName(name).item(0);
    }
    static Animation decode(InputStream input) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            reader.setInput(stream);
            IIOMetadataNode descriptor = child((IIOMetadataNode)reader.getStreamMetadata()
                .getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
            int w = attribute(descriptor, "logicalScreenWidth", 1), h = attribute(descriptor, "logicalScreenHeight", 1);
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();
            int duration = 0;
            for (int i = 0; i < reader.getNumImages(true); i++) {
                IIOMetadataNode root = (IIOMetadataNode)reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode rect = child(root, "ImageDescriptor"), control = child(root, "GraphicControlExtension");
                int x = attribute(rect, "imageLeftPosition", 0), y = attribute(rect, "imageTopPosition", 0);
                BufferedImage before = copy(canvas);
                Graphics2D graphics = canvas.createGraphics();
                graphics.drawImage(reader.read(i), x, y, null); graphics.dispose();
                frames.add(copy(canvas));
                duration += Math.max(20, attribute(control, "delayTime", 10) * 10);
                ends.add(duration);
                String disposal = control.getAttribute("disposalMethod");
                if (disposal.equals("restoreToPrevious")) canvas = before;
                else if (disposal.equals("restoreToBackgroundColor")) {
                    graphics = canvas.createGraphics(); graphics.setComposite(AlphaComposite.Clear);
                    graphics.fillRect(x, y, attribute(rect, "imageWidth", w), attribute(rect, "imageHeight", h)); graphics.dispose();
                }
            }
            if (frames.isEmpty()) throw new IOException("Empty loading image");
            return new Animation(frames, ends, duration);
        } finally { reader.dispose(); }
    }
    private static BufferedImage copy(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics(); graphics.drawImage(image, 0, 0, null); graphics.dispose(); return result;
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        String key = object.getClass().getName() + ":" + name;
        Field field = fields.get(key);
        if (field == null) {
            Class<?> type = object.getClass();
            while (type != null) {
                try { field = type.getDeclaredField(name); field.setAccessible(true); break; }
                catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
            }
            if (field == null) throw new NoSuchFieldException(key);
            fields.put(key, field);
        }
        return field.get(object);
    }
    public static boolean drawStartup(Object state, float delta) {
        try {
            int w = (Integer)field(state, "extends"), h = (Integer)field(state, "if");
            int done = ((AtomicInteger)field(state, "this")).get(), total = ((AtomicInteger)field(state, "instanceof")).get();
            boolean split = ((AtomicBoolean)field(state, "final")).get(), second = ((AtomicBoolean)field(state, "new")).get();
            String task = clean((String)((AtomicReference<?>)field(state, "return")).get());
            float progress = total > 0 ? Math.max(0, Math.min(1, (float)done / total)) : -1;
            if (progress >= 0 && split) progress = (second ? .5f : 0) + progress * .5f;
            return draw(state, w, h, task.isEmpty() ? "Подготовка игры" : task,
                done, total, progress, split ? (second ? 2 : 1) : 0, false);
        } catch (Exception e) { failure = e.toString(); return false; }
    }
    public static boolean drawWorld(Object screen, float delta) {
        try {
            // Keep the original actionable error/confirmation UI, including its buttons.
            String status = String.valueOf(field(screen, "short"));
            if (status.equals("DISCONNECTED") || status.equals("OUTDATED")) return false;
            if ((Boolean)field(screen, "static")) return false;
            String task = clean((String)field(screen, "native"));
            Object connection = field(screen, "else");
            Method method = connection.getClass().getMethod("do");
            String stage = String.valueOf(method.invoke(connection));
            String title = switch (stage) {
                case "BACKEND_LOGIN" -> "Авторизация";
                case "GAME_SERVER_CONNECTED" -> "Загрузка игрового мира";
                default -> "Подключение к игре";
            };
            int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            return draw(screen, viewport[2], viewport[3], title + (task.isEmpty() ? "" : " · " + task),
                -1, -1, -1, 0, true);
        } catch (Exception e) { failure = e.toString(); return false; }
    }
    private static String clean(String task) {
        if (task == null) return "";
        // Never expose query parameters or credentials even if an upstream task includes a URL.
        String value = task.replaceAll("https?://\\S+", "[сетевой ресурс]").replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.length() > 280 ? value.substring(0, 277) + "…" : value;
    }
    private static boolean draw(Object owner, int width, int height, String task, int done, int total,
                                float progress, int phase, boolean world) {
        if (width < 1 || height < 1 || failure != null && animation == null) return false;
        prepare();
        long context = GLFW.glfwGetCurrentContext();
        if (context == 0) return false;
        State saved = new State();
        try {
            View view = views.get(context);
            if (view == null) { view = new View(); views.put(context, view); }
            view.render(owner, width, height, task, done, total, progress, phase, world);
            return true;
        } finally { saved.restore(); }
    }
    public static void closeContext() {
        View view = views.remove(GLFW.glfwGetCurrentContext());
        if (view != null) view.close();
    }
    public static String diagnostic() { return failure != null ? failure : animationFailure; }

    private static final class State {
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        final int srcRGB = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRGB = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int eqRGB = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        final int texture;
        State() { GL13.glActiveTexture(GL13.GL_TEXTURE0); texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); }
        void restore() {
            GL20.glUseProgram(program); GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); GL13.glActiveTexture(active); GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack);
            GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); GL20.glBlendEquationSeparate(eqRGB, eqAlpha);
            enabled(GL11.GL_DEPTH_TEST, depth); enabled(GL11.GL_CULL_FACE, cull);
            enabled(GL11.GL_BLEND, blend); enabled(GL11.GL_SCISSOR_TEST, scissor);
        }
        static void enabled(int flag, boolean value) { if (value) GL11.glEnable(flag); else GL11.glDisable(flag); }
    }
    private static final class View {
        final int program, vao, buffer, sizeUniform, imageTexture, panelTexture;
        final FloatBuffer vertices = BufferUtils.createFloatBuffer(16);
        ByteBuffer pixels;
        Object owner;
        long start = System.nanoTime(), refresh, sample = start, frames, lastFrameTime = start;
        int previousDone, previousTotal, previousPhase, imageIndex = -1, panelW, panelH;
        String previousTask = "";
        double rate, fps;
        boolean captured;
        final Deque<String> history = new ArrayDeque<>();
        View() {
            String vertex = "#version 330 core\nlayout(location=0) in vec2 p;layout(location=1) in vec2 u;uniform vec2 size;out vec2 uv;void main(){uv=u;gl_Position=vec4(p.x/size.x*2-1,1-p.y/size.y*2,0,1);}";
            String fragment = "#version 330 core\nin vec2 uv;uniform sampler2D image;out vec4 color;void main(){color=texture(image,uv);}";
            int vs = shader(GL20.GL_VERTEX_SHADER, vertex), fs = shader(GL20.GL_FRAGMENT_SHADER, fragment);
            program = GL20.glCreateProgram(); GL20.glAttachShader(program, vs); GL20.glAttachShader(program, fs); GL20.glLinkProgram(program);
            GL20.glDeleteShader(vs); GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("Loading shader link failed");
            sizeUniform = GL20.glGetUniformLocation(program, "size");
            vao = GL30.glGenVertexArrays(); buffer = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STREAM_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
            GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);
            imageTexture = texture(); panelTexture = texture();
        }
        private static int shader(int type, String source) {
            int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) { GL20.glDeleteShader(shader); throw new IllegalStateException("Loading shader compilation failed"); }
            return shader;
        }
        private static int texture() {
            int id = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE); return id;
        }
        void render(Object owner, int w, int h, String task, int done, int total, float progress, int phase, boolean world) {
            long now = System.nanoTime();
            if (this.owner != owner) {
                this.owner = owner; start = sample = now; previousDone = done; previousTotal = total; previousPhase = phase;
                previousTask = ""; history.clear(); rate = 0; captured = false;
            }
            frames++;
            if (now - lastFrameTime >= 1_000_000_000L) { fps = frames * 1e9 / (now - lastFrameTime); frames = 0; lastFrameTime = now; }
            if (phase != previousPhase || total != previousTotal || done < previousDone) {
                sample = now; previousDone = done; previousTotal = total; previousPhase = phase; rate = 0;
            } else if (now - sample >= 1_000_000_000L) {
                rate = Math.max(0, done - previousDone) * 1e9 / (now - sample); sample = now; previousDone = done;
            }
            if (!task.equals(previousTask)) {
                if (!previousTask.isEmpty()) { history.addFirst(previousTask); while (history.size() > 3) history.removeLast(); }
                previousTask = task;
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquation(GL14.GL_FUNC_ADD); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(program); GL20.glUniform2f(sizeUniform, w, h); GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int informationWidth = Math.max(200, Math.min(1020, w - 48));
            int informationHeight = Math.max(190, Math.min(310, h / 2));
            int informationY = h - informationHeight - 20;
            if (now >= refresh || panelW != informationWidth || panelH != informationHeight) {
                panelW = informationWidth; panelH = informationHeight;
                upload(panelTexture, information(task, done, total, progress, phase, world, now, w, h));
                refresh = now + 250_000_000L;
            }
            // Opaque full-screen background without changing the engine's glClearColor.
            if (background == 0) {
                background = texture(); BufferedImage color = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                color.setRGB(0, 0, 0xff101115); upload(background, color);
            }
            quad(background, 0, 0, w, h);
            Animation gif = animation;
            if (gif != null) {
                int index = gif.index((now - start) / 1_000_000L);
                if (index != imageIndex) { upload(imageTexture, gif.frames[index]); imageIndex = index; }
                float scale = Math.max(0.01f, Math.min((w - 80f) / gif.frames[0].getWidth(), (informationY - 48f) / gif.frames[0].getHeight()));
                float gw = gif.frames[0].getWidth() * scale, gh = gif.frames[0].getHeight() * scale;
                quad(imageTexture, (w - gw) / 2, (informationY - gh) / 2, gw, gh);
            }
            quad(panelTexture, (w - panelW) / 2f, informationY, panelW, panelH);
            capture(now, w, h, world);
        }
        int background;
        BufferedImage information(String task, int done, int total, float progress, int phase, boolean world, long now, int w, int h) {
            BufferedImage image = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(24, 26, 32)); g.fillRoundRect(0, 0, panelW, panelH, 18, 18);
            int font = panelH < 250 ? 14 : 17, line = font + 7, y = 30;
            g.setFont(new Font("Segoe UI", Font.BOLD, font + 4)); g.setColor(new Color(240, 241, 245));
            g.drawString("SZCustom · " + (world ? "Подключение" : "Загрузка игры"), 20, y); y += line + 10;
            g.setFont(new Font("Segoe UI", Font.PLAIN, font));
            if (progress >= 0) {
                g.setColor(new Color(51, 55, 65)); g.fillRoundRect(20, y, panelW - 40, 7, 7, 7);
                g.setColor(new Color(255, 155, 61)); g.fillRoundRect(20, y, Math.round((panelW - 40) * progress), 7, 7, 7); y += line;
                g.setColor(new Color(225, 227, 234));
                g.drawString(String.format(Locale.ROOT, "%.1f%%  ·  Ресурсы этапа: %,d / %,d%s", progress * 100, done, total,
                    phase == 0 ? "" : "  ·  Этап " + phase + " / 2"), 20, y); y += line;
            } else {
                g.setColor(new Color(225, 227, 234)); g.drawString("Ожидание игры / сервера · процент недоступен", 20, y); y += line;
            }
            g.setColor(Color.WHITE);
            y = wrap(g, task, y, line, 2);
            Runtime runtime = Runtime.getRuntime();
            double elapsed = (now - start) / 1e9;
            String eta = rate > 0 && done >= 0 && total > done ? " · Осталось на этапе ~" + seconds((total - done) / rate) : "";
            g.setColor(new Color(178, 184, 199));
            String timing = "Время: " + seconds(elapsed) + (total > 0 ? String.format(Locale.ROOT, " · Скорость: %.0f ресурсов/с", rate) : "") + eta;
            y = wrap(g, timing, y, line, 2);
            g.drawString(String.format(Locale.ROOT, "Память: %d / %d МБ · FPS: %.0f · %d×%d",
                (runtime.totalMemory() - runtime.freeMemory()) / 1048576, runtime.maxMemory() / 1048576, fps, w, h), 20, y); y += line;
            for (String old : history) {
                if (y + line > panelH - 10) break;
                g.setColor(new Color(126, 133, 150)); wrap(g, "Ранее: " + old, y, line, 1); y += line;
            }
            g.dispose(); return image;
        }
        int wrap(Graphics2D g, String text, int y, int line, int lines) {
            int limit = panelW - 40;
            for (int i = 0; i < lines && !text.isEmpty(); i++) {
                int end = text.length();
                while (end > 1 && g.getFontMetrics().stringWidth(text.substring(0, end) + (end < text.length() ? "…" : "")) > limit) end--;
                g.drawString(text.substring(0, end) + (i == lines - 1 && end < text.length() ? "…" : ""), 20, y);
                text = text.substring(end).trim(); y += line;
            }
            return y;
        }
        static String seconds(double amount) {
            long seconds = Math.max(0, Math.round(amount)); return seconds >= 60 ? seconds / 60 + "м " + seconds % 60 + "с" : seconds + "с";
        }
        void upload(int texture, BufferedImage image) {
            int w = image.getWidth(), h = image.getHeight(), size = w * h * 4;
            if (pixels == null || pixels.capacity() < size) pixels = BufferUtils.createByteBuffer(size);
            pixels.clear();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            for (int color : argb) pixels.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color).put((byte)(color >> 24));
            pixels.flip(); GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        }
        void quad(int texture, float x, float y, float w, float h) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); vertices.clear();
            vertices.put(x).put(y).put(0).put(0).put(x + w).put(y).put(1).put(0)
                .put(x).put(y + h).put(0).put(1).put(x + w).put(y + h).put(1).put(1).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer); GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertices); GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        }
        void capture(long now, int w, int h, boolean world) {
            // Maintainer-only verification, completely inactive in normal launches.
            String folder = System.getProperty("szcustom.loading.captureDir");
            if (folder == null || captured || now - start < 2_000_000_000L || animation == null) return;
            captured = true;
            try {
                ByteBuffer rgba = BufferUtils.createByteBuffer(w * h * 4); GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int index = ((h - 1 - y) * w + x) * 4;
                    image.setRGB(x, y, 0xff000000 | (rgba.get(index) & 255) << 16 | (rgba.get(index + 1) & 255) << 8 | (rgba.get(index + 2) & 255));
                }
                Path destination = Path.of(folder); Files.createDirectories(destination);
                ImageIO.write(image, "png", destination.resolve(world ? "world.png" : "startup.png").toFile());
            } catch (Exception ignored) { }
        }
        void close() {
            GL11.glDeleteTextures(imageTexture); GL11.glDeleteTextures(panelTexture); if (background != 0) GL11.glDeleteTextures(background);
            GL15.glDeleteBuffers(buffer); GL30.glDeleteVertexArrays(vao); GL20.glDeleteProgram(program);
        }
    }
}
