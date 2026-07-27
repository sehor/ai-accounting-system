# Graph Report - ai-accouting-system  (2026-07-27)

## Corpus Check
- 18 files · ~4,547 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 136 nodes · 122 edges · 25 communities (21 shown, 4 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `9424c43f`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 21|Community 21]]

## God Nodes (most connected - your core abstractions)
1. `AI 财务系统开发设计文档（TDD）v0.1` - 20 edges
2. `AI 财务系统产品需求文档（PRD）v0.1` - 13 edges
3. `6. 功能需求` - 11 edges
4. `16. 具体开发计划` - 9 edges
5. `5. 核心业务流程` - 7 edges
6. `5. 数据模型` - 7 edges
7. `9. 非功能需求` - 5 edges
8. `11. 交付路线` - 5 edges
9. `5.3 基础资料` - 5 edges
10. `5.4 凭证` - 5 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Import Cycles
- None detected.

## Communities (25 total, 4 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.09
Nodes (21): 10. 报表与查询 DSL, 12. MCP 设计, 13. 幂等、并发与错误处理, 14.1 可观测性, 14.2 保留, 14. 可观测性与保留, 15. 测试策略, 17. 开发执行约定 (+13 more)

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (17): 10. v0.1 验收标准, 12. 产品边界说明, 1. 产品概述, 2. 产品目标, 3.1 关系模型, 3.2 账套角色, 3. 用户、账套与权限, 4.1 包含 (+9 more)

### Community 2 - "Community 2"
Cohesion: 0.11
Nodes (18): 5.1 通用字段, 5.2 身份与账套, 5.4 凭证, 5.5 文档与任务, 5.6 审计与幂等, 5. 数据模型, `app_user`, `audit_revision` (+10 more)

### Community 3 - "Community 3"
Cohesion: 0.18
Nodes (11): 6.10 审计, 6.1 账套与成员, 6.2 会计准则与科目, 6.3 会计期间, 6.4 多币种, 6.5 凭证, 6.6 审批, 6.7 重复单据 (+3 more)

### Community 4 - "Community 4"
Cohesion: 0.22
Nodes (9): 16. 具体开发计划, 阶段 0：工程基础, 阶段 1：身份、账套与隔离, 阶段 2：准则包与基础资料, 阶段 3：凭证核心, 阶段 4：账簿与报表, 阶段 5：附件与异步任务, 阶段 6：MCP (+1 more)

### Community 5 - "Community 5"
Cohesion: 0.29
Nodes (7): 5.1 创建账套, 5.2 录入期初余额, 5.3 创建与记账凭证, 5.4 上传单据并生成草稿, 5.5 修改、撤销与恢复, 5.6 查询财务数据, 5. 核心业务流程

### Community 6 - "Community 6"
Cohesion: 0.40
Nodes (5): 11. 交付路线, M0：工程基础与账套访问, M1：会计核心, M2：附件与任务, M3：Agent 与交付加固

### Community 7 - "Community 7"
Cohesion: 0.40
Nodes (5): 9.1 安全与隔离, 9.2 性能目标, 9.3 可观测性, 9.4 保留与删除, 9. 非功能需求

### Community 8 - "Community 8"
Cohesion: 0.40
Nodes (5): 5.3 基础资料, `account`, `accounting_period`, `dimension_type` / `dimension_value`, `opening_balance`

### Community 9 - "Community 9"
Cohesion: 0.40
Nodes (4): AI 财务系统, 启动前准备, 常用命令, 当前本地环境决策

### Community 12 - "Community 12"
Cohesion: 0.50
Nodes (4): 11.1 上传, 11.2 PostgreSQL 任务队列, 11.3 Mock 提取, 11. 附件与异步任务

### Community 13 - "Community 13"
Cohesion: 0.50
Nodes (4): 2.1 技术栈, 2.2 为什么采用 Spring Boot, 2.3 保持简单的边界, 2. 架构决策

### Community 14 - "Community 14"
Cohesion: 0.50
Nodes (4): 9.1 状态机, 9.2 事务, 9.3 修改与恢复, 9. 凭证状态、事务与修订

## Knowledge Gaps
- **92 isolated node(s):** `graphify`, `com.example:accounting`, `1. 产品概述`, `2. 产品目标`, `3.1 关系模型` (+87 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AI 财务系统开发设计文档（TDD）v0.1` connect `Community 0` to `Community 2`, `Community 4`, `Community 12`, `Community 13`, `Community 14`?**
  _High betweenness centrality (0.195) - this node is a cross-community bridge._
- **Why does `5. 数据模型` connect `Community 2` to `Community 8`, `Community 0`?**
  _High betweenness centrality (0.126) - this node is a cross-community bridge._
- **Why does `AI 财务系统产品需求文档（PRD）v0.1` connect `Community 1` to `Community 3`, `Community 5`, `Community 6`, `Community 7`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **What connects `graphify`, `com.example:accounting`, `1. 产品概述` to the rest of the system?**
  _92 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.09090909090909091 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.1111111111111111 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.1111111111111111 - nodes in this community are weakly interconnected._