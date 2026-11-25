package szmods.runtime;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public record PlayerProfile(String name,String faction,int health,int maxHealth,int stamina,List<Item> equipment) {
    record Item(int id,String titleKey) {}
    private record ProfileView(Object connection,PlayerProfile profile) {}
    private static volatile ProfileView displayed;
    private static ProfileView opened;
    private static final ImBoolean visible = new ImBoolean();
    private static boolean renderFailed;

    public static PlayerProfile read(DataInput input) throws IOException {
        if (input.readUnsignedByte() != 1) throw new IOException("Unknown player profile version");
        String name = input.readUTF();
        String faction = input.readUTF();
        int health = input.readUnsignedShort(), maxHealth = input.readUnsignedShort(), stamina = input.readUnsignedByte();
        int count = input.readUnsignedByte();
        if (count > 12 || stamina > 100) throw new IOException("Invalid player profile fields");
        var equipment = new ArrayList<Item>(count);
        for (int i=0;i<count;i++) equipment.add(new Item(input.readUnsignedShort(),input.readUTF()));
        return new PlayerProfile(name,faction,health,maxHealth,stamina,List.copyOf(equipment));
    }

    public void play(Object connection) { displayed = new ProfileView(connection,this); }

    static void connected(Object connection) {
        var current = displayed;
        if (current != null && current.connection != connection) {
            displayed = null;
            opened = null;
        }
    }

    static void request(String name) {
        try {
            Object packet = Native.CHAT.getConstructor().newInstance();
            Native.MESSAGE.set(packet,"/profile "+name);
            Native.SEND.invoke(packet);
        } catch (ReflectiveOperationException | LinkageError error) {
            System.err.println("[Player profile] Cannot request profile: " + error);
        }
    }

    public static void render() {
        var current = displayed;
        if (current == null || renderFailed) return;
        try {
            Native.BEGIN_FRAME.invoke(Native.UI.invoke(null));
            if (opened != current) {
                opened = current;
                visible.set(true);
                ImGui.setNextWindowFocus();
            }
            ImGui.setNextWindowSize(370, 275, ImGuiCond.FirstUseEver);
            boolean content = ImGui.begin("Профиль игрока###bscp-player-profile", visible, ImGuiWindowFlags.NoDocking);
            try {
                if (content) current.profile.draw();
            } finally {
                ImGui.end();
            }
            if (!visible.get()) displayed = null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            renderFailed = true;
            System.err.println("[Player profile] Screen unavailable: " + error);
        }
    }

    private void draw() throws ReflectiveOperationException {
        ImGui.textUnformatted(name);
        ImGui.textUnformatted("Фракция  " + faction);
        ImGui.separator();
        ImGui.textUnformatted("Здоровье  " + health + " / " + maxHealth);
        ImGui.textUnformatted("Выносливость  " + stamina + "%");
        ImGui.separator();
        ImGui.textUnformatted("Экипировка");
        if (equipment.isEmpty()) ImGui.textDisabled("Нет экипированных предметов");
        for (Item item : equipment) {
            String localized = item.titleKey.isEmpty() ? "" : (String)Native.LOCALIZE.invoke(null,item.titleKey);
            ImGui.bullet();
            ImGui.sameLine();
            ImGui.textUnformatted(localized.isBlank() || localized.equals(item.titleKey) ? "Предмет #" + item.id : localized);
        }
        if (ImGui.button("Закрыть")) visible.set(false);
    }

    private static final class Native {
        static final Class<?> CHAT;
        static final Field MESSAGE;
        static final Method SEND, UI, BEGIN_FRAME, LOCALIZE;
        static {
            try {
                CHAT = Class.forName("677917411");
                MESSAGE = CHAT.getField("continue");
                SEND = FunnelImpact.nativeMethod(CHAT,"boolean",void.class);
                Class<?> imgui = Class.forName("976516049");
                UI = FunnelImpact.nativeMethod(imgui,"try",imgui);
                BEGIN_FRAME = FunnelImpact.nativeMethod(imgui,"throw",void.class);
                LOCALIZE = FunnelImpact.nativeMethod(Class.forName("255753289"),"else",String.class,String.class);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
}
