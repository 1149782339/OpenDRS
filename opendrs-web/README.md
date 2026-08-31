# OpenDRS 管理台

Vite + Vue 3 管理界面，用于维护连接与迁移任务。开发服务器把 `/api` 代理到本机 `opendrs-service`（默认 `http://localhost:8080`，对应 `OPENDRS_PORT`）。

## 启动

先在仓库根目录启动元数据库与服务：

```bash
docker compose up -d
mvn -pl opendrs-service -am spring-boot:run
```

再启动前端：

```bash
cd opendrs-web
npm i
npm run dev
```

浏览器打开 [http://localhost:5173](http://localhost:5173)。

- 连接页：`/connections`
- 任务页：`/tasks`
- 任务详情：`/tasks/:id`（`jobState` 为 `STARTING` / `RUNNING` / `STOPPING` 时每 2 秒轮询状态）

生产构建：`npm run build`。当前 `base` 为 `/`。
