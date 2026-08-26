package laoqi123.module.modules.misc;

import laoqi123.event.EventTarget;
import laoqi123.event.impl.LoadWorldEvent;
import laoqi123.event.impl.PacketEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.module.Category;
import laoqi123.module.Module;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.FloatValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HealthBypass extends Module {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final BooleanValue spoofHealth = new BooleanValue("SpoofHealth", true);
    private final BooleanValue useScoreboard = new BooleanValue("UseScoreboard", true);
    private final BooleanValue useEmoji = new BooleanValue("Emoji", true);
    private final BooleanValue nameTag = new BooleanValue("NameTag", false);
    private final FloatValue length = new FloatValue("Length", 2F, 1F, 2F);

    private final Map<String, Float> healths = new HashMap<>();

    public HealthBypass() {
        super("HealthBypass", false);
    }

    public void onEnabled() {
        healths.clear();
    }

    public void onDisabled() {
        healths.clear();
        if (mc.world != null) {
            for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                player.setHealth(player.getHealth());
            }
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        healths.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.world == null || mc.player == null) return;

        if (nameTag.getValue()) {
            readNameTagHealths();
        } else if (useEmoji.getValue()) {
            readEmojiScores();
        }

        if (healths.isEmpty()) return;

        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            Float health = healths.get(player.getGameProfile().getName());
            if (health != null) {
                player.setHealth(Math.max(0.0F, health));
            }
        }
    }

    private void readNameTagHealths() {
        if (mc.world == null) return;
        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String name = player.getGameProfile().getName();
            Float health = getNameTagHealth(player);
            if (health != null) {
                healths.put(name, health);
            } else {
                healths.remove(name);
            }
        }
    }

    private Float getNameTagHealth(AbstractClientPlayerEntity player) {
        int maxLen = Math.round(length.getValue());
        Pattern pattern = Pattern.compile("(?<!\\d)(\\d{1," + maxLen + "}\\.\\d{1," + maxLen + "})(?!\\d)");
        StringBuilder sb = new StringBuilder();
        if (player.getDisplayName() != null) sb.append(player.getDisplayName().getString());
        if (player.getCustomName() != null) sb.append('\n').append(player.getCustomName().getString());
        Matcher matcher = pattern.matcher(sb.toString());
        if (!matcher.find()) return null;
        try {
            return Float.parseFloat(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void readEmojiScores() {
        if (mc.world == null) return;
        Scoreboard scoreboard = mc.world.getScoreboard();
        if (scoreboard == null) return;
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.LIST);
        if (objective == null || objective.getRenderType() != ScoreboardCriterion.RenderType.HEARTS) return;
        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String name = player.getGameProfile().getName();
            try {
                ScoreHolder holder = ScoreHolder.fromName(name);
                ReadableScoreboardScore score = scoreboard.getScore(holder, objective);
                if (score != null && score.getScore() >= 0) {
                    healths.put(name, (float) score.getScore());
                }
            } catch (Throwable ignored) {}
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null) return;
        Packet<?> packet = event.getPacket();

        if (!nameTag.getValue() && (useScoreboard.getValue() || useEmoji.getValue())
                && packet instanceof ScoreboardScoreUpdateS2CPacket sp) {
            String objective = sp.objectiveName();
            String name = sp.scoreHolderName();
            int score = sp.score();
            if (name.equals(mc.player.getGameProfile().getName())) return;

            boolean match = false;
            if (useScoreboard.getValue() && ("belowHealth".equalsIgnoreCase(objective) || "health".equalsIgnoreCase(objective))) {
                match = true;
            }
            if (!match && useEmoji.getValue() && mc.world != null) {
                Scoreboard scoreboard = mc.world.getScoreboard();
                if (scoreboard != null) {
                    ScoreboardObjective listObj = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.LIST);
                    if (listObj != null && listObj.getRenderType() == ScoreboardCriterion.RenderType.HEARTS
                            && listObj.getName().equalsIgnoreCase(objective)) {
                        match = true;
                    }
                }
            }
            if (match) {
                healths.put(name, (float) score);
            }
            return;
        }

        if (spoofHealth.getValue() && packet instanceof HealthUpdateS2CPacket hp) {
            if (hp.getHealth() > 20.0F) {
                event.setCancelled(true);
            }
        }
    }
}