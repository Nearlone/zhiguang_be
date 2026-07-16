package com.tongji.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.api.dto.RagIndexStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.config.EsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * RAG 索引构建服务：
 * - 将公开且已发布的知文切片并写入向量库
 * - 通过指纹（SHA256/ETag）判断是否需要重建，保证幂等
 * - 采用 delete-by-query 清理旧切片，再批量 upsert 新切片
 */
@Service
@RequiredArgsConstructor
public class RagIndexService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    // 当前 DashScope Embedding 模型单次最多接收 10 条文本，超过会直接返回 HTTP 400。
    static final int EMBEDDING_BATCH_SIZE = 10;
    // 控制单篇知文的向量数量，避免异常大正文带来过高的调用成本和索引膨胀。
    static final int MAX_CHUNKS_PER_POST = 100;
    // 向量库封装（Elasticsearch VectorStore），负责写入/检索向量
    private final VectorStore vectorStore;
    // 数据访问：根据 postId 查询知文详情（含 contentUrl、指纹等）
    private final KnowPostMapper knowPostMapper;
    // 拉取 Markdown 正文内容
    private final RestTemplate http = new RestTemplate();
    // 直接使用 ES 客户端做指纹判断和删除旧切片
    private final ElasticsearchClient es;
    // ES 相关配置（索引名等）
    private final EsProperties esProps;
    // 固定条带锁避免锁对象无限增长，同时防止 Kafka 消费与首次问答重复调用 Embedding。
    private final Object[] indexLocks = createIndexLocks(256);

    public RagIndexResult ensureIndexed(long postId) {
        // 当前策略：在问答前直接尝试重建（指纹未变化时会跳过）
        return reindexSinglePost(postId);
    }

    public RagIndexResult reindexSinglePost(long postId) {
        return reindexWithLock(postId, null);
    }

    /**
     * 作者手动重建入口：先校验归属关系，避免任意登录用户消耗他人的 Embedding 配额。
     */
    public RagIndexResult reindexSinglePostForOwner(long postId, long creatorId) {
        return reindexWithLock(postId, creatorId);
    }

    /**
     * 删除或转私密时移除历史向量，防止已失效正文继续被召回。
     */
    public void removeIndex(long postId) {
        Object lock = indexLock(postId);
        synchronized (lock) {
            if (!deleteExistingChunks(postId)) {
                throw new IllegalStateException("Delete RAG index failed for post " + postId);
            }
            knowPostMapper.markRagNotIndexed(postId);
        }
    }

    /**
     * 状态查询不走详情缓存，供前端在发布后短轮询 READY/FAILED。
     */
    public RagIndexStatusResponse getIndexStatus(long postId, Long currentUserId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知文不存在");
        }
        boolean isPublic = "published".equalsIgnoreCase(row.getStatus())
                && "public".equalsIgnoreCase(row.getVisible());
        boolean isOwner = currentUserId != null && Objects.equals(row.getCreatorId(), currentUserId);
        if (!isPublic && !isOwner) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权查看索引状态");
        }

        String status = StringUtils.hasText(row.getRagIndexStatus())
                ? row.getRagIndexStatus() : RagIndexStatus.NOT_INDEXED.name();
        // 只有 READY 代表整篇文章的所有批次均已成功，其他状态不能向前端暴露旧版本数量。
        int chunkCount = RagIndexStatus.READY.name().equals(status) && row.getRagIndexChunkCount() != null
                ? row.getRagIndexChunkCount() : 0;
        String message = "READY".equals(status) ? "AI问答已就绪"
                : StringUtils.hasText(row.getRagIndexError()) ? row.getRagIndexError()
                : statusMessage(status);
        return new RagIndexStatusResponse(String.valueOf(postId), status, chunkCount,
                row.getRagIndexedAt(), message);
    }

    private RagIndexResult reindexSinglePost(KnowPostDetailRow row) {
        long postId = row.getId();

        // 仅索引公开的已发布知文
        if (!"published".equalsIgnoreCase(row.getStatus()) || !"public".equalsIgnoreCase(row.getVisible())) {
            log.warn("Post {} is not public/published, skip indexing", postId);
            if (!deleteExistingChunks(postId)) {
                throw new IllegalStateException("Delete ineligible RAG index failed for post " + postId);
            }
            knowPostMapper.markRagNotIndexed(postId);
            return RagIndexResult.skipped("仅公开且已发布的知文支持AI问答");
        }

        knowPostMapper.markRagIndexing(postId);

        // 内容地址缺失则无法抓取正文
        if (!StringUtils.hasText(row.getContentUrl())) {
            log.warn("Post {} missing contentUrl or not found", postId);
            return markFailed(postId, "知文正文不可用，AI问答准备失败");
        }

        // 指纹检测：如未变化则跳过重建
        String currentSha = row.getContentSha256();
        String currentEtag = row.getContentEtag();
        if (isUpToDate(postId, currentSha, currentEtag)) {
            log.info("Post {} already indexed with same fingerprint, skip", postId);
            int existingCount = row.getRagIndexChunkCount() == null ? 0 : row.getRagIndexChunkCount();
            knowPostMapper.markRagIndexReady(postId, existingCount);
            return RagIndexResult.ready(existingCount);
        }

        // 抓取 Markdown 正文
        String text = fetchContent(row.getContentUrl());
        if (!StringUtils.hasText(text)) {
            log.warn("Post {} content empty", postId);
            return markFailed(postId, "知文正文读取失败，AI问答准备失败");
        }

        // 先按 Markdown 标题切段，再做固定长度切片（带重叠）
        List<String> chunks = chunkMarkdown(text);
        if (chunks.size() > MAX_CHUNKS_PER_POST) {
            log.warn("Post {} has {} chunks, exceeding max {}, skip indexing",
                    postId, chunks.size(), MAX_CHUNKS_PER_POST);
            return markFailed(postId, "知文内容过长，最多支持100个向量切片");
        }

        // 组装 Document（文本 + 业务元数据），用于向量写入与检索过滤
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String cid = postId + "#" + i;
            Map<String, Object> meta = new HashMap<>();
            meta.put("postId", String.valueOf(postId));
            meta.put("chunkId", cid);
            meta.put("position", i);
            meta.put("contentEtag", currentEtag);
            meta.put("contentSha256", currentSha);
            meta.put("contentUrl", row.getContentUrl());
            meta.put("title", row.getTitle());
            // 使用稳定的 chunkId 作为 ES 文档 ID，重建时可覆盖同位置切片，也便于失败后精确清理。
            docs.add(new Document(cid, chunks.get(i), meta));
        }
        int indexed = replaceDocuments(postId, docs);
        if (indexed != docs.size()) {
            return markFailed(postId, "向量索引创建失败，可稍后重试");
        }
        knowPostMapper.markRagIndexReady(postId, indexed);
        return RagIndexResult.ready(indexed);
    }

    private RagIndexResult reindexWithLock(long postId, Long requiredOwnerId) {
        Object lock = indexLock(postId);
        synchronized (lock) {
            // 等待同文章的前序任务结束后重新读取数据库，确保指纹和状态都是最新值。
            KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
            if (row == null) {
                log.warn("Post {} not found", postId);
                return RagIndexResult.failed("知文不存在，无法创建AI问答索引");
            }
            if (requiredOwnerId != null && !Objects.equals(row.getCreatorId(), requiredOwnerId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "知文不存在或无权重建索引");
            }
            return reindexSinglePost(row);
        }
    }

    private Object indexLock(long postId) {
        return indexLocks[Math.floorMod(Long.hashCode(postId), indexLocks.length)];
    }

    private static Object[] createIndexLocks(int size) {
        Object[] locks = new Object[size];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    /**
     * 替换单篇知文的全部向量切片：
     * - 单篇最多 100 条，超过时拒绝索引，避免只保存正文前半部分；
     * - 每批最多 10 条，满足当前 DashScope Embedding 模型的批量限制；
     * - 任一批失败时清理本轮文档，避免半篇文章残留在向量库。
     */
    int replaceDocuments(long postId, List<Document> docs) {
        int totalChunks = docs.size();
        if (totalChunks > MAX_CHUNKS_PER_POST) {
            log.warn("Post {} has {} chunks, exceeding max {}, skip indexing",
                    postId, totalChunks, MAX_CHUNKS_PER_POST);
            return 0;
        }

        // 通过 postId 删除旧版本，正文变短时也不会残留旧的尾部切片。
        if (!deleteExistingChunks(postId)) {
            return 0;
        }
        int totalBatches = (totalChunks + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE;
        for (int start = 0, batchNo = 1; start < totalChunks; start += EMBEDDING_BATCH_SIZE, batchNo++) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, totalChunks);
            List<Document> batch = List.copyOf(docs.subList(start, end));
            try {
                vectorStore.add(batch);
            } catch (Exception e) {
                log.error("VectorStore batch add failed: postId={}, batch={}/{}, batchSize={}, totalChunks={}, error={}",
                        postId, batchNo, totalBatches, batch.size(), totalChunks, e.getMessage());
                cleanupFailedWrite(postId, docs);
                return 0;
            }
        }

        log.info("RAG index completed: postId={}, chunks={}, batches={}", postId, totalChunks, totalBatches);
        return totalChunks;
    }

    /**
     * 批次失败后优先按稳定文档 ID 删除，再按 postId 兜底清理。
     */
    private void cleanupFailedWrite(long postId, List<Document> docs) {
        try {
            vectorStore.delete(docs.stream().map(Document::getId).toList());
        } catch (Exception e) {
            log.warn("Delete failed RAG document ids for post {} failed: {}", postId, e.getMessage());
        }
        deleteExistingChunks(postId);
    }

    private RagIndexResult markFailed(long postId, String userMessage) {
        // 数据库只保存经过控制的业务文案，不把供应商响应、Key 或内部堆栈暴露给前端。
        knowPostMapper.markRagIndexFailed(postId, userMessage);
        return RagIndexResult.failed(userMessage);
    }

    private static String statusMessage(String status) {
        return switch (status) {
            case "PENDING" -> "知文已发布，AI问答正在排队准备";
            case "INDEXING" -> "正在创建AI问答索引";
            case "FAILED" -> "AI问答准备失败，可稍后重试";
            default -> "AI问答尚未准备";
        };
    }

    /**
     * 指纹判断是否需要重建：
     * - 以 postId 查询任意一条已索引文档的 metadata
     * - 优先比较 SHA256，其次比较 ETag；一致则视为无需重建
     */
    private boolean isUpToDate(long postId, String currentSha, String currentEtag) {
        try {
            if (!StringUtils.hasText(esProps.getIndex())) {
                // 未配置索引名则无法判断，直接视为需要重建
                return false;
            }
            SearchResponse<Map> resp = es.search(s -> s
                            .index(esProps.getIndex())
                            .size(1)
                            .query(q -> q.term(t -> t
                                    .field("metadata.postId")
                                    .value(v -> v.stringValue(String.valueOf(postId))))),
                    Map.class);
            List<Hit<Map>> hits = resp.hits().hits();
            if (hits == null || hits.isEmpty()) return false;
            Map source = hits.getFirst().source();
            if (source == null) return false;
            Object metaObj = source.get("metadata");
            if (!(metaObj instanceof Map<?, ?> meta)) return false;
            String indexedSha = asString(meta.get("contentSha256"));
            String indexedEtag = asString(meta.get("contentEtag"));
            if (StringUtils.hasText(currentSha) && StringUtils.hasText(indexedSha)) {
                return Objects.equals(currentSha, indexedSha);
            }
            if (StringUtils.hasText(currentEtag) && StringUtils.hasText(indexedEtag)) {
                return Objects.equals(currentEtag, indexedEtag);
            }
            return false;
        } catch (Exception e) {
            log.warn("Fingerprint check failed for post {}: {}", postId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除旧切片：按 metadata.postId 精确删除，确保 upsert 幂等
     */
    private boolean deleteExistingChunks(long postId) {
        try {
            if (!StringUtils.hasText(esProps.getIndex())) return true;
            es.deleteByQuery(d -> d
                    .index(esProps.getIndex())
                    .query(q -> q.term(t -> t
                            .field("metadata.postId")
                            .value(v -> v.stringValue(String.valueOf(postId))))));
            return true;
        } catch (Exception e) {
            log.warn("Delete old chunks failed for post {}: {}", postId, e.getMessage());
            return false;
        }
    }

    private static String asString(Object o) {
        // 统一处理 null → String 的转换
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 拉取正文内容（Markdown 文本）。
     */
    private String fetchContent(String url) {
        try {
            return http.getForObject(url, String.class);
        } catch (Exception e) {
            log.error("Fetch content failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按 Markdown 标题切段，再交由固定长度切片策略处理。
     */
    private List<String> chunkMarkdown(String text) {
        List<String> paras = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            boolean isHeader = line.startsWith("#");
            if (isHeader && !buf.isEmpty()) { // 遇到新的标题，收束上一段
                paras.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(line).append('\n');
        }
        if (!buf.isEmpty()) paras.add(buf.toString());

        return getChunks(paras);
    }

    /**
     * 固定长度切片（每片 ≤ 800 字符），切片间 100 字符重叠：
     * - 兼顾检索召回与上下文连续性
     */
    private static List<String> getChunks(List<String> paras) {
        List<String> chunks = new ArrayList<>();
        for (String p : paras) {
            if (p.length() <= 800) {
                chunks.add(p);
            } else {
                int start = 0;
                while (start < p.length()) {
                    int end = Math.min(start + 800, p.length());
                    chunks.add(p.substring(start, end));
                    if (end >= p.length()) break;
                    start = Math.max(end - 100, start + 1); // 重叠 100 字符以保留语义连续
                }
            }
        }
        return chunks;
    }
}
