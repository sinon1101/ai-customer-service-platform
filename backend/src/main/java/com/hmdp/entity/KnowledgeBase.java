package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库:租户隔离的实体,所有查询都按当前租户的 tenant_id 过滤。
 */
@Data
@Accessors(chain = true)
@TableName("knowledge_base")
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 知识库名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 状态:1启用 0禁用 */
    private Integer status;

    /** 文档数(冗余计数) */
    private Integer docCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
