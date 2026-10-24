package com.song.im.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.im.mq.MessageEvent;
import com.song.im.mq.MessageEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Outbox 投递器：把"待办表"里的事件真正发到 MQ。
 *
 * <p>为什么需要它：业务事务里发 MQ 是"双写"，MQ 慢/挂都会让消息的推送永久丢失。
 * 改成"事务里只写库（业务表 + 待办表）"，再由本类异步补发，就变成
 * <b>至少一次</b>投递：发失败会重试，发成功才标记完成。</p>
 *
 * <p>并发安全：{@code claimBatch} 用 {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * 抢占，多实例同时扫也不会重复处理同一行。</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单批最多处理多少条 */
    private static final int BATCH_SIZE = 100;

    /** 最大重试次数，超过转死信 */
    private static final int MAX_RETRY = 5;

    /** "发送中"超过这个秒数没结果，视为卡住，回收重发 */
    private static final long STUCK_SECONDS = 60;

    private final OutboxRepository outboxRepository;

    private final MessageEventPublisher eventPublisher;

    public OutboxRelay(OutboxRepository outboxRepository, MessageEventPublisher eventPublisher) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 每秒扫一次；MESSAGE.created 走这里补发 */
    @Scheduled(fixedDelayString = "${im.outbox.interval-ms:1000}")
    public void relay() {
        try {
            List<OutboxRepository.OutboxRow> batch = claim();
            if (batch.isEmpty()) {
                return;
            }
            log.debug("Outbox 取到 {} 条待发事件", batch.size());
            for (OutboxRepository.OutboxRow row : batch) {
                dispatch(row);
            }
        } catch (Exception e) {
            // 定时任务异常必须自吞，否则调度线程会被取消
            log.error("Outbox 投递异常", e);
        }
    }

    /** 抢占必须在事务里，否则 SKIP LOCKED 的行锁立刻释放，起不到互斥作用 */
    @Transactional
    public List<OutboxRepository.OutboxRow> claim() {
        return outboxRepository.claimBatch(BATCH_SIZE);
    }

    private void dispatch(OutboxRepository.OutboxRow row) {
        try {
            if (MessageEventPublisher.EVENT_MESSAGE_CREATED.equals(row.eventType())) {
                MessageEvent event = MAPPER.readValue(row.payload(), MessageEvent.class);
                eventPublisher.publish(event);
            } else {
                log.warn("未知的 outbox 事件类型 type={} id={}", row.eventType(), row.id());
            }
            outboxRepository.markSent(row.id());
        } catch (Exception e) {
            int retry = row.retry() + 1;
            boolean dead = retry >= MAX_RETRY;
            // 指数退避：1s、2s、4s、8s、16s
            long delaySec = Math.min(1L << Math.min(retry, 5), 60L);
            Timestamp nextAt = Timestamp.from(Instant.now().plusSeconds(delaySec));

            outboxRepository.markFailed(row.id(), retry, nextAt, dead);
            log.warn("Outbox 投递失败 id={} type={} retry={} 下次={} {}",
                    row.id(), row.eventType(), retry, nextAt, dead ? "已转死信" : "");
        }
    }

    /** 每分钟：回收卡住的事件 + 清理已发送的历史 */
    @Scheduled(fixedDelay = 60_000)
    public void housekeeping() {
        try {
            int reset = outboxRepository.resetStuck(STUCK_SECONDS);
            if (reset > 0) {
                log.warn("回收卡在发送中的事件 {} 条", reset);
            }
            int deleted = outboxRepository.deleteSentBefore(72);
            if (deleted > 0) {
                log.info("清理已发送的 outbox 记录 {} 条", deleted);
            }
        } catch (Exception e) {
            log.warn("Outbox 清理失败: {}", e.getMessage());
        }
    }
}
