package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.KnowledgeBaseFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.KnowledgeBase;

public interface IKnowledgeBaseService extends IService<KnowledgeBase> {

    /** 在当前登录租户下创建知识库 */
    Result create(KnowledgeBaseFormDTO form);

    /** 列出当前租户的知识库 */
    Result listCurrentTenant();

    /** 查询当前租户的某个知识库(越权返回失败) */
    Result getOne(Long id);

    /** 更新当前租户的某个知识库 */
    Result update(Long id, KnowledgeBaseFormDTO form);

    /** 删除当前租户的某个知识库 */
    Result remove(Long id);

    /** 该知识库是否属于当前登录租户(RAG 提问时校验 kbId 归属,杜绝越权检索) */
    boolean isOwned(Long id);
}
