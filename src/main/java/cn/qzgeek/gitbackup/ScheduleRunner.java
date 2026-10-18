package cn.qzgeek.gitbackup;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 定时器：Quartz 六段表达式（秒 分 时 日 月 星期），在异步线程里推进，不占用任何区域线程。
 * 每个计划独立计算下一次触发时间，因此可以任意增删多个不同频率的计划。
 */
final class ScheduleRunner {
    record Slot(GitBackupConfig.Schedule schedule, Cron cron, ExecutionTime execution, ZonedDateTime next, String error) {
        boolean valid() { return error == null; }
    }

    private final Logger logger;
    private final List<Slot> slots = new ArrayList<>();

    ScheduleRunner(Logger logger) { this.logger = logger; }

    /** 解析全部计划。非法表达式只影响该条计划，并在启动日志中明确报错。 */
    void load(List<GitBackupConfig.Schedule> schedules) {
        slots.clear();
        CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        List<ZonedDateTime> now = List.of(ZonedDateTime.now());
        for (GitBackupConfig.Schedule s : schedules) {
            try {
                Cron cron = parser.parse(s.cron().trim());
                cron.validate();
                ExecutionTime et = ExecutionTime.forCron(cron);
                Optional<ZonedDateTime> next = et.nextExecution(now.get(0));
                if (next.isEmpty()) throw new IllegalArgumentException("该表达式不会再触发");
                slots.add(new Slot(s, cron, et, next.get(), null));
            } catch (Exception e) {
                logger.severe("定时计划 " + s.id() + " 的 cron 表达式无效: " + s.cron() + " → " + e.getMessage());
                slots.add(new Slot(s, null, null, null, e.getMessage()));
            }
        }
    }

    /** 校验单个表达式是否合法，供命令与测试使用。 */
    static String validate(String expression) {
        try {
            CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
            Cron cron = parser.parse(expression.trim());
            cron.validate();
            Optional<ZonedDateTime> next = ExecutionTime.forCron(cron).nextExecution(ZonedDateTime.now());
            if (next.isEmpty()) return "该表达式不会再触发";
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 推进一次；到期的计划通过回调触发。 */
    void tick(Consumer<GitBackupConfig.Schedule> fire) {
        ZonedDateTime now = ZonedDateTime.now();
        List<Slot> updated = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (!slot.valid()) { updated.add(slot); continue; }
            Slot current = slot;
            while (!current.next().isAfter(now)) {
                try {
                    fire.accept(current.schedule());
                } catch (RuntimeException e) {
                    logger.severe("触发定时备份 " + current.schedule().id() + " 失败: " + e.getMessage());
                }
                Optional<ZonedDateTime> next = current.execution().nextExecution(current.next());
                if (next.isEmpty()) { current = new Slot(current.schedule(), current.cron(), current.execution(), null, "已无后续触发时间"); break; }
                current = new Slot(current.schedule(), current.cron(), current.execution(), next.get(), null);
            }
            updated.add(current);
        }
        slots.clear();
        slots.addAll(updated);
    }

    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Slot s : slots) {
            if (!s.valid()) out.add(s.schedule().id() + " → 无效: " + s.error());
            else {
                Duration d = Duration.between(ZonedDateTime.now(), s.next());
                out.add(s.schedule().id() + " [" + s.schedule().cron() + "] 下次 " + s.next().toLocalDateTime()
                        + "（约 " + Math.max(0, d.toMinutes()) + " 分钟后）");
            }
        }
        return out;
    }

    /** 生成一份"当前各计划下次触发时间"的快照，用于 status 展示。 */
    Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Slot s : slots) out.put(s.schedule().id(), s.valid() ? s.next().toString() : "无效: " + s.error());
        return out;
    }
}
