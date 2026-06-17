package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Ticket;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface TicketMapper extends BaseMapper<Ticket> {

    /** 按状态分组统计本租户工单数(M7 看板坐席效率) */
    @Select("SELECT status AS status, COUNT(*) AS cnt FROM ticket WHERE tenant_id = #{tenantId} GROUP BY status")
    List<Map<String, Object>> countByStatus(@Param("tenantId") Long tenantId);

    /** 平均等待时长(秒):建单 → 被接入;仅统计已接入的工单 */
    @Select("SELECT AVG(TIMESTAMPDIFF(SECOND, create_time, assign_time)) FROM ticket "
            + "WHERE tenant_id = #{tenantId} AND assign_time IS NOT NULL")
    Double avgWaitSeconds(@Param("tenantId") Long tenantId);

    /** 平均处理时长(秒):被接入 → 结束;仅统计已结束的工单 */
    @Select("SELECT AVG(TIMESTAMPDIFF(SECOND, assign_time, close_time)) FROM ticket "
            + "WHERE tenant_id = #{tenantId} AND assign_time IS NOT NULL AND close_time IS NOT NULL")
    Double avgHandleSeconds(@Param("tenantId") Long tenantId);
}
