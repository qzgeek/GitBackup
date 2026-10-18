package cn.qzgeek.gitbackup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** 聊天栏 TUI 组件构造。纯文本 + 可点击，终端与游戏内都清晰可读。 */
final class Text {
    private Text() {}

    static Component line(String text) {
        return Component.text(text).color(NamedTextColor.GRAY);
    }

    static Component title(String text) {
        return Component.text("══ " + text + " ══").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
    }

    static Component ok(String text) { return Component.text("✔ " + text).color(NamedTextColor.GREEN); }
    static Component warn(String text) { return Component.text("! " + text).color(NamedTextColor.YELLOW); }
    static Component error(String text) { return Component.text("✘ " + text).color(NamedTextColor.RED); }
    static Component note(String text) { return Component.text("» " + text).color(NamedTextColor.AQUA); }

    static Component button(String label, String command, String hover) {
        Component c = Component.text("[" + label + "]").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD);
        if (command != null && !command.isBlank()) c = c.clickEvent(ClickEvent.runCommand(command));
        if (hover != null && !hover.isBlank()) c = c.hoverEvent(HoverEvent.showText(Component.text(hover).color(NamedTextColor.GRAY)));
        return c;
    }

    static Component row(Component... parts) {
        Component out = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out = out.append(Component.text(" ").color(NamedTextColor.DARK_GRAY));
            out = out.append(parts[i]);
        }
        return out;
    }

}
