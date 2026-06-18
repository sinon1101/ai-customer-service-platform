package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 人工坐席工单(M6):机器人答不了 / 用户主动转人工 → 生成工单进待接入池 → 多坐席抢单。
 * <p>
 * id 由 {@link com.hmdp.utils.RedisIdWorker} 生成(非自增),故 {@code IdType.INPUT}。
 * 抢单的事实源是本表:抢单 = {@code UPDATE ... WHERE id=? AND status='WAITING'} 看影响行数,
 * 由 InnoDB 行锁保证「只有一个坐席抢到」(外层再套 Redisson 锁做纵深防御)。
 */
@Data
@Accessors(chain = true)
@TableName("ticket")
public class Ticket implements Serializable {

    private static final long serialVersionUID = 1L;

    // 雪花 ID 超过 JS Number 安全整数范围(2^53),必须以字符串返回前端,否则末位精度丢失
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 关联的对话ID(链到 chat 多轮历史) */
    private String conversationId;

    /** 关联知识库(可空) */
    private Long kbId;

    /** 访客(发起转人工的登录账号;游客为雪花 ID,同样以字符串返回) */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long visitorUserId;

    /** 访客昵称(冗余) */
    private String visitorName;

    /** 转人工原因:USER_REQUEST / BOT_FAILED / NOT_FOUND */
    private String reason;

    /** 触发转人工的最后一问(给坐席的上下文) */
    private String lastQuestion;

    /** 状态:WAITING / ASSIGNED / CLOSED */
    private String status;

    /** 抢到工单的坐席账号 */
    private Long agentId;

    /** 坐席昵称(冗余) */
    private String agentName;

    private LocalDateTime createTime;

    /** 被接入时间 */
    private LocalDateTime assignTime;

    /** 结束时间 */
    private LocalDateTime closeTime;
}
