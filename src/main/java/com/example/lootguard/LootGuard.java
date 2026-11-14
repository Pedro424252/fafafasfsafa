package com.example.lootguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aesir Loot Guard - Gaks_v310
 *
 * SADECE SENIN oldurdugun oyuncu icin, kilit suresi boyunca:
 *  - ekranda "X OLDU" yazar ve eline kilic alir
 *  - kristal / glowstone / anchor eline gelirse surekli guvenli slota gecer
 *  - kristal / glowstone / anchor kullanimini ve kristale vurmayi engeller
 *
 * HIZ: tum zamanlama mikrosaniye (System.nanoTime) uzerinden. Olum paketi (status 3) netty thread'inde,
 *  ana thread'e siraya girmeden ONCE yakalanir ve kilit hemen kurulur (ClientConnectionMixin).
 *  Ayrica TAHMIN modu: sunucuya gonderilmis ama cevabi gelmemis kristal vurulari us damgasiyla sayilir;
 *  hedef bunlarla olmus olabilirse yeni kristal vurusu/yerlestirmesi ANINDA durdurulur.
 *
 * "Senin oldurdugun" su sekilde anlasilir:
 *  1) olum mesajinda senin adin gecerse (X was slain by SEN, blown up by SEN ...)
 *  2) mesaj yoksa: son 8 sn icinde ona vurduysan
 *  3) mesaj yoksa: son 4 sn icinde yakinindaki bir kristale vurduysan
 *  4) anchor: "[Intentional Game Design]" mesaji + son 4 sn icinde anchor'a tikladiysan
 */
public class LootGuard implements ClientModInitializer {
    public static volatile boolean enabled = true;
    public static volatile int lockSeconds = 5;   // kilit suresi (saniye), GUI'den ayarlanir
    public static volatile boolean predict = true;     // tahmin modu (olumden ONCE durdur)
    public static volatile int estDamage = 10;         // bir kristalin tahmini hasari (HP)

    private static final double RANGE = 30.0;           // olum algilama mesafesi (blok)
    private static final double NEAR = 20.0;            // "kayboldu" algilamasi icin mesafe
    private static final double CRYSTAL_RADIUS = 12.0;  // kristal patlamasinin etki mesafesi
    private static final double ANCHOR_RADIUS = 12.0;   // anchor patlamasinin etki mesafesi

    // --- TUM ZAMANLAR MIKROSANIYE (us). Saat: System.nanoTime() / 1000 ---
    private static final long US = 1_000_000L;                    // 1 saniye = 1_000_000 us
    private static final long DEDUPE_US = 4 * US;                 // ayni oyuncu icin tekrar algilama araligi
    private static final long HIT_WINDOW_US = 8 * US;             // vurustan sonra "benim" sayilma suresi
    private static final long CRYSTAL_WINDOW_US = 4 * US;         // kristale vurdugundan sonra sure
    private static final long ANCHOR_WINDOW_US = 4 * US;          // anchor'a tikladiktan sonra sure
    private static final long MIN_WINDOW_US = 150_000L;           // tahmin penceresi alt sinir (150 ms)
    private static final long MAX_WINDOW_US = 600_000L;           // tahmin penceresi ust sinir (600 ms)
    private static final long WINDOW_MARGIN_US = 100_000L;        // ping uzerine eklenen pay (100 ms)

    private static long nowUs() {
        return System.nanoTime() / 1000L;
    }

    private record Seen(String name, double x, double y, double z) {}
    /** Zaman damgali konum kaydi; tek nesne oldugu icin thread'ler arasi tutarli okunur. */
    private record Mark(long tUs, double x, double y, double z) {}
    /** Sunucuya gonderilmis, cevabi henuz gelmemis kristal vurusu. */
    private record Hit(int id, long tUs, double x, double y, double z) {}

    private static KeyBinding openKey;
    private static volatile long lockUntilUs = nowUs() - 1;
    private static volatile String lastName = "";
    private static volatile boolean wantSword = false;   // netty thread'inden ana thread'e "kilica gec" istegi

    // netty thread'i (paket gelisi) ile ana thread ortak kullandigi icin thread-safe
    private static final Map<String, Long> lastTrigger = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastHit = new ConcurrentHashMap<>();
    private static volatile Mark crystalMark = null;
    private static volatile Mark anchorMark = null;

    // sadece ana thread
    private static Map<UUID, Seen> prevNear = new HashMap<>();
    private static Object lastWorld = null;
    private static final ArrayDeque<Hit> inflight = new ArrayDeque<>();

    /** Tahmin penceresi (us): ping + pay, [150ms, 600ms] araliginda. */
    private static long predictWindowUs(MinecraftClient client) {
        long pingMs = 0;
        if (client.getNetworkHandler() != null && client.player != null) {
            var e = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (e != null) pingMs = e.getLatency();
        }
        return Math.max(MIN_WINDOW_US, Math.min(MAX_WINDOW_US, pingMs * 1000L + WINDOW_MARGIN_US));
    }

    /**
     * (x,y,z) civarindaki oyunculardan biri, sunucuya zaten gonderdigimiz ama cevabi henuz gelmemis
     * kristallerle olmus olabilir mi? Olabilirse true -> bu kristal islemi ENGELLENIR.
     *
     * Eski surum: hp degisince sayaci SIFIRLIYORDU. Spam'de ilk cevap gelince hala yolda olan
     * kristaller unutuluyor ve loot patliyordu. Yeni surum: her vurus kendi mikrosaniye damgasiyla
     * tutulur, SADECE pencere (ping+pay) dolunca duser. Ayni kristale tekrar vurmak tekrar sayilmaz.
     * Totemi olan hedef: her totem bir olumcul vurusu daha tolere eder.
     */
    private static boolean predictCheck(MinecraftClient client, double x, double y, double z, int crystalId, boolean count) {
        if (!enabled || !predict || client.player == null || client.world == null) return false;
        long now = nowUs();
        long win = predictWindowUs(client);
        while (!inflight.isEmpty() && now - inflight.peekFirst().tUs() > win) inflight.pollFirst();

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isDead()) continue;
            if (!within(p.getX(), p.getY(), p.getZ(), x, y, z, CRYSTAL_RADIUS)) continue;
            int flying = 0;
            for (Hit h : inflight) {
                if (within(p.getX(), p.getY(), p.getZ(), h.x(), h.y(), h.z(), CRYSTAL_RADIUS)) flying++;
            }
            float hp = p.getHealth() + p.getAbsorptionAmount();
            int totems = (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0)
                    + (p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0);
            if (hp + totems * (float) estDamage - flying * (float) estDamage <= 0f) return true;
        }
        if (count) {
            for (Hit h : inflight) if (h.id() == crystalId) return false;   // ayni kristal, tekrar sayma
            inflight.addLast(new Hit(crystalId, now, x, y, z));
        }
        return false;
    }

    public static boolean locked() {
        return enabled && nowUs() - lockUntilUs < 0;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.END_CRYSTAL) || s.isOf(Items.GLOWSTONE) || s.isOf(Items.RESPAWN_ANCHOR);
    }

    /** Bu oyuncuyu ben mi oldurdum? (mesaj yoksa vurus/kristal kaydina bakar) */
    private static boolean mine(String name, double x, double y, double z) {
        long now = nowUs();
        Long h = lastHit.get(name);
        if (h != null && now - h < HIT_WINDOW_US) return true;
        Mark c = crystalMark;
        if (c != null && now - c.tUs() < CRYSTAL_WINDOW_US && within(x, y, z, c.x(), c.y(), c.z(), CRYSTAL_RADIUS)) return true;
        Mark a = anchorMark;
        if (a != null && now - a.tUs() < ANCHOR_WINDOW_US && within(x, y, z, a.x(), a.y(), a.z(), ANCHOR_RADIUS)) return true;
        return false;
    }

    private static boolean within(double x, double y, double z, double ax, double ay, double az, double r) {
        double dx = x - ax, dy = y - ay, dz = z - az;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Thread-safe: kilidi hemen kurar (netty thread'inden de cagrilabilir). Envantere DOKUNMAZ. */
    private static void lockNow(String name) {
        long now = nowUs();
        Long last = lastTrigger.get(name);
        if (last != null && now - last < DEDUPE_US) return;
        lastTrigger.put(name, now);
        lastName = name;
        wantSword = true;
        lockUntilUs = now + lockSeconds * US;
    }

    /** Ana thread: kilit + kilica gec. */
    private static void trigger(MinecraftClient client, String name) {
        lockNow(name);
        applySword(client);
    }

    private static void applySword(MinecraftClient client) {
        if (!wantSword || client.player == null) return;
        wantSword = false;
        switchToSword(client);
    }

    /**
     * NETTY THREAD: sunucudan gelen paket ana thread'e siraya girmeden ONCE burada gorulur.
     * Olum durumu (status 3) ve olen oyuncu benimse kilit mikrosaniyeler icinde kurulur;
     * ana thread'in tick/frame'i beklenmez.
     */
    public static void onPacketNetty(Packet<?> packet) {
        if (!enabled) return;
        if (!(packet instanceof EntityStatusS2CPacket st) || st.getStatus() != 3) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientWorld w = mc.world;
            if (w == null || mc.player == null) return;
            Entity e = st.getEntity(w);
            if (!(e instanceof AbstractClientPlayerEntity p) || p == mc.player) return;
            if (mine(p.getName().getString(), p.getX(), p.getY(), p.getZ())) lockNow(p.getName().getString());
        } catch (Throwable ignored) {
            // netty thread'inde dunya okumasi nadiren yaris yaratabilir; ana thread taramasi yedek
        }
    }

    private static void switchToSword(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    /** Oncelik: kilic -> bos slot -> pearl -> totem. Bulamazsa bir sey yapmaz. */
    private static void switchToSafe(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_PEARL)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { inv.setSelectedSlot(i); return; }
        }
    }

    /** Olum taramasi. Tick basinda, tick sonunda VE her tikta (kristal/anchor) cagrilir - gecikme minimum. */
    private static void scan(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            prevNear.clear();
            inflight.clear();
            lastWorld = null;
            return;
        }
        applySword(client);   // netty thread'inin istedigi kilic gecisi
        if (!enabled) {
            prevNear.clear();
            return;
        }
        if (client.world != lastWorld) {
            lastWorld = client.world;
            prevNear.clear();
        }

        Map<UUID, Seen> curNear = new HashMap<>();
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) continue;
            present.add(p.getUuid());
            String name = p.getName().getString();
            double d2 = client.player.squaredDistanceTo(p);
            if (p.isDead()) {                       // 1) olu olarak goruldu
                if (d2 <= RANGE * RANGE && mine(name, p.getX(), p.getY(), p.getZ())) {
                    trigger(client, name);
                }
                continue;
            }
            if (d2 <= NEAR * NEAR) curNear.put(p.getUuid(), new Seen(name, p.getX(), p.getY(), p.getZ()));
        }
        // 2) yakindayken bir anda kayboldu (olu hali hic gorulmediyse)
        for (Map.Entry<UUID, Seen> e : prevNear.entrySet()) {
            if (present.contains(e.getKey())) continue;
            Seen s = e.getValue();
            if (mine(s.name(), s.x(), s.y(), s.z())) trigger(client, s.name());
        }
        prevNear = curNear;
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("lootguard", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Loot Guard"),
                        b -> MinecraftClient.getInstance().setScreen(new LootGuardScreen()))
                        .dimensions(5, 5, 110, 20).build());
            }
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> scan(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new LootGuardScreen());
            }
            scan(client);
            if (client.player == null || !enabled) return;

            if (locked() && bad(client.player.getMainHandStack())) {
                switchToSafe(client);   // kilit boyunca surekli kontrol, her tick
            }
            if (locked() && client.player.age % 5 == 0) {
                long leftUs = Math.max(0, lockUntilUs - nowUs());
                client.inGameHud.setOverlayMessage(
                        Text.literal("Aesir Loot Guard: " + lastName + " OLDU - loot korunuyor ("
                                + (leftUs / US + 1) + " sn)"), false);
            }
        });

        // 3) sohbetteki olum mesaji: sadece mesajda SENIN adin geciyorsa
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            if (!(message.getContent() instanceof TranslatableTextContent t)) return;
            String key = t.getKey();
            if (!key.startsWith("death.")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;
            String myName = client.player.getName().getString();

            // Anchor: "X was killed by [Intentional Game Design]" - mesajda katil adi yok
            if (key.startsWith("death.attack.badRespawnPoint")) {
                Object[] a = t.getArgs();
                if (a.length == 0) return;
                String v = (a[0] instanceof Text tx0) ? tx0.getString() : String.valueOf(a[0]);
                if (v.equals(myName)) return;
                Mark am = anchorMark;
                if (am == null || nowUs() - am.tUs() >= ANCHOR_WINDOW_US) return;
                boolean ok = true;   // oyuncu goruluyorsa anchor'a yakin olmali
                for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                    if (p.getName().getString().equals(v)) {
                        ok = within(p.getX(), p.getY(), p.getZ(), am.x(), am.y(), am.z(), ANCHOR_RADIUS);
                        break;
                    }
                }
                if (ok) trigger(client, v);
                return;
            }
            String victim = null;
            boolean me = false;
            Object[] args = t.getArgs();
            for (int i = 0; i < args.length; i++) {
                String s = (args[i] instanceof Text tx) ? tx.getString() : String.valueOf(args[i]);
                if (i == 0) victim = s;
                else if (s.equals(myName)) me = true;
            }
            if (me && victim != null && !victim.equals(myName)) trigger(client, victim);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            if (enabled) scan(MinecraftClient.getInstance());   // tiktan hemen once olum kontrolu
            boolean anchor = world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR);
            if (locked()) {
                if (bad(player.getStackInHand(hand)) || anchor) return ActionResult.FAIL;
                return ActionResult.PASS;
            }
            if (player.getStackInHand(hand).isOf(Items.END_CRYSTAL)
                    && predictCheck(MinecraftClient.getInstance(), hit.getBlockPos().getX() + 0.5,
                            hit.getBlockPos().getY() + 1, hit.getBlockPos().getZ() + 0.5, -1, false)) {
                return ActionResult.FAIL;   // olum tahmini: yeni kristal koyma
            }
            if (anchor && enabled) {   // anchor'a tikladim: patlarsa benim sayilir
                anchorMark = new Mark(nowUs(), hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled) scan(mc);                               // tiktan hemen once olum kontrolu
            if (entity instanceof EndCrystalEntity) {
                if (locked()) return ActionResult.FAIL;
                if (predictCheck(mc, entity.getX(), entity.getY(), entity.getZ(), entity.getId(), true)) return ActionResult.FAIL;   // yoldaki kristaller oldurmus olabilir
                crystalMark = new Mark(nowUs(), entity.getX(), entity.getY(), entity.getZ());
            } else if (entity instanceof PlayerEntity) {
                lastHit.put(entity.getName().getString(), nowUs());
            }
            return ActionResult.PASS;
        });
    }
}
