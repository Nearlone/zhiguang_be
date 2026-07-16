package com.tongji.knowpost.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowPostRagControllerValidationTest {

    private KnowPostRagController controller;
    private Validator validator;
    private Method qaStream;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        // 本测试只检查方法参数注解，不会执行 Controller，因此两个业务依赖可以为空。
        controller = new KnowPostRagController(null, null, null);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        // ExecutableValidator 需要 Method 对象，借此校验 qaStream 四个入参上的约束。
        qaStream = KnowPostRagController.class.getMethod(
                "qaStream", long.class, String.class, int.class, int.class);
    }

    @Test
    void rejectsParametersOutsideAllowedBoundaries() {
        Set<ConstraintViolation<KnowPostRagController>> violations = validate(0L, " ", 0, 63);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(
                        "知文ID必须大于0",
                        "问题不能为空",
                        "topK不能小于1",
                        "maxTokens不能小于64");
    }

    @Test
    void rejectsParametersAboveAllowedBoundaries() {
        Set<ConstraintViolation<KnowPostRagController>> violations = validate(
                1L, "问".repeat(501), 11, 2049);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(
                        "问题不能超过500个字符",
                        "topK不能大于10",
                        "maxTokens不能大于2048");
    }

    @Test
    void acceptsParametersAtAllowedBoundaries() {
        assertThat(validate(1L, "问题", 1, 64)).isEmpty();
        assertThat(validate(Long.MAX_VALUE, "问".repeat(500), 10, 2048)).isEmpty();
    }

    private Set<ConstraintViolation<KnowPostRagController>> validate(
            long id, String question, int topK, int maxTokens) {
        // 直接执行 Bean Validation，测试速度快且不需要启动 Spring 容器。
        return validator.forExecutables().validateParameters(
                controller, qaStream, new Object[]{id, question, topK, maxTokens});
    }
}
