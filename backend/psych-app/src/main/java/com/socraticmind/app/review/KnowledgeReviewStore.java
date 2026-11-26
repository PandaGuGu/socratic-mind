package com.socraticmind.app.review;

import java.util.List;

/**
 * 文档审核记录。
 *
 * 从 JSON 文件搬到库，原因只有一个：多实例。文件版在每个副本上各写一份，
 * "谁签的字"会随负载均衡漂到不同机器——同一个团队的人在不同副本看到不同的审核状态，
 * 而且这种不一致不会报错。
 *
 * 键是"文档 + 版本"：不同版本各留一条（历史累积），同一版本重审覆盖。
 * aclSnapshot 存签字那一刻的可见范围，之后改权限不回填——回填等于让历史签字随现状漂移。
 *
 * @param operatorId 成员 id。为空表示当时没有身份体系（历史数据）或未署名
 */
public interface KnowledgeReviewStore {

    record DocReview(String documentId, String title, String version, String decision,
                     String operatorId, String operatorName, String reviewedAt, String note,
                     String aclSnapshot) {
        /** 兼容旧字段名：页面与测试里还按 reviewer 读取。 */
        public String reviewer() {
            return operatorName;
        }

        /** 兼容旧字段名。 */
        public String aclScope() {
            return aclSnapshot;
        }
    }

    List<DocReview> all();

    List<DocReview> forDocument(String documentId);

    DocReview find(String documentId, String version);

    DocReview save(String documentId, String title, String version, String decision,
                   String operatorId, String operatorName, String note, String aclSnapshot);

    void clear();
}
