---
name: nox-plugin-dev
description: Nox（AiCode 二改）插件开发完整指南：plugin.json 全字段、13 种贡献类型、12 个 UI 槽位、runtime 的 JSON-RPC 协议与 handler 分发、页面桥 API 与 data:// 存储、UI 组件库、悬浮窗、工具与 hook 定义、打包安装调试与商店发布流程
---

# Nox 插件开发

Nox 的插件是一个 zip 包，靠包根 `plugin.json` 声明它要贡献什么：AI 工具、路径页面、技能、子代理、MCP 服务器、供应商、生命周期 hook、界面入口。宿主负责解包、校验、注册、启停，并把插件的能力接入对应位置。

开发前先明确一件事：**插件不是沙箱里的小程序**。声明了 `runtime` 的插件会作为独立进程跑在容器里（Node 或 Python），能读写文件、起服务、调外部命令；只有 HTML 页面型插件才跑在 WebView 沙箱里。这决定了两类插件的调试方式与能力边界。

---

## 一、包结构

```
my-plugin-1.0.0.zip
├── plugin.json                必须，清单
├── dist/main.js               runtime 入口（声明 runtime 时）
├── schemas/*.json             工具入参 JSON Schema（被 inputSchema 引用）
├── pages/*.html               界面页面（被 uiEntries.page / configPage 引用）
├── skills/<名字>/SKILL.md      技能（被 contributes.skills 引用）
├── agents/<名字>.md           子代理定义（被 contributes.agents 引用）
└── mcp/*.json                 MCP 服务器定义（被 contributes.mcp 引用）
```

约束（宿主编包时逐条校验，违反任一条清单直接判无效）：

- 所有路径必须是包内相对路径，不允许 `..`、绝对路径、符号链接
- 页面只接受 `.html` / `.htm`
- 不允许打包 `.so`、`.dex`、`.apk`、`.jar`、`.wasm`、`.class` 等可执行文件
- 单个文本文件不超过 2MB，整包不超过 20MB（商店上传）/ 16MB（安装校验上限）

---

## 二、plugin.json 全字段

```json
{
  "manifestVersion": 1,
  "id": "publisher.plugin-name",
  "name": { "default": "My Plugin", "zh-CN": "我的插件" },
  "version": "1.0.0",
  "publisher": "publisher",
  "repository": "https://github.com/publisher/my-plugin",
  "description": { "default": "...", "zh-CN": "..." },
  "license": "MIT",
  "icon": "box",
  "engines": { "pluginApi": "^1.0.0" },
  "runtime": { "type": "javascript", "entry": "dist/main.js" },
  "contributes": { }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `manifestVersion` | 是 | 固定 `1` |
| `id` | 是 | `发布者.插件名`，两段都以字母（含中文等 Unicode 字母）开头，可含数字、下划线与连字符；全局唯一。`一束晚风.workbuddy网关` 这类中文 id 合法 |
| `name` | 是 | 本地化文本，见下方格式 |
| `version` | 是 | 语义化版本 `x.y.z`，可带 `-beta.1` 预发布后缀 |
| `publisher` | 是 | 单个词，允许中文等 Unicode 字母，可含数字、下划线、连字符；不得含空格、点、斜杠 |
| `repository` | 是 | GitHub 仓库地址；商店重新托管时索引里会指向商店自身，属正常 |
| `description` | 是 | 本地化文本 |
| `license` | 是 | 任意短字符串，商店一般用 `MIT` |
| `icon` | 否 | Feather 图标名（如 `box`、`grid`、`book`、`check`），或包内图片路径如 `icons/icon.png`（png 可用，文件必须在包内） |
| `engines.pluginApi` | 是 | 兼容的宿主 API 范围，写 `^1.0.0` |
| `runtime` | 否 | 声明后插件以独立进程运行；不声明则只有页面/静态贡献 |
| `contributes` | 是 | 至少要有一项非空，否则清单判无效 |

本地化文本两种写法都接受：

```json
"name": "我的插件"
"name": { "default": "My Plugin", "zh-CN": "我的插件", "en": "My Plugin" }
```

语言标签统一小写、`-` 分隔（`zh-CN`、`zh_CN` 视为同一个）；取不到时回退 `default`，再回退任意一项。

---

## 三、contributes 的 15 种类型

```json
"contributes": {
  "skills":         [],
  "agents":         [],
  "workspaceTools": [],
  "uiEntries":      [],
  "screens":        [],
  "styleTokens":    null,
  "tools":          [],
  "hooks":          [],
  "mcp":            [],
  "providers":      [],
  "shizuku":        null,
  "overlay":        null,
  "backup":         null,
  "modelAccess":    null,
  "configPage":     null
}
```

### 1. tools —— AI 可调用的工具

```json
{
  "id": "echo",
  "title": { "zh-CN": "回显", "default": "Echo" },
  "description": { "zh-CN": "把传入的文本原样回显。", "default": "Echo text back." },
  "inputSchema": "schemas/echo.input.json",
  "handler": "tools.echo"
}
```

- `inputSchema` 指向包内 JSON Schema（`{"type":"object","properties":{...},"required":[...]}`）
- `handler` 是 runtime 里注册的处理函数名，宿主通过 JSON-RPC 调用
- 装的插件贡献的工具，AI 侧名称形如 `plugin__<插件ID>__<工具ID>`
- 没有 `runtime` 却声明 `tools` → 清单无效

### 2. skills / agents —— 技能与子代理

```json
"skills": [ { "id": "notes", "file": "skills/project-notes/SKILL.md" } ]
"agents": [ { "id": "reviewer", "file": "agents/reviewer.md" } ]
```

装好后在 AI 的能力清单里显示为 `<插件ID>:<名字>`，只读，用户不能在技能页编辑。

### 3. workspaceTools —— 右侧「项目工具」抽屉里的工具

```json
{
  "id": "snapshot",
  "title": { "zh-CN": "项目快照" },
  "description": { "zh-CN": "列出当前项目的文件结构。" },
  "icon": "layers",
  "category": "通用",
  "pageFile": "pages/project-snapshot.html"
}
```

注意字段名是 **`pageFile`**（不是 `page`），`id` / `title` / `description` / `icon` / `category` / `pageFile` 全部必填。

聊天页从屏幕右缘往左滑打开抽屉，点条目进入对应页面。

### 4. uiEntries —— 界面入口（12 个槽位）

```json
{
  "id": "memory",
  "title": { "zh-CN": "记忆管理" },
  "slot": "settings.data",
  "page": "pages/memory.html",
  "icon": "book"
}
```

**slot 必须精确等于下表之一**，拼错会导致整个清单解析失败：

| slot | 出现位置 |
|---|---|
| `settings.general` | 设置 → 通用分区末尾 |
| `settings.ai` | 设置 → AI 配置分区末尾 |
| `settings.environment` | 设置 → 运行环境分区末尾 |
| `settings.permissions` | 设置 → 应用权限分区末尾 |
| `settings.data` | 设置 → 数据与诊断分区末尾 |
| `settings.help` | 设置 → 帮助与关于分区末尾 |
| `drawer.footer` | 会话侧栏底部设置组 |
| `chat.header.menu` | 聊天顶栏三点菜单 |
| `chat.welcome` | 空会话居中卡片 |
| `chat.session.menu` | 会话长按弹出菜单（上下文带 `sessionId`） |
| `files.tree.menu` | 文件树节点长按 |
| `editor.selection.menu` | 代码编辑器顶栏（上下文带文件 `path`） |
| `backup.data` | 设置「数据与诊断」的备份数据组（**只接受动作入口**，不能有 page） |

硬性约束：

- 每条必须同时有 `id`、`title`、`slot`
- `page` 与 `handler` 只能二选一；有 `page` 的是页面型，只有 `handler` 的是动作型
- 每个插件最多 **2 条** `uiEntries`
- 条目里出现未知字段会报 `UNSUPPORTED_FIELD`，同样导致清单无效
- 动作型入口执行前若 `confirm: true`，宿主先弹确认框

### 5. screens —— 整屏接管（JSON DSL）

把宿主的一整个屏幕交给插件渲染。与 `uiEntries` 的区别：槽位是在宿主布局里插一个条目，
`screens` 是整屏替换。

```json
"screens": [
  {
    "id": "custom-chat",
    "title": { "zh-CN": "自定义聊天页" },
    "target": "chat",
    "icon": "grid",
    "file": "ui/chat.json"
  }
]
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 插件内唯一局部 ID |
| `title` | 是 | 本地化标题 |
| `target` | 是 | 只能是 `chat` / `settings` / `drawer` 三者之一 |
| `icon` | 否 | Feather 图标名，缺省 `grid` |
| `file` | 二选一 | 包内静态 DSL 文件，必须以 `.json` 结尾，上限 512KB |
| `handler` | 二选一 | runtime 里返回 DSL 的函数名，需要同时声明 `runtime` |

硬性约束：

- `file` 与 `handler` 必须 **恰好有一个**，两个都写或都不写都判无效
- 同一插件对同一 `target` 只能声明一条，否则清单无效
- 最多 2 条
- 声明 `handler` 却没写 `runtime` → 清单无效

启用后，对应屏幕会被插件界面覆盖，顶部常驻一条「「插件名」接管中 · 刷新 · 恢复默认」提示条，
使用者可随时退回宿主默认界面。

#### DSL 文档格式

```json
{
  "v": 1,
  "state": { "count": 0 },
  "root": {
    "type": "column",
    "style": { "gap": 12, "padding": 16 },
    "children": [
      { "type": "text", "text": "你好", "style": { "fontSize": 20, "weight": 600 } },
      { "type": "button", "text": "点我", "action": { "type": "event", "name": "tap" } }
    ]
  }
}
```

- `v` 是 DSL 版本，当前只接受 `1`；对不上整份文档拒绝渲染
- `state` 是插件自己的初始状态，每次交互都会原样回传，省得插件自己记会话状态
- `root` 是根节点

**支持的节点**（`type`，出现表外类型整份文档判废）：

| 分类 | 节点 |
|---|---|
| 布局 | `column` `row` `box` `card` `scroll` `spacer` `divider` |
| 内容 | `text` `icon` `image` `badge` `progress` |
| 交互 | `button` `iconButton` `switch` `slider` `textField` |

节点通用字段：`id`、`style`、`children`、`action`。
类型专属字段：`text`、`icon`、`src`（图片地址）、`placeholder`、`value`、`checked`、`progress`、`min` / `max`、`size`、`width` / `height`。

**`style` 可用字段**：

```json
"style": {
  "background": "cardSurface",
  "foreground": "#FF3482FF",
  "border": "subtleBorder",
  "radius": 16,
  "padding": 16,
  "gap": 12,
  "fontSize": 15,
  "weight": 600,
  "align": "center",
  "fillWidth": true
}
```

- 颜色写语义令牌名（`primary` `surface` `onSurface` `cardSurface` `pageBackground` `mutedSurface` `subtleText` `subtleBorder` `success` `warning` `info` `error` 等）
  就自动跟随宿主亮/暗主题；也可写 `#RRGGBB` / `#AARRGGBB` 固定色
- `padding` 支持单个数字、`[垂直, 水平]`、`[上, 右, 下, 左]`
- `weight` 取 400 / 500 / 600 / 700

**`action` 三种**：

```json
{ "type": "event", "name": "submit" }
{ "type": "close" }
{ "type": "openUrl", "url": "https://example.com" }
```

- `event` 把交互回传给插件 `handler`，由插件返回新文档实现刷新（唯一能改界面的途径）
- `close` 等于使用者点「恢复默认」
- `openUrl` 仅接受 `https://`
- 除这三种以外一律不认，DSL 无法触发任意宿主能力

#### 动态界面的往返

写了 `handler` 的屏幕，宿主在打开时调一次，参数是 `{ target, screen }`，
插件需要返回一份完整 DSL 文档（`{"v":1,"root":{...}}`，或包一层 `{"document": ...}`）。

每次交互宿主再调同一个 handler，参数是：

```json
{
  "target": "chat",
  "screen": "custom-chat",
  "action": "event",
  "event": "submit",
  "nodeId": "name-field",
  "value": "当前输入值",
  "state": { "count": 0 }
}
```

返回值同样是完整 DSL 文档——宿主不维护插件的业务状态，每次都由插件重新给出整份界面。
想报错就返回 `{"error": "原因"}`，宿主会把原因显示在接管界面上。

最小 handler：

```js
'ui.dashboard': async (args) => ({
  v: 1,
  state: { clicks: args.state?.clicks || 0 },
  root: {
    type: 'column',
    style: { gap: 12, padding: 16 },
    children: [
      { type: 'text', text: `已点击 ${args.state?.clicks || 0} 次`, style: { fontSize: 18 } },
      { type: 'button', text: '点一下', action: { type: 'event', name: 'tap' } },
    ],
  },
})
```

> 注意：`event` 到达时 `args.event === 'tap'`，插件根据它决定把 `clicks` 加一。

#### 限制

- 节点最多 600 个，嵌套最深 24 层，单个文本最长 4000 字
- 图片 `src` 只接受 `https://`，单张上限 4MB
- 文档超过 512KB 直接拒绝

### 6. styleTokens —— 改宿主既有组件的样式

`screens` 是插件自己画一整屏，`styleTokens` 相反：**不动功能，只改外观**。
当前开放的是聊天输入框。

```json
"styleTokens": {
  "chatInputRadius": 26,
  "chatInputShadow": 26,
  "chatInputMinHeight": 58,
  "chatInputColor": "cardSurface"
}
```

| 字段 | 范围 | 说明 |
|---|---|---|
| `chatInputRadius` | 0–48 | 输入框圆角（dp），宿主默认 20 |
| `chatInputShadow` | 0–40 | 外阴影高度（dp），宿主默认 18；写 0 去掉阴影 |
| `chatInputMinHeight` | 0–160 | 最小高度（dp），不写则不限制 |
| `chatInputColor` | 语义令牌或 `#RRGGBB` | 输入框底色，宿主默认 `surface` |
| `bubbleRadius` | 0–32 | 用户气泡圆角（dp），宿主默认 10 |
| `bubbleUserColor` | 同上 | 用户气泡底色 |
| `bubbleUserTextColor` | 同上 | 用户气泡文字色 |
| `topBarColor` | 同上 | 聊天顶栏底色 |
| `topBarTextColor` | 同上 | 顶栏标题色 |
| `drawerColor` | 同上 | 会话侧栏底色 |
| `drawerTextColor` | 同上 | 侧栏条目文字色 |
| `messageTextColor` | 同上 | 消息正文（Markdown 段落）文字色 |
| `iconColor` | 同上 | 通用图标色；只影响原本用中性色的图标，不动选中态强调色 |

- 全部字段可选，只写想改的，其余继续用宿主值
- 不声明 `runtime` 也能用
- **多个插件同时声明时按字段合并**：每个字段取第一个声明它的插件（按插件 id 排序），
  所以「输入框插件」和「气泡插件」可以各管一块、同时生效；同名字段才会覆盖
- 写了越界值或非法颜色 → 清单无效（不会静默回退默认值）
- `iconColor` 不覆盖按钮选中态、错误色等强调图标，避免把状态语义改乱

### 7. runtime —— 独立进程

```json
"runtime": {
  "type": "javascript",
  "entry": "dist/main.js",
  "command": null,
  "dependencies": { "tool": "node", "file": "package.json" },
  "resident": false
}
```

- `type`：`javascript`（node 启动）或 `python`（python3 启动）
- `entry`：入口文件，包内相对路径
- `dependencies`：宿主要在启动前安装依赖时声明，`tool` 是依赖管理器，`file` 是依赖清单
- `resident: true`：进程不参与空闲回收，直到插件停用/卸载/版本替换。

**什么时候必须设 `resident: true`**：进程要在「没人主动调用」时也保持活着。典型场景：起本地服务（如 `providers` 的 baseUrl）、维持长连接、监听 hook 事件、提供悬浮窗、页面随时可能 `aicode.invoke` 过来。

漏设的症状：插件能装、页面能开，但过一会儿点按钮就报 `PLUGIN_EXITED`「插件进程 stdout 已关闭」——进程被空闲回收了。看着像崩了，实际是配置问题。

**反过来，只靠工具调用、没有后台逻辑的插件不设 resident**，让宿主按需拉起就行，省内存。

不管设不设 `resident`，runtime 都要自己保证进程不因异常退出，写法见「四、runtime 的 JSON-RPC 协议 → 进程必须自己保证不退出」。

### 8. hooks —— 生命周期与拦截

```json
{ "id": "h1", "event": "agent.tool.before", "mode": "transform", "handler": "hooks.before" }
```

三种模式：

| mode | 能做的事 |
|---|---|
| `observe` | 只观察，返回内容被忽略 |
| `transform` | 等待宿主应用结构化变更（改参数、改结果、改 HTTP 请求） |
| `review` | 只在宿主判定 ASK 后做代审核，返回 allow / deny / pass |

事件表：

| 事件 | 可用模式 | 说明 |
|---|---|---|
| `app.started` | observe | App 进程启动完成 |
| `agent.run.started` / `finished` | observe | 一次对话轮次 |
| `agent.request.started` / `finished` | observe | 一次模型请求 |
| `agent.tool.started` / `finished` | observe | 一次工具调用 |
| `agent.compaction` | observe | 上下文压缩 |
| `agent.tool.before` | transform | 可拒绝或改写工具参数 |
| `agent.tool.after` | transform | 只可改写成功结果的 `data` |
| `agent.http.before` | transform | 改写 provider 请求的 URL/headers/body，鉴权头以 `{{Token}}` 占位 |
| `agent.permission.review` | review | ASK 分支代审核 |
| `plugin.installed` / `enabled` / `disabled` / `uninstalled` | observe | 只派发给自身；卸载与停用在状态变更前同步等待最多 10 秒 |
| `backup.export.before` / `backup.import.finished` | observe | 需同时声明 `contributes.backup` |

observe 事件的 payload 只有运行元数据（会话 id、工具名等），不含消息正文、工具参数或结果。

`agent.run.*` 表示完整的一轮任务，`agent.request.*` 表示其中一次模型请求；两者通过 `runId` 关联，请求另有 `requestId`。完成状态为 `success` / `error` / `cancelled`，轮数上限失败通过 `errorCode: conversation_turn_limit` 标识，不转发原始错误正文。请求完成时任务仍可能在运行，自动续接前应调用 `host.chat.getRunState` 或 `host.chat.waitForRun` 确认空闲；取消不能触发失败续接。

`host.chat.createSession` 可传 `providerId`、`model` 与 `reasoningEffort`，用于继承原会话的模型选择；前两项必须成对提供，供应商需已启用且有可用凭据，模型需在该供应商配置中。省略时沿用默认模型设置。`host.chat.sendMessage` 仍交由聊天页按普通消息流程处理，聊天页尚未就绪时返回 `CHAT_UI_UNAVAILABLE`。

### hook handler 的返回结构

`observe` 返回什么都行，宿主会忽略。另两种模式返回结构固定，写错不会报错但也不生效。

**`transform`**——按需返回变更，不改就返回空对象：

```js
// agent.tool.before：拒绝或改写工具参数
return { decision: 'deny', reason: '插件拒绝访问 .env', logs: ['blocked read'] };
return { arguments: { ...newArgs } };        // 改写参数
return {};                                    // 放行，不做任何改动

// agent.tool.after：只可改写成功结果的 data
return { data: { ...newData } };

// agent.http.before：改写 provider 请求
return { url, headers, body };
```

`deny` 会让本次工具调用失败，`reason` 会展示给用户。

**`review`**——只在宿主判定 ASK 后才会调到，用于代用户审核：

```js
return { decision: 'allow' };                 // 直接放行
return { decision: 'deny', reason: '...' };   // 直接拒绝
return { decision: 'pass' };                  // 交给用户自己决定
```

需要人工介入时，可以先通过 `plugin.event` 把请求推给插件自己的页面，等页面回 `aicode.invoke(...)` 后再返回结果：

```js
// 插件 → 页面：推送待审核请求
sendEvent('chat.permission.requested', {
  requestId, sessionId, runId, scope: 'main',
  agentName, toolName, arguments: args, appForeground,
});
// 页面处理完后调 aicode.invoke('hooks.resolveReview', { requestId, decision })
```

`plugin.event` 是插件主动推送的通道，不受 hook 事件表限制，事件名自定义。

### 9. mcp —— 提供 MCP 服务器

```json
"mcp": [ { "id": "fs", "file": "mcp/fs.json" } ]
```

装好后服务器名带 `plugin__` 前缀，由插件启停控制，不会写进用户的 mcp.json。

### 10. providers —— 注册 AI 供应商

```json
"providers": [
  {
    "id": "echo",
    "name": { "zh-CN": "回显服务", "default": "Echo" },
    "description": { "zh-CN": "本地回显，用于验证链路。", "default": "Local echo service." },
    "format": "openai",
    "baseUrl": "http://127.0.0.1:PORT/v1"
  }
]
```

`id` / `name` / `description` / `format` / `baseUrl` 全部必填，`format` 目前只接受 `openai`。

宿主复用 OpenAI 适配器请求，不内置新的上游协议——所以插件要自己启动或提供那个 `baseUrl` 的服务（记得同时设 `runtime.resident: true`）。

注册后出现在「设置 → AI 供应商」列表，带「插件」标签；宿主侧 `id` 是 `plugin:<插件ID>:<供应商ID>`。

### 11. shizuku / overlay / backup / modelAccess

这四个都只接受 `purpose` 字段（说明用途，会显示给用户）：

```json
"shizuku": { "purpose": "以 shell 身份读取系统属性" }
"backup": { "purpose": "备份插件的本地数据" }
"modelAccess": { "purpose": "代用户发起模型请求" }
```

`shizuku` 需要用户在插件详情里单独授权后才能执行命令。

`overlay` 是悬浮窗页面，除 `purpose` 还能声明最多 3 个 `surfaces`：

```json
"overlay": {
  "purpose": "显示悬浮球",
  "surfaces": [
    { "id": "ball", "page": "pages/ball.html", "width": 64, "height": 64, "draggable": true, "snap": true }
  ]
}
```

`surfaces` 每项必填 `id` 与 `page`，`width` / `height` / `draggable` / `snap` 可选（`snap` 可为 `true` 或 `'both'`）。

浮层页面的完整 API 见「五、页面桥 → 可用方法 → 悬浮窗」，三个方向的消息传递：

```
浮层页面  --aicode.overlay.postMessage(payload)-->  runtime (host.overlay.create 收到的 surface)
浮层页面  <--host.overlay.postMessage({surface, payload})--  runtime
浮层页面  <--aicode.overlay.onMessage(fn)--  宿主推送（click / longPress / dragStart / dragEnd / hint）
```

`backup` 声明后可以配合 `backup.export.before` / `backup.import.finished` 两个 hook 写入与恢复自己的数据。

`modelAccess` 声明后才能用 runtime 的 `host.model.*` 创建模型句柄、发起流式调用、读图片。

### 12. configPage —— 插件配置页

```json
"configPage": "pages/config.html"
```

在插件详情页里多一个「打开配置」入口。这个页面就是普通 HTML 页面，配置的读写自己用 `fs.*` 或 `secrets.*` 落盘（宿主不提供 `config.get/set` 这类接口）。

持久化的标准做法是 `data://` 私有存储：

```js
const file = await aicode.fs.read('data://config.json');
const cfg = JSON.parse(file.content || '{}');
await aicode.fs.write('data://config.json', JSON.stringify(cfg));
```

`data://` 目录在卸载插件时自动清除，不进宿主备份。需要跨设备同步或进备份的数据，用 `contributes.backup` 配合 `backup.export.before` hook 自己处理。

### 13. 页面里的 UI 组件库

引了 `/__aicode/aicode-ui-vue.js` 后，`app.use(AicodeUI)` 即可用宿主组件，它们自动跟随宿主主题（暗色/亮色、字号缩放），比自己写样式更省事。

```html
<link rel="stylesheet" href="/__aicode/theme.css">
<link rel="stylesheet" href="/__aicode/ui.css">
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/aicode-ui-vue.js"></script>
<script src="/__aicode/aicode.js"></script>
<script>
  const app = Vue.createApp({ /* ... */ });
  app.use(AicodeUI);
  app.mount('#app');
</script>
```

常用组件：`<aicode-group>`（分组卡片）、`<aicode-row>`、`<aicode-field>`、`<aicode-input>`、`<aicode-textarea>`、`<aicode-switch>`、`<aicode-segmented>`（分段选择）、`<aicode-button>`、`<aicode-icon-button>`、`<aicode-badge>`、`<aicode-divider>`、`<aicode-select>`、`<aicode-dialog>`、`<aicode-bottom-sheet>`、`<aicode-swipe-row>`、`<aicode-empty>`、`<aicode-markdown>`。

不想用 Vue 的页面（比如悬浮层）直接引 `aicode.js` 写原生 JS 即可，不必引入前三个脚本。

主题变量可用：`--aicode-page-bg`、`--aicode-on-surface`、`--aicode-on-surface-variant`、`--aicode-font-sans`、`--aicode-font-scale`。

---

### 重置会话上下文（绕过上游轮数限制）

有些上游按「单会话累计轮数」限制（如 100 轮），到上限就拒服务。
这种情况不需要新建会话：调 `resetSessionContext` 把上下文起点移到当前时刻，
**历史消息全部保留在界面与数据库里**，只是不再发给模型，上游看到的就是一次新对话。

```js
// runtime 里（需要当前会话 id，一般从页面上下文或你自己的配置里拿）
await hostCall('host.chat.resetSessionContext', { sessionId })
// → { ok: true, sessionId, contextStartAt }
```

```js
// 页面里
aicode.call('chat.resetSessionContext', { sessionId })
```

`getSession` 返回的 `contextStartAt` 可以判断该会话是否被重置过（`0` 表示从未重置）。
重置不需要修改会话 id，历史记录与侧栏位置都不变。

## 四、runtime 的 JSON-RPC 协议

宿主启动插件进程后，双方通过 **stdout/stdin 上的 JSON-RPC 2.0 单行消息**通信。

### 最要紧的一条：`method` 是「调用类别」，不是 handler 名

宿主调用插件时，`method` 固定是下面三个之一，**handler 名放在 `params.handler`，参数放在 `params.args`**：

| `method` | 触发场景 |
|---|---|
| `tool.invoke` | AI 调用了插件贡献的工具 |
| `hook.invoke` | 插件声明的 hook 事件被触发 |
| `ui.invoke` | 插件页面调用 `aicode.invoke(...)`，或 `uiEntries` 的动作型入口 |

```json
// 宿主 → 插件（正确）
{ "jsonrpc": "2.0", "id": "call-1", "method": "tool.invoke",
  "params": { "handler": "tools.echo", "args": { "text": "hello" } } }
```

```json
// 宿主 → 插件（错误写法，不要照这个实现）
{ "jsonrpc": "2.0", "id": "call-1", "method": "tools.echo", "params": { "text": "hello" } }
```

按第二种写，宿主每次调用都会命中「未知 method」分支。典型症状是**页面一直转圈、工具调用超时**，看起来像插件没起来，其实进程活着但一直在回错误。

插件应答：

```json
{ "jsonrpc": "2.0", "id": "call-1", "result": { "text": "hello" } }
```

错误用 `error: { code, message }`，宿主会把它显示成工具失败原因。建议的错误码：`METHOD_UNKNOWN`（调用类别不认识）、`HANDLER_NOT_FOUND`（类别对但 handler 没注册）、`PLUGIN_ERROR`（handler 内部抛错）。

### 最小可用的分发骨架

```js
const handlers = {
  'tools.echo': async (args) => ({ text: `你好，${args.name}！` }),
  'hooks.boot': async () => ({ ok: true }),
  'ui.status': async () => ({ ok: true }),
};

const CALL_METHODS = ['tool.invoke', 'hook.invoke', 'ui.invoke'];

function handleHostRequest(id, method, params) {
  params = params || {};
  if (CALL_METHODS.indexOf(method) < 0) {
    return send({ jsonrpc: '2.0', id, error: { code: 'METHOD_UNKNOWN', message: method } });
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    return send({ jsonrpc: '2.0', id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({ jsonrpc: '2.0', id, result: result === undefined ? null : result }))
    .catch((error) => send({
      jsonrpc: '2.0', id,
      error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) },
    }));
}
```

`handlers` 的键名必须与 `plugin.json` 里各处的 `handler` 字段逐字一致——`tools[].handler`、`hooks[].handler`、`uiEntries[].handler` 都指同一张表。

### 插件 → 宿主：请求与应答

带 `id` 的是请求，宿主会用同一个 `id` 回 `{result}` 或 `{error}`；不带 `id` 的是单向通知，宿主不回。

```js
// 请求：调用宿主能力，等应答
let hostSeq = 0;
const hostPending = new Map();

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

// 通知：主动推送事件，不等应答
function sendEvent(name, payload) {
  send({ jsonrpc: '2.0', method: 'plugin.event', params: { name, payload } });
}
```

读 stdin 时要按消息形状分派，两类消息都从同一个管道进来：

```js
readline.createInterface({ input: process.stdin }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try { message = JSON.parse(line); } catch (e) { return; }

  if (message.method && message.id !== undefined) {
    handleHostRequest(message.id, message.method, message.params || {});   // 宿主调我们
  } else if (message.method) {
    // 无 id 的通知，按需处理或忽略
  } else if (message.id !== undefined) {
    handleHostResponse(message.id, message);                              // 宿主回我们
  }
});
```

### 进程必须自己保证不退出

宿主把 **stdout 关闭**当作插件死亡。进程意外退出后，页面侧报 `PLUGIN_EXITED`，用户看到的是「插件进程 stdout 已关闭」，很像插件崩了，实际可能只是某个 handler 抛了未捕获异常。

三条必须做到：

```js
// 1. 未捕获异常就地吞掉，回一条错误消息，绝不让进程退出
process.on('uncaughtException', (error) => {
  try { send({ jsonrpc: '2.0', id: 'crash-' + Date.now(),
    error: { code: 'PLUGIN_UNCAUGHT', message: String((error && error.stack) || error) } }); } catch (e) {}
});
process.on('unhandledRejection', (reason) => {
  try { send({ jsonrpc: '2.0', id: 'reject-' + Date.now(),
    error: { code: 'PLUGIN_UNHANDLED', message: String((reason && reason.message) || reason) } }); } catch (e) {}
});

// 2. 单条消息处理异常不许拖垮整个循环
rl.on('line', (line) => {
  try { /* 解析与分派 */ } catch (error) {
    /* 能拿到 id 就回错误 */
  }
});

// 3. stdin 关闭 = 宿主主动结束插件，此时才正常退出
rl.on('close', () => process.exit(0));
```

### 可调用的宿主方法（`host.*`）

下表带 ✅ 的已在多个线上插件中实际调用，其余按宿主文档列出。调用前建议 `try/catch`：方法缺失或权限不足会回 `error`，插件不该因此崩掉。

| 方法 | 用途 |
|---|---|
| `host.fs.read` / `write` / `stat` / `list` / `mkdir` / `delete` / `rename` / `copy` ✅ | 文件读写。路径支持 `data://` 私有存储，见下节 |
| `host.log` ✅ | 写日志到宿主日志文件，`{ level, message }` |
| `host.notify` ✅ | 系统通知 |
| `host.tip` ✅ | 界面内轻提示 |
| `host.app.info` / `host.app.isForeground` ✅ | 宿主版本、包名；是否在前台 |
| `host.chat.setDraft` ✅ | 往输入框写草稿 |
| `host.chat.openSession` / `getSession` / `renameSession` / `pinSession` / `deleteSession` | 会话管理 |
| `host.chat.resetSessionContext` | 重置上下文起点：历史全保留，但之后只把新消息发给模型 |
| `host.chat.getRunState` / `waitForRun` | 读运行状态、等待一轮结束 |
| `host.chat.listApprovals` / `resolveToolApproval` / `resolveQuestion` / `resolvePlanApproval` | 审批与提问 |
| `host.overlay.create` / `close` / `postMessage` ✅ | 悬浮窗的创建、关闭、给浮层页面发消息 |
| `host.shizuku.exec` ✅ | 以 shell 身份执行命令，需 `contributes.shizuku` 且用户已授权 |
| `host.model.listProviders` / `listModels` | 列供应商与模型 |
| `host.model.create` / `start` / `read` / `cancel` / `close` | 模型句柄：流式调用、读增量、取消。需 `contributes.modelAccess` |
| `host.clipboard.read` / `write` | 剪贴板 |
| `host.share.text` | 系统分享 |
| `host.ui.openUrl` | 打开链接 |
| `host.webview.login` | 打开受控 WebView 完成第三方网页登录，回传 `{ finalUrl, cookies }`；用户取消抛 `LOGIN_CANCELED` |
| `host.file.pick` | 拉起系统文件选择器，回传 `{ files: [{ name, size, mime, base64 }] }`；取消抛 `PICK_CANCELED` |
| `host.tools.execute` / `pending` / `resolve` | 调用宿主工具、处理待审批 |
| `host.mcp.status` | MCP 连接状态 |
| `host.http.request` | 受管 HTTP：会经过 `agent.http.before` hook，可配代理与超时 |

---

## 五、页面桥（HTML 页面里可用）

### 引入方式：宿主资源必须走绝对路径

页面被宿主加载在 `https://appassets.androidplatform.net/plugin/pages/<你的页面>`，只有 `/__aicode/` 这个前缀映射到宿主注入的资源。所以**桥脚本与 UI 资源一律用绝对路径**：

```html
<script src="/__aicode/aicode.js"></script>
```

写成相对路径 `aicode.js` 会被解析成 `/plugin/pages/aicode.js` → 404 → `window.aicode` 永远 undefined。**这是最容易踩的坑**：页面能正常打开、样式也正常，只有调用不通。

| 资源 | 路径 |
|---|---|
| 页面桥 | `/__aicode/aicode.js` |
| Vue 运行时 | `/__aicode/vue.global.prod.js` |
| 宿主 UI 组件库 | `/__aicode/aicode-ui-vue.js` |
| 主题变量 | `/__aicode/theme.css` |
| 基础样式 | `/__aicode/ui.css` |

`aicode` 是历史上游沿用的全局名（已有插件都这么写），宿主同时把同一对象挂到 `window.nox`，两种写法等价；脚本路径也支持 `/__aicode/nox.js`。

例外：`eruda.min.js` 是调试面板，用相对路径 `src="eruda.min.js"` 也能加载，不影响功能。

### 调用方式

```js
const info = await aicode.call('app.info');
await aicode.call('fs.write', { path: 'data://note.md', content: 'hi' });
aicode.events.on('fs.changed', (payload) => { /* ... */ });
```

页面里还有几个**顶层快捷方法**，与 `aicode.call(...)` 等价但更好写：

```js
await aicode.invoke('tools.echo', { name: '世界' });  // 调自己 runtime 的 handler
await aicode.bash('ls -la');                          // 跑命令
await aicode.tools.execute('Bash', { command: 'ls' }); // 调宿主工具
await aicode.toast('已保存');                          // 轻提示
const ctx = await aicode.context();                    // { pluginId, pageId, visible, ... }
```

`aicode.invoke(handler, args)` 是最常用的一个：handler 名与 runtime 的 `handlers` 键名一致，宿主把它转成 `ui.invoke` 发给插件进程。**桥没有 `plugin` 命名空间**，`aicode.plugin.invoke(...)` 不存在。

**网页登录**：`aicode.webview.login({ url, title?, finishText?, cookieUrls?, accentColor?, cornerRadius?, dimAlpha?, showClose? })` 在 App 内打开受控全屏 WebView，
用户完成第三方网页登录后返回 `{ finalUrl, cookies: { 地址: cookie 串 } }`；用户点「关闭」或返回抛 `LOGIN_CANCELED`。
可选样式：`accentColor`（`#RRGGBB`，完成按钮与强调色）、`cornerRadius`（dp，卡片圆角）、`dimAlpha`（0–0.9，遮罩透明度）、`showClose`（是否显示关闭）。
WebView 开启 JavaScript、DOM Storage 与第三方 Cookie，登录态写入 App 级 CookieManager 并落盘，下次打开仍登录。
需要 Nox 1.0.10+。

```js
const result = await aicode.webview.login({
  url: 'https://example.com/login',
  title: '登录 Example',
  cookieUrls: ['https://example.com', 'https://api.example.com']
});
await aicode.fs.write('data://session.json', JSON.stringify(result));
// result.finalUrl / result.cookies
```

### 可用方法

**文件**：`fs.read`、`fs.readBinary`、`fs.write`、`fs.append`、`fs.list`、`fs.stat`、`fs.mkdir`、`fs.delete`、`fs.rename`、`fs.copy`、`fs.watch`、`fs.unwatch`

文件路径支持三种写法：绝对路径（如 `/root/note.md`）、`~/` 开头的家目录、以及 **`data://` 私有存储**。

**`data://` 是插件配置持久化的标准做法**：映射到插件自己的数据目录，卸载插件时自动清除，不进备份。

```js
await aicode.fs.write('data://config.json', JSON.stringify(cfg));
const file = await aicode.fs.read('data://config.json');
const cfg = JSON.parse(file.content || '{}');
```

runtime 侧同样可用 `host.fs.read({ path: 'data://config.json' })`。**宿主不提供 `config.get/set` 这类接口**，配置就是自己读写一个 `data://` 下的 JSON 文件。

**悬浮窗**：`overlay.create`、`overlay.update`、`overlay.close`、`overlay.self`、`overlay.resize`、`overlay.setDragRegion`、`overlay.postMessage`、`overlay.onMessage`

**网页登录**：`webview.login`（见上节示例）

**文件选择**：`file.pick({ multiple?, mimeTypes? })` 拉起系统文件选择器，返回 `{ files: [{ name, size, mime, base64 }] }`；单文件 ≤ 2MB、总计 ≤ 4MB，最多 8 个；取消抛 `PICK_CANCELED`。

只在 `overlay.surfaces` 声明的浮层页面里可用。`overlay.postMessage({type, ...})` 发给 runtime，runtime 侧用 `host.overlay.postMessage({surface, payload})` 发回来，浮层页面用 `overlay.onMessage(fn)` 收。

**会话**：`chat.createSession`、`listSessions`、`openSession`、`getSession`、`renameSession`、`pinSession`、`deleteSession`、`listMessages`、`sendMessage`、`setDraft`、`stop`、`getRunState`、`waitForRun`、`listApprovals`、`resolveToolApproval`、`resolveQuestion`、`resolvePlanApproval`

**工具**：`tools.list`、`tools.execute`、`tools.pending`、`tools.resolve`

**终端**：`terminal.run`、`terminal.poll`、`terminal.write`、`terminal.stop`、`terminal.close`、`terminal.list`

**其他**：`app.info`、`app.isForeground`、`context`、`clipboard.read/write`、`share.text`、`ui.openUrl`、`ui.close`、`ui.overlay`、`bash`、`shizuku.exec`、`secrets.get/set/list/delete`、`toast`、`tip`

### 调用插件自己的进程

页面要驱动 runtime 里的 handler，用**顶层方法** `aicode.invoke`，不是 `aicode.plugin.invoke`：

```js
// 对：调用 runtime 中注册名为 startGateway 的 handler
const state = await aicode.invoke('startGateway', { port: 7864 });

// 错：桥没有 plugin 命名空间，会报 "aicode.plugin.invoke 缺失"
// aicode.plugin.invoke('startGateway', {...})
```

同理，命令行走 `aicode.bash(cmd)`，工具走 `aicode.tools.execute(name, args)`。

`aicode.events.on(name, handler)` 订阅宿主推送事件；`fs.changed` 与 `ui.lifecycle` 是带上下文的事件。

页面里还可以直接用宿主提供的 UI 资源：`vue.global.prod.js`、`aicode-ui-vue.js`、`ui.css`、`eruda.min.js`、`markdown/`，路径相对页面所在目录。

---

## 六、打包、安装、调试

**打包**：把包内文件直接压进 zip 根目录（不要把外层文件夹也压进去）。

```bash
cd my-plugin && zip -r ../my-plugin-1.0.0.zip . -x '*.DS_Store'
```

**安装**：设置 → 插件 → 右上角「+」选 zip。

**组合包**：把多个插件包放进同一个 zip 也能一次导入——根目录不放 `plugin.json`，改为每个插件一个一级子目录：

```
bundle.zip
├── my-plugin-a/
│   ├── plugin.json
│   └── dist/main.js
└── my-plugin-b/
    ├── plugin.json
    └── pages/panel.html
```

宿主识别规则：根目录没有 `plugin.json`、且一级子目录里有，就按组合包处理，逐个安装并给出成功/失败清单。只认一级子目录，插件包内部的嵌套目录不会被误判。单个插件失败不影响其余。

**开发中目录**：设置 → 插件 → 齿轮 → 开发者模式。开启后 `~/.nox/plugin-dev/<项目目录>/` 下每个含 `plugin.json` 的子目录都会出现在「开发中」标签页，改动文件会自动重打包替换，不用反复导入 zip。

**用 `plugin` 工具自造插件**（不必等人去界面点）：

宿主给 AI 一个内置工具 `plugin`，直接管理本机插件：

| action | 作用 |
|---|---|
| `list` | 列出已装插件、启停状态、各自贡献的工具/技能/供应商，并给出 `devDir` 路径 |
| `install` | `source=dev` 按目录名装开发目录里的项目；`source=zip` 按路径装本地包 |
| `reload` | 重新打包并热替换开发目录里的项目，改完代码立刻生效 |
| `enable` / `disable` / `uninstall` | 启停与卸载 |
| `logs` | 读该插件最近若干行运行日志（按 `[插件ID]` 前缀筛） |

自造流程：

1. 用 `writeFile` 在 `~/.nox/plugin-dev/<目录名>/` 写 `plugin.json` 与代码
2. `plugin(action="install", source="dev", path="<目录名>")`
3. 新工具在同一回合内即可使用；技能正文用 `loadSkill` 读
4. 改完代码 → `plugin(action="reload", plugin_id="...")`
5. 起不来 → `plugin(action="logs", plugin_id="...")` 看 `stderr` 与退出码

`reload` 只对开发目录里的项目有效。开发目录在容器内是 `~/.nox/plugin-dev/`，宿主侧对应 App 私有的 `files/nox/plugin-dev/`。

**排查清单**（按顺序看）：

1. 插件在「已安装」列表里吗？不在 → 安装没走完
2. 开关能点吗？灰的 → 清单解析失败，详情页会指出具体字段
3. 有 `uiEntries` 吗？只声明 `tools` 的插件不会在界面上产生任何入口，这是正常的
4. 页面打不开 → 检查 `page` 路径是否真实存在于包内、是否 `.html` 结尾
5. 工具调不通 → 检查 `handler` 名与 runtime 里注册的一致、`inputSchema` 是否合法 JSON
6. 页面能开但调用一直转圈 → 先看 `typeof aicode` 是不是 `undefined`（桥没加载），再查 runtime 是不是在回 `METHOD_UNKNOWN`（分发写错）
7. 报 `PLUGIN_EXITED` / 「插件进程 stdout 已关闭」→ 进程挂了或被回收：确认需常驻的已设 `resident: true`，且挂了 `uncaughtException` 兜底
8. 报 `aicode is not defined` → 页面没引入 `/__aicode/aicode.js`，或写成了相对路径

**日志在哪**：`/storage/emulated/0/Android/data/com.nox/files/logs/`，应用内「设置 → 日志」也能看，调成 Debug 级别能看到插件通信细节。

---

## 七、发布到商店

完整版文档在仓库里：`https://github.com/xzyy03/Nox-Plugins-Store/blob/main/docs/plugin-dev.md`
（App 里也有入口：插件 → 齿轮 →「插件开发文档」）。

1. 在 App 里点插件页顶栏的上传图标 → 选包
2. 包被写入暂存仓库 `yyds0399/Muse-Plugins-Inbox` 的 `incoming/`
3. 商店仓库 Actions 每 15 分钟扫描，解包读清单、审计、通过后复制进 `packages/` 并更新 `index.json`
4. 索引更新后约 15 分钟内出现在所有用户的插件商店

**审计会拦截**：路径越界、可执行文件、`eval` / `new Function`、`Runtime.getRuntime`、`su` 提权、`pm install/grant`、`/bin/sh` 或 `sh -c` 拼接。

**审计放行**（插件是 Node 进程，属正常能力）：`child_process`、`spawn`、`execSync`、`require('fs'/'http'/'https'/'net'/'os')`、宿主自带的 `eruda.min.js` 与 `vue.global.prod.js`。

**幂等**：索引里已有同 id 同版本时跳过；重复 id 不会通过格式校验。

## 八、第三方索引源

设置 → 插件 → 齿轮 → 「第三方插件源」，一行一个地址。宿主会把常见的 GitHub 页面地址自动转成索引直链：

| 填写 | 实际拉取 |
|---|---|
| `https://github.com/o/r` | `https://raw.githubusercontent.com/o/r/main/index.json` |
| `https://github.com/o/r/tree/dev` | `https://raw.githubusercontent.com/o/r/dev/index.json` |
| `https://github.com/o/r/blob/main/index.json` | 同路径的 raw 地址 |

默认分支不是 `main` 时自动回退到 `master` 重试。自建源直接填 `index.json` 的完整地址即可，不会做任何改写。

索引文件格式与官方一致（`schema_version: 1` + `plugins[]`）。

---

## 九、最小可用插件

`plugin.json`：

```json
{
  "manifestVersion": 1,
  "id": "demo.hello",
  "name": { "zh-CN": "你好插件", "default": "Hello Plugin" },
  "version": "1.0.0",
  "publisher": "demo",
  "repository": "https://github.com/demo/hello",
  "description": { "zh-CN": "最小示例：一个工具加一个设置页入口。", "default": "Minimal sample." },
  "license": "MIT",
  "icon": "box",
  "engines": { "pluginApi": "^1.0.0" },
  "runtime": { "type": "javascript", "entry": "dist/main.js" },
  "contributes": {
    "tools": [
      {
        "id": "hello",
        "title": { "zh-CN": "打招呼" },
        "description": { "zh-CN": "向指定的人问好。" },
        "inputSchema": "schemas/hello.input.json",
        "handler": "tools.hello"
      }
    ],
    "uiEntries": [
      {
        "id": "panel",
        "title": { "zh-CN": "你好面板" },
        "slot": "settings.ai",
        "page": "pages/panel.html",
        "icon": "grid"
      }
    ]
  }
}
```

`schemas/hello.input.json`：

```json
{
  "type": "object",
  "properties": { "name": { "type": "string", "description": "要问候的名字" } },
  "required": ["name"]
}
```

`dist/main.js`：

```js
'use strict';
const readline = require('readline');

const send = (msg) => process.stdout.write(JSON.stringify(msg) + '\n');

const handlers = {
  // 键名与 plugin.json 里 tools[].handler 逐字一致
  'tools.hello': async (args) => ({ text: `你好，${args.name}！` }),
};

const CALL_METHODS = ['tool.invoke', 'hook.invoke', 'ui.invoke'];

function handleHostRequest(id, method, params) {
  params = params || {};
  if (CALL_METHODS.indexOf(method) < 0) {
    return send({ jsonrpc: '2.0', id, error: { code: 'METHOD_UNKNOWN', message: method } });
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    return send({ jsonrpc: '2.0', id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({ jsonrpc: '2.0', id, result: result === undefined ? null : result }))
    .catch((error) => send({
      jsonrpc: '2.0', id,
      error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) },
    }));
}

// 未捕获异常不许拖垮进程（宿主把 stdout 关闭当作插件死亡）
process.on('uncaughtException', (e) => {
  try { send({ jsonrpc: '2.0', id: 'crash-' + Date.now(),
    error: { code: 'PLUGIN_UNCAUGHT', message: String((e && e.stack) || e) } }); } catch (x) {}
});

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  try {
    if (msg.method && msg.id !== undefined) {
      handleHostRequest(msg.id, msg.method, msg.params || {});
    }
    // 无 id 的通知与宿主回包，本例不需要处理
  } catch (e) { /* 单条消息异常不影响后续 */ }
});
rl.on('close', () => process.exit(0));
```

`pages/panel.html`：

```html
<!doctype html>
<meta charset="utf-8">
<script src="/__aicode/aicode.js"></script>
<body>
  <button id="go">读宿主版本</button>
  <pre id="out"></pre>
  <script>
    document.getElementById('go').onclick = async () => {
      const info = await aicode.call('app.info');
      document.getElementById('out').textContent = JSON.stringify(info, null, 2);
    };
  </script>
</body>
```

打包成 zip 后从「设置 → 插件 → +」导入，插件页会出现条目，装好后「设置 → AI 配置」末尾出现「你好面板」。

---

## 十、容易踩的坑

- `slot` 拼错 → 整个清单解析失败，插件状态变错误、开关灰掉。先抄表里的字面值。
- `contributes` 全空（或只写了不认识的新键）→ 清单无效。至少要有一项真实贡献。
- 每个插件最多 2 条 `uiEntries`，超了直接无效。
- `backup.data` 槽位不能带 `page`，只能是 `handler` 动作。
- 声明 `tools` 却没写 `runtime` → 无效。
- 页面文件必须真实存在于包内；路径大小写敏感。
- 常驻型插件（起本地服务、维持长连接、监听 hook、提供悬浮窗）要设 `runtime.resident: true`，否则会被空闲回收，用户点按钮时报 `PLUGIN_EXITED`。
- 宿主调插件时 `method` 是 `tool.invoke` / `hook.invoke` / `ui.invoke`，handler 名在 `params.handler`。把 handler 名当 `method` 写，插件会一直回 `METHOD_UNKNOWN`，症状是页面转圈、工具超时。
- 页面里调自己 runtime 的 handler 用 `aicode.invoke(handler, args)`；配置持久化写 `data://xxx.json`。
- `uiEntries` 的动作型入口（只有 `handler` 没有 `page`）也走 `ui.invoke`，与页面里 `aicode.invoke` 同一条链路。
- 改了插件版本号才会触发「更新」，同版本重装会被当作替换而非更新。
- 商店索引更新后 App 需要下拉刷新才看到新条目。
- 页面报 `aicode is not defined` → 检查 `<script src="/__aicode/aicode.js">` 的**绝对路径**。
- 页面报 `aicode.plugin.invoke 缺失` → 用 `aicode.invoke(handler, args)`，桥没有 `plugin` 命名空间。
- 本地端口连不上（如 `curl 127.0.0.1:7864` 失败）多半是 handler 没被调用起来，先修上面的调用方式。
- 声明了 `contributes.providers` 却没设 `resident: true` → 宿主空闲回收后本地服务就没了，供应商列表里点进去报连接失败。
- 写文件用了 `data://` 以外的路径，容器重建后可能丢；要持久化的配置就写 `data://`。
- `icon` 写图片路径时，文件必须真的打进包里；只写 `icons/icon.png` 而没放文件，图标位会空着。
