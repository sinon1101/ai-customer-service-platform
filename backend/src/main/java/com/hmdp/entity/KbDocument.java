package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档:租户隔离,异步摄入管道的载体。
 * 上传即落库(PENDING),消费端切片+向量化后推进 status,并回写 chunk_count。
 */
@Data
@Accessors(chain = true)
@TableName("kb_document")
public class KbDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 所属知识库 */
    private Long kbId;

    /** 文档名 */
    private String name;

    /** 来源类型:TEXT / MD */
    private String sourceType;

    /** 原始文本内容(本版纯文本/Markdown) */
    private String content;

    /** 字符数 */
    private Integer charCount;

    /** 切片数(向量化完成后回写) */
    private Integer chunkCount;

    /** 状态:PENDING / PROCESSING / COMPLETED / FAILED */
    private String status;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
