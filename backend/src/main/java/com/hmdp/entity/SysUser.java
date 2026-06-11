package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户后台账号(管理员 / 坐席)。username 全局唯一,登录时据此解析所属租户。
 */
@Data
@Accessors(chain = true)
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 登录名(全局唯一) */
    private String username;

    /** 加密密码(salt@md5) */
    private String password;

    /** 昵称 */
    private String nickName;

    /** 角色:ADMIN 管理员 / AGENT 坐席 */
    private String role;

    /** 状态:1正常 0禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
