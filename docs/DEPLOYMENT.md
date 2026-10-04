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
5. 部署完成后，从 Render 环境变量取得自动生成的 `RECALL_ACCESS_TOKEN`，在站点设置中输入该口令。
6. 添加一条测试记忆，重新加载连接后核对；再测试真实模型生成和引用。如需外部 RAG，配置相应变量并单独验收。

不要把 `.env`、模型密钥、访问口令、工作区数据提交 GitHub。使用平台密钥管理。磁盘必须保留，免费无盘实例的文件系统无法提供可靠长期记忆。

参考：[Docker](https://render.com/docs/docker)、[持久磁盘](https://render.com/docs/disks)、[价格](https://render.com/pricing)。

## GitHub Pages 演示

GitHub Pages 工作流使用 `/recall-agent/` 作为构建基础路径，只发布前端产物。仓库 Settings → Pages → Source 选择 GitHub Actions，然后运行 Pages 工作流。若仓库改名，同步修改 `VITE_BASE_PATH`。

该链接是交互演示，不是完整 AI 服务。正式 Render 站点上线后，将仓库 About 链接换成正式地址。

## 存储备份

通过设置页导出当前工作区 JSON；维护者也可备份 `/app/data` 整个目录。文件名携带工作区凭据，备份应按私密数据保管。不要以公开静态目录托管该目录。

当前服务面向单实例、受邀体验。未来多人版应增加账户身份、数据库、权限管理、个人连接器配置和恢复机制。
