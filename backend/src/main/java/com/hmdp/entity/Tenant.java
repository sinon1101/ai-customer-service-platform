package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户:一套部署服务 N 家企业,各自数据/配置逻辑隔离。
 */
@Data
@Accessors(chain = true)
@TableName("tenant")
public class Tenant implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业名称 */
    private String name;

    /** 租户编码(全局唯一,用作 Redis key 前缀) */
    private String code;

    /** 状态:1正常 0禁用 */
    private Integer status;

    /** 每日调用配额 */
    private Integer dailyQuota;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
