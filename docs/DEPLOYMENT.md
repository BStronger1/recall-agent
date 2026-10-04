# 部署与展示

## 两种发布层级

1. **GitHub Pages：免费交互演示**。展示产品页面、示例回答、记忆编辑和 RAG 配置状态；无 Java 后端，不会调用真实模型，不会持久化真实记忆。适合作为 GitHub 产品集入口。
2. **Render：完整服务**。通过根目录 Dockerfile 同时构建 Vue 与 Spring Boot，HTTPS 同源服务，持久磁盘保存记忆。需账户、付费实例、模型密钥。

## Render 完整部署

仓库包含 `render.yaml`：Singapore、Starter Web Service、1 GB 持久磁盘，挂载 `/app/data`，健康检查 `/api/health`。

1. 登录 Render，连接个人 GitHub 仓库。
2. New → Blueprint，选 `recall-agent`。
3. 检查平台显示的实际月费，再确认创建。模型调用费用另计；本文件不承诺免费。
4. 在密钥字段填写 `MODEL_API_KEY`；设置与你的模型供应商匹配的 `MODEL_BASE_URL` 与 `CHAT_MODEL`。
5. 部署完成后，在站点设置中创建账号并填写个人模型 API。`RECALL_ACCESS_TOKEN` 和站点默认模型仅供旧工作区使用，新账号不依赖它们。
6. 添加一条测试记忆，重新加载连接后核对；再测试真实模型生成和引用。如需外部 RAG，配置相应变量并单独验收。

不要把 `.env`、模型密钥、访问口令、工作区数据提交 GitHub。使用平台密钥管理。磁盘必须保留，免费无盘实例的文件系统无法提供可靠长期记忆。

参考：[Docker](https://render.com/docs/docker)、[持久磁盘](https://render.com/docs/disks)、[价格](https://render.com/pricing)。

## GitHub Pages 演示

GitHub Pages 工作流使用 `/recall-agent/` 作为构建基础路径，只发布前端产物。仓库 Settings → Pages → Source 选择 GitHub Actions，然后运行 Pages 工作流。若仓库改名，同步修改 `VITE_BASE_PATH`。

该链接是交互演示，不是完整 AI 服务。正式 Render 站点上线后，将仓库 About 链接换成正式地址。

## 存储备份

通过设置页导出当前工作区 JSON；维护者也可备份 `/app/data` 整个目录。文件名携带工作区凭据，备份应按私密数据保管。不要以公开静态目录托管该目录。

当前服务支持单实例自助账号，密码哈希和会话哈希位于 `data/accounts/`；加密密钥文件 `data/.model-encryption-key` 也须备份。上线使用 HTTPS，反向代理须正确传递并由服务端可信地处理 HTTPS 状态，以设置 Secure Cookie。校园 HTTP 入口建议通过 SSH 隧道传输密码和 API 密钥。扩大规模需迁移数据库，并增加个人外部 RAG 连接器和密码找回机制。
