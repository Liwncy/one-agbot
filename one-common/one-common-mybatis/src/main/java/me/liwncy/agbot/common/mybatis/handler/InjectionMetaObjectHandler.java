package me.liwncy.agbot.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import me.liwncy.agbot.common.mybatis.core.domain.BaseEntity;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 自动填充 create/update 时间与操作者。当前无登录体系，操作者填 -1。
 */
public class InjectionMetaObjectHandler implements MetaObjectHandler {

    public static final Long DEFAULT_USER_ID = -1L;

    @Override
    public void insertFill(MetaObject metaObject) {
        if (!(metaObject.getOriginalObject() instanceof BaseEntity entity)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(now);
        }
        entity.setUpdateTime(entity.getCreateTime());
        if (entity.getCreateBy() == null) {
            entity.setCreateBy(DEFAULT_USER_ID);
        }
        if (entity.getUpdateBy() == null) {
            entity.setUpdateBy(entity.getCreateBy());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (!(metaObject.getOriginalObject() instanceof BaseEntity entity)) {
            return;
        }
        entity.setUpdateTime(LocalDateTime.now());
        if (entity.getUpdateBy() == null) {
            entity.setUpdateBy(DEFAULT_USER_ID);
        }
    }
}
