package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.DocUploadDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.KbDocument;

public interface IKbDocumentService extends IService<KbDocument> {

    /** 上传文档到当前租户的某个知识库:落库 PENDING + 发 MQ,立即返回 docId */
    Result upload(Long kbId, DocUploadDTO form);

    /** 列出某知识库下当前租户的文档(带处理状态) */
    Result listByKb(Long kbId);

    /** 取当前租户名下的文档,拿不到(不存在或不属于本租户)返回 null,杜绝越权 */
    KbDocument getOwned(Long docId);
}
