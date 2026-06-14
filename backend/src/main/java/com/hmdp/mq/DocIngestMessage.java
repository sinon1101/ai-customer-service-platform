package com.hmdp.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档摄入消息体:只带定位信息,消费端按 docId 回表取内容,避免消息体过大。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocIngestMessage {
    private Long tenantId;
    private Long kbId;
    private Long docId;
}
