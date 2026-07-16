package com.tongji.storage.api;

import com.tongji.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageControllerUploadPolicyTest {

    private StorageController controller;

    @BeforeEach
    void setUp() {
        // 本测试只验证纯类型策略，不需要连接 OSS、JWT 或数据库。
        controller = new StorageController(null, null, null);
    }

    @Test
    void shouldAllowSupportedImageTypes() {
        assertEquals(".jpg", controller.normalizeExt("jpg", "image/jpeg", "knowpost_image"));
        assertEquals(".png", controller.normalizeExt(".PNG", "image/png", "knowpost_image"));
        assertEquals(".webp", controller.normalizeExt(".webp", "image/webp", "knowpost_image"));
    }

    @Test
    void shouldRejectExecutableOrMismatchedImageTypes() {
        assertThrows(BusinessException.class,
                () -> controller.normalizeExt(".svg", "image/svg+xml", "knowpost_image"));
        assertThrows(BusinessException.class,
                () -> controller.normalizeExt(".png", "image/jpeg", "knowpost_image"));
    }

    @Test
    void shouldKeepMarkdownContentUploadCompatible() {
        assertEquals(".md", controller.normalizeExt(".md", "text/markdown", "knowpost_content"));
    }
}
