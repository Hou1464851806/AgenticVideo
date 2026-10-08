# Git 自动提交、追溯与回滚流程

本流程细化[仓库 Git 规则](../AGENTS.md#6-git-规则)，供助手执行本地提交、合并、追溯或用户请求的回滚时阅读。授权范围由 AGENTS.md 和用户当次要求确定。

当前状态：Java/Vue 工程、Compose、验证脚本与 CI 配置已建立，验证命令见各模块 README 和 scripts/README.md。此流程是助手的执行规范，不是后台监控服务或 Git Hook；只有助手正在处理任务时才会执行。纯文档任务检查内容、相对链接、Markdown 结构和差异格式；工程改动运行实际构建与受影响集成/端到端检查。

## 1. 自动提交的前提

| 前提 | 检查与处理 |
|---|---|
| 授权与权限 | 用户已授权本地 commit、合并到 main；运行环境允许写入工作树及 Git 元数据、执行必要检查。AGENTS.md 是行为规则，不能覆盖沙盒、审批策略或组织限制。权限被拒时报告具体失败，不通过修改规则或禁用检查绕过。 |
| Git 身份 | `git var GIT_AUTHOR_IDENT` 和 `git var GIT_COMMITTER_IDENT` 可解析已有身份；遵循已有签名与 hooks 配置。缺失身份时向用户索取，不自行编造身份或关闭签名。 |
| 仓库状态 | 确认当前分支、main 和工作树归属，没有未解决冲突或未完成的 merge、rebase、cherry-pick、revert。已有操作先按用户意图处理。 |
| 改动归属 | 开始任务时记录 HEAD、分支、暂存及未暂存差异、未跟踪文件；提交只包含本任务内容。已有用户暂存内容不能跟随本任务提交；无法安全分离时保留原状，报告阻塞。 |
| 可验收与可验证 | 每个提交有明确目的，必要链路完整，适用检查通过，文档与实现状态一致；需要的检查因环境缺失未执行时，保留改动并说明，不合并到 main。文档任务不要求不存在的业务测试。 |
| 内容安全 | 审查本次新增和修改内容，不包含真实密钥、凭证、私有素材、敏感日志或无关生成产物。 |
| 合并边界 | 本地 main 存在，合并工作树干净，完整待合并提交都属于已授权任务；远端权限和保护规则不能通过本地合并绕过。 |

未满足提交前提时保留改动；提交成功但合并前提未满足时保留任务提交，报告“已提交，未合并”及具体原因。没有本任务差异时不创建空提交。

## 2. 提交粒度与 message

一个 commit 对应一个可说明、可验证、可单独撤销的目的，例如“补充本地 Git 自动交付规范”或“实现素材上传校验”。同一功能依赖的代码、测试与文档一起提交；不同目的分别提交。提交不要求每改一个文件都保存一次，未完成或验证失败的修改继续留在任务工作树。

标题使用 `feat`、`fix`、`docs`、`refactor`、`test`、`chore` 等 type，scope 对应实际模块；描述具体结果，避免 `update`、`changes`、`WIP`。破坏性接口变更在正文写清影响与迁移方式。沿用仓库已有中英文习惯。

```text
docs(git): define automatic local delivery and rollback policy

Why: make task boundaries, validation and rollback explicit.
Validation: git diff --cached --check passed; local document links checked.
Limitations: no application build or CI exists yet.
```

正文中的命令与结果必须来自实际执行，示例不能照抄为已验证记录。Issue 或需求链接仅在真实存在且与任务相关时记录。交付说明提供 commit SHA 和 main 合并 SHA，将会话目标与 Git 历史关联起来。

## 3. 提交步骤

以下命令在仓库根目录执行。路径、分支与 SHA 示例需替换为本任务真实值；按步骤检查结果，失败时停止依赖步骤，不作为一条盲目执行的批处理。

1. 执行 `git status --short`、`git branch --show-current`、`git diff --stat`、`git diff`、`git diff --cached`、`git diff --check`，审查未跟踪文件。确认提交身份、操作状态和既有 hooks；保持用户已有修改。
2. 优先在 `codex/<task>` 任务分支工作；已有指定分支按现状处理。若从 main 开始，先创建任务分支。用户工作树有未提交内容时，不擅自 stash；独立任务可以从明确基线建立独立工作树，涉及用户未提交内容的任务须确认其范围。
3. 完成本任务验收、影响范围内的适用检查及文档同步。仅暂存审查过的本任务路径或代码块，例如 `git add -- AGENTS.md docs/git-workflow.md`。同文件包含用户改动时只暂存本任务代码块；无法安全区分时暂停提交。
4. 执行 `git diff --cached --name-status`、`git diff --cached`、`git diff --cached --check`，确认索引恰好是本任务的可交付结果。若验证依赖未暂存或未跟踪内容，在独立工作树验证候选提交内容，避免仅验证混合工作区。
5. 将标题、原因、验证和限制写入临时 message 文件，执行 `git commit --file <message-file>`，保留正常 hooks 与签名校验；禁止使用 `--no-verify` 掩盖失败。提交后检查 `git show --stat HEAD` 与状态，记录 SHA；hooks 改动了内容时重新审查并验证。

用户工作树可以仍有其原有未提交内容；提交后必须确认这些内容与任务开始时一致，不能以“工作区不干净”为理由一起提交。

## 4. 合并到本地 main

1. 用 `git worktree list --porcelain` 找到 main 工作树。若 main 已被占用，使用该工作树前确认其干净且没有其他任务正在写入；无法确认时暂停合并。需要新工作树时，Codex 桌面环境优先通过应用的工作树工具创建和登记，使用返回的路径。
2. 在干净的合并工作树切换到 main，记录完整 `git rev-parse main` 为合并前 SHA、任务分支 HEAD 为任务 SHA。存在远端时，先检查是否获得联网同步授权以及 main 与上游的关系；已授权同步只能正常 fetch 和 fast-forward，遇到分叉或无法确认上游新状态时停止合并并说明。没有远端时明确报告本地结果。
3. 审查 `git log --oneline main..<task-branch>` 及 `git diff main...<task-branch>`，完整待合并历史都必须在授权范围内。当前任务分支混有未授权提交时，从 main 建立独立集成分支，仅按依赖顺序 cherry-pick 已审查的本任务提交并重新验证；无法安全分离时暂停合并，不能只凭最终 diff 看似正确就合入整条历史。
4. 确认没有其他进程正在操作该合并工作树。执行 `git merge --no-ff --no-commit <task-branch>`，使用已审查的真实分支。若任务已包含于 main，报告实际已有结果，不制造空合并。
5. 没有冲突时，审查合并后的 `git diff --cached`、`git diff --cached --check`，运行合并结果的适用完整检查。main 新提交会影响本任务时必须验证组合结果，不能沿用任务分支的旧测试结论。
6. 出现冲突时按双方需求判断；合并失败或检查未通过且不能立即修复时，仅在确认自己发起合并且工作树开始时干净的前提下 `git merge --abort`，保留任务提交并报告。需求、安全或兼容性有歧义时向用户澄清；禁止批量选择一侧。
7. 检查合并期间任务 HEAD 未变化，执行 `git commit --file <merge-message-file>` 完成合并。message 说明任务结果、来源分支和合并验证。检查 `git rev-list --parents -n 1 HEAD`，第一父提交必须是合并前 main，第二父提交是已审查的任务 HEAD（使用集成分支时为其 HEAD）。
8. 检查提交内容、合并工作树状态与用户原工作树，记录任务 SHA、合并 SHA、合并前 main SHA 和验证结果。只报告本地 main 已合并；远端 main 是否更新另行确认。

`--no-ff` 保留每个任务的合并节点及内部提交，便于在 main 上按任务追溯和整体撤销；它不替代合并结果验证。远端若规定线性历史或强制 PR，只能在另行授权的远端流程中遵守平台规则，本地规则不能绕过保护。

## 5. 追溯与回滚

追溯命令在仓库根目录执行：

```bash
git log --graph --oneline --decorate --all
git log --first-parent --oneline main
git show <task-commit-sha>
git diff <merge-sha>^1 <merge-sha>
git rev-list --parents -n 1 <merge-sha>
```

用户要求撤销时，在干净的回滚任务分支中执行以下对应操作，审查反向差异并完成适用验证，再按同一流程提交、合并到 main。`--no-commit` 留出验证与自定义 message 的机会。

| 撤销目标 | 操作 |
|---|---|
| 一个普通 commit | `git revert --no-commit <task-commit-sha>` |
| 一个完整任务合并 | 核对第一父提交确实为合并前 main 后，`git revert --no-commit -m 1 <merge-sha>` |

回滚提交正文记录被撤销的 SHA、原因、验证与限制；交付时记录回滚提交和它的合并 SHA。发生冲突时检查后续依赖，按需求处理；无法安全处理时，在确认是自己发起的 revert 后用 `git revert --abort` 恢复到操作前状态，并报告冲突。

撤销 merge 保留其历史祖先关系，直接再次合并原任务分支不会重新引入已撤销的内容。需要恢复原功能时，经用户授权撤销那次 revert 或创建新的修复提交，并重新验证。

Git 回滚恢复已纳入 Git 的文件变化，不能恢复数据库、对象存储素材、外部账号操作或已部署运行状态。涉及这些资源的任务还需要独立迁移、备份或补偿方案；本流程不会把代码可回滚描述成业务数据可恢复。需要异地备份时另行授权配置远端并推送，本地 Git 历史不是异地备份。

## 6. 依据与边界

- [OpenAI 官方 AGENTS.md 指南](https://developers.openai.com/codex/guides/agents-md)：项目规则在运行时加载；修改规则不创建常驻执行器。
- [OpenAI 官方审批与安全指南](https://learn.chatgpt.com/docs/agent-approvals-security)：沙盒控制访问范围，审批策略控制批准方式；仓库规则不能替代运行环境权限。
- [Git merge 官方文档](https://git-scm.com/docs/git-merge)：`--no-ff --no-commit` 保留合并提交并在提交前停下，便于验证合并结果；脏工作树会增加失败恢复风险。
- [Git revert 官方文档](https://git-scm.com/docs/git-revert)：revert 通过新提交逆转变化，merge 的 mainline 参数决定保留哪条父线，也影响后续重新合并。
