package com.tongji.storage.api;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.storage.OssStorageService;
import com.tongji.storage.api.dto.StoragePresignRequest;
import com.tongji.storage.api.dto.StoragePresignResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/storage")
@Validated
@RequiredArgsConstructor
public class StorageController {

    private static final Map<String, Set<String>> CONTENT_UPLOAD_TYPES = Map.of(
            "text/markdown", Set.of(".md", ".markdown"),
            "text/html", Set.of(".html"),
            "text/plain", Set.of(".txt"),
            "application/json", Set.of(".json")
    );
    private static final Map<String, Set<String>> IMAGE_UPLOAD_TYPES = Map.of(
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/webp", Set.of(".webp")
    );
    private static final Map<String, String> DEFAULT_EXTENSIONS = Map.of(
            "text/markdown", ".md",
            "text/html", ".html",
            "text/plain", ".txt",
            "application/json", ".json",
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp"
    );

    private final OssStorageService ossStorageService;
    private final JwtService jwtService;
    private final KnowPostMapper knowPostMapper;

    /**
     * 获取用于直传的 PUT 预签名 URL。
     */
    @PostMapping("/presign")
    public StoragePresignResponse presign(@Valid @RequestBody StoragePresignRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);

        long postId;
        try {
            postId = Long.parseLong(request.postId());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "postId 非法");
        }

        // 权限校验：postId 必须属于当前用户
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != userId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        String scene = request.scene();
        if (!"knowpost_content".equals(scene) && !"knowpost_image".equals(scene)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
        }
        String objectKey;
        String ext = normalizeExt(request.ext(), request.contentType(), scene);

        if ("knowpost_content".equals(scene)) {
            objectKey = "posts/" + postId + "/content" + ext;
        } else {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC")).format(Instant.now());
            String rand = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
            objectKey = "posts/" + postId + "/images/" + date + "/" + rand + ext;
        }

        int expiresIn = 600; // 10 分钟
        String putUrl = ossStorageService.generatePresignedPutUrl(objectKey, request.contentType(), expiresIn);
        Map<String, String> headers = Map.of("Content-Type", request.contentType());
        return new StoragePresignResponse(objectKey, putUrl, headers, expiresIn);
    }

    String normalizeExt(String ext, String contentType, String scene) {
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase();
        Map<String, Set<String>> allowedTypes;
        if ("knowpost_content".equals(scene)) {
            allowedTypes = CONTENT_UPLOAD_TYPES;
        } else if ("knowpost_image".equals(scene)) {
            allowedTypes = IMAGE_UPLOAD_TYPES;
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
        }
        Set<String> allowedExtensions = allowedTypes.get(normalizedType);
        if (allowedExtensions == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传文件类型");
        }

        String normalizedExt = ext == null ? "" : ext.trim().toLowerCase();
        if (normalizedExt.isBlank()) {
            return DEFAULT_EXTENSIONS.get(normalizedType);
        }
        if (!normalizedExt.startsWith(".")) {
            normalizedExt = "." + normalizedExt;
        }
        // 后端必须再次核对 MIME 与扩展名，不能只依赖可被绕过的前端 accept 属性。
        if (!allowedExtensions.contains(normalizedExt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件扩展名与 Content-Type 不匹配");
        }
        return normalizedExt;
    }
}
