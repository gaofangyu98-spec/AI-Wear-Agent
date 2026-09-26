# AI-Wear-Agent

多模态 AI 穿搭助手，基于 Java Spring Boot + Python Flask 双服务架构，提供从图片审核 → 向量索引 → 以图搜图 → Agent 智能编辑/合并的完整图片处理流水线。

用户上传穿搭图片后，系统自动审核内容合规性、提取 CLIP 视觉向量、支持按文字描述或图片检索历史图片，并通过 AI Agent 自动编排图片编辑或合并操作。

## 核心亮点

| 亮点 | 说明 |
|---|---|
| 双服务架构 | Java 管业务/鉴权/存储，Python 管大模型/向量推理，RESTful 协同，各干各的擅长事 |
| LangGraph Agent | 基于 deepagents 注册图片编辑/合并两个工具，LLM 按自然语言指令自动选工具调用 |
| CLIP 向量检索 | 本地 CLIP-ViT-Base 抽 512 维向量 L2 归一化，Redis 按用户分 Set 存储，余弦相似度毫秒级召回 |
| 双层内容审核 | qwen-vl-max 客观描述图片 → qwen-plus 按规则判定，规则和模型解耦 |
| 跨语言协同 | Java 下载 OSS 临时文件 → 传 Python 处理 → 结果图二次上传 OSS → finally 清理临时文件 |
| AOP 日志切面 | Java 侧 ApiLogAspect 统一记录接口调用日志，不侵入业务代码 |
| 配置隔离 | 真实配置走 application.yml.example / .env.example 模板，.gitignore 排除敏感配置 |

---

## 一、项目背景

做一个穿搭场景的 AI 图片处理工具：用户上传衣服图或人像图，系统自动判断是不是穿搭相关图片、给图片打标签、支持按图或按文字找历史图片、还能用自然语言让 AI 把衣服换个背景、把衣服和人像合成一张穿搭图。

技术上的难点是：业务侧要做鉴权、数据库、文件存储，Java/Spring Boot 生态最熟；但 AI 推理侧（CLIP 模型、LangChain、通义千问 SDK）全是 Python 生态。所以拆成两个服务，通过 HTTP 接口协同。

## 二、架构总览

```
用户/客户端
    │
    ▼
┌─────────────────────────────────────────────────┐
│  Java Spring Boot (AIWear) — 端口 8080           │
│  ┌───────────┐ ┌──────────┐ ┌────────────────┐  │
│  │ User API  │ │ File API │ │ Record API     │  │
│  │ 注册/登录 │ │ 上传/搜图 │ │ 调用历史       │  │
│  └─────┬─────┘ └────┬─────┘ └────────────────┘  │
│        │             │                           │
│  ┌─────▼─────────────▼─────┐                     │
│  │  Service 层             │                     │
│  │  JWT / MySQL / Redis    │                     │
│  │  OSS 文件存储           │                     │
│  └─────┬───────────────────┘                     │
│        │ HTTP 调用 Python 服务                   │
└────────┼─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│  Python Flask (AIWearPython) — 端口 5000         │
│  ┌──────────┐ ┌──────────┐ ┌────────────────┐  │
│  │ 审核     │ │ 向量化   │ │ Agent 编排     │  │
│  │ /validate│ │ /upload  │ │ /skill/image   │  │
│  └────┬─────┘ └────┬─────┘ └───────┬────────┘  │
│       │            │               │            │
│  ┌────▼────────────▼───────────────▼────┐       │
│  │  通义千问 VL / CLIP / Dashscope      │       │
│  │  LangChain / deepagents              │       │
│  └──────────────────────────────────────┘       │
└─────────────────────────────────────────────────┘
```

为什么拆两个服务：
- Java 侧是传统 Web 后端，鉴权、事务、OSS 存储这些事 Java/Spring Boot 做起来稳，MyBatis-Plus 操作数据库方便
- Python 侧是 AI 生态，CLIP 模型加载、LangChain、通义千问 SDK 全是 Python 优先
- 塞在一个服务里要么 Java 硬写 Python AI 代码，要么 Python 硬写 Web 后端，都不专业

## 三、项目结构

```
AI-Wear-Agent/
├── AIWear/                          # Java Spring Boot 后端
│   ├── src/main/java/com/bite/aiwear/
│   │   ├── controller/            # UserController, FileController, RecordController
│   │   ├── service/               # UserService, FileService, PythonImageService
│   │   │   └── impl/              # 接口实现
│   │   ├── entity/                # User, ImageFile, Record
│   │   ├── mapper/                # MyBatis-Plus Mapper 接口
│   │   ├── dto/
│   │   │   ├── request/           # AuthRequest, EditImageRequest 等
│   │   │   └── response/          # AuthResponse, SearchImageResponse 等
│   │   ├── util/                  # JwtUtil, OssService, EmailService, VerificationCodeService
│   │   ├── commom/               # 公共类
│   │   └── log/                   # ApiLogAspect (AOP 日志切面)
│   ├── src/main/resources/
│   │   ├── application.yml.example
│   │   ├── mapper/                # MyBatis XML
│   │   ├── static/
│   │   └── templates/
│   └── pom.xml
│
├── AIWearPython/                  # Python Flask 智能服务
│   ├── server.py                  # 主服务（审核/向量/搜索/Agent）
│   ├── download.py                # CLIP 模型下载脚本
│   ├── requirements.txt           # Python 依赖
│   └── .env.example              # DASHSCOPE_API_KEY 配置模板
│
├── .gitignore
├── LICENSE
└── README.md
```

## 四、技术栈

| 层面 | 组件 | 选型 |
|------|------|------|
| Java 后端 | 框架 | Spring Boot 3.2 + MyBatis-Plus |
| | 数据库 | MySQL 8.0 |
| | 缓存 | Redis (Lettuce) |
| | 鉴权 | JWT (jjwt 0.11.5) |
| | 文件存储 | 阿里云 OSS |
| | 邮件 | Spring Mail（邮箱验证码） |
| | 日志 | AOP 切面统一记录接口日志 |
| | Java 版本 | 17 |
| Python 智能层 | Web 框架 | Flask 3.1 |
| | 视觉模型 | CLIP (openai/clip-vit-base-patch16) |
| | 多模态 LLM | qwen-vl-max（看图描述） |
| | 文本 LLM | qwen-plus（审核/Agent 决策） |
| | 智能体框架 | LangChain + deepagents |
| | 图片编辑 | Dashscope 图像编辑模型（qwen-image-edit-plus） |
| | 向量存储 | Redis |

---

## 五、图片处理流水线

```
① 内容审核 → ② 上传 OSS → ③ 向量化 → ④ 检索 → ⑤ Agent 编辑/合并
```

### 5.1 内容审核（POST /api/validate-image）

双层审核设计：
1. qwen-vl-max（多模态模型）看图，客观生成图片描述和关键词（"一件红色卫衣，白底"）
2. qwen-plus（文本模型）拿到描述后按业务规则判断：是不是衣服/人像

为什么拆两步而不是直接让 VL 模型判定：
- 直接让 VL 判定"是不是衣服"容易被 Prompt 带偏，输出不稳定
- 拆成"客观描述 + 规则判定"，描述由多模态模型做，判断由文本模型做，规则改了不用动 VL Prompt，解耦

### 5.2 上传 OSS（POST /api/file/upload/image）

- Java 接收 Multipart 文件
- 上传到阿里云 OSS，返回图片 URL
- MySQL 记录 ImageFile 元数据（用户 ID、OSS URL、上传时间）

### 5.3 向量化（POST /api/upload-image Python）

1. Java 把 OSS URL 传给 Python
2. Python 从 OSS 下载图片到内存
3. qwen-vl-max 生成图片描述和关键词
4. CLIP 模型抽 512 维向量，做 L2 归一化（归一化后余弦相似度等价于点积，计算更快）
5. Redis 写两个 key：
   - clip:image:{imageId}：Hash 存 userId、ossUrl、description、keywords、vector
   - clip:user:{userId}:image_ids：Set 存该用户所有图片 ID

CLIP 模型是全局单例，模块加载时初始化一次，用 threading.Lock 做懒加载，避免每次请求重新加载几百 MB 模型。

### 5.4 检索（POST /api/file/search）

两种检索方式：

- 图搜图：上传一张查询图，CLIP 抽向量，遍历该用户所有图片向量算余弦相似度，>= 0.7 的作为结果
- 文搜图：用户输入文本，从 Redis 取该用户所有图片的文字描述，用 difflib SequenceMatcher + 2-gram Jaccard 算文本相似度，取 max，>= 0.1 的作为结果

为什么图搜图用 CLIP、文搜图用文本相似度：
- 两张图"像不像"是语义层面的，文本匹配算不出来，必须用 CLIP 向量
- 文搜图文本本身短，直接算文本相似度比再走一次 CLIP 文本向量更快，阈值放宽多召回

### 5.5 Agent 编辑/合并（POST /api/file/edit | /api/file/merge）

1. Java 下载用户指定的 OSS 图片到本地临时文件
2. Java 调 Python /api/skill/image，传图片路径和用户自然语言指令
3. Python 用 deepagents.create_deep_agent 创建 Agent，注册两个工具：
   - edit_image_tool：单图编辑，调 qwen-image-edit-plus
   - merge_image_tool：双图合成，调 qwen-image-edit-plus 带两张图
4. Agent 根据用户指令和图片数量自动选工具（传一张图就是编辑，传两张就是合并）
5. 底层模型返回结果图 URL
6. Python 把结果传回 Java，Java 把结果图二次上传到 OSS（image/edited/ 或 image/merged/ 目录，UUID 命名）
7. finally 块删本地临时文件，不管成功失败都清

---

## 六、快速开始

### 前置依赖

- JDK 17+, Python 3.10+, MySQL 8.0+, Redis, Maven
- 阿里云 OSS 账号 + 通义千问 Dashscope API Key

### 1. 下载 CLIP 模型（仅首次）

```bash
cd AIWearPython
python download.py
# 将打印的路径填入 server.py 的 CLIP_MODEL_DIR
```

### 2. 启动 Python 服务

```bash
cd AIWearPython
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY
pip install -r requirements.txt
python server.py
# 默认 http://0.0.0.0:5000
```

### 3. 启动 Java 服务

```bash
cd AIWear
cp src/main/resources/application.yml.example src/main/resources/application.yml
# 填入 MySQL/Redis/OSS/邮件配置
mvn spring-boot:run
# 默认 http://localhost:8080
```

---

## 七、Java API 接口

### 用户模块 /api/user/*

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/user/send-code | 发送邮箱验证码 |
| POST | /api/user/auth | 统一认证（注册/登录），返回 JWT token |

### 文件模块 /api/file/*

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/file/upload/image | 上传图片到 OSS，返回图片 ID |
| GET | /api/file/my-images | 当前用户的所有图片列表 |
| POST | /api/file/search | 以图搜图 / 文本搜图 |
| POST | /api/file/edit | AI 编辑单张图片 |
| POST | /api/file/merge | AI 合并两张图片 |

### 记录模块 /api/record/*

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/record/my | 查看调用记录，?action=edit|merge 过滤 |

---

## 八、Python API 接口

| 方法 | 路径 | 说明 | 输入 | 输出 |
|------|------|------|------|------|
| POST | /api/validate-image | 合规审核 | Multipart file | { allow: bool } |
| POST | /api/upload-image | 向量化 | userId, ossUrl | { success, imageId } |
| POST | /api/search-image | 检索 | userId + (query 或 file) | { results: [...] } |
| POST | /api/skill/image | Agent 编辑/合并 | instruction + file(s) | { url: "oss..." } |

---

## 九、Redis 数据模型

```
clip:image:{imageId} → Hash { userId, ossUrl, description, keywords, vector: [512维float] }
clip:user:{userId}:image_ids → Set { imageId1, imageId2, ... }
```

为什么用 Set 存用户图片列表而不是 keys *：
- keys * 在线上是阻塞命令，数据量大了会卡整个 Redis
- 按用户分 Set，每个用户只查自己的图片，O(N) 但 N 很小

---

## 十、安全说明

- 真实配置文件已通过 .gitignore 排除，不提交到仓库
- JWT 密钥在本地配置中管理，源码无硬编码
- 邮箱验证码 5 分钟有效期
- Python 服务仅内部网络监听，不对外暴露
- OSS 文件名用 UUID，避免用户猜到图片路径

---

## 十一、可改进点

1. 图片量上来后全遍历算余弦会慢，应该上 Milvus / FAISS 做向量索引
2. Agent 当前只有两个工具，复杂指令下可能误判工具选择，应该加 few-shot 示例
3. Python 服务没有限流和重试，大模型接口超时会直接抛错，应该加重试和降级
4. 同一张图重复上传会重复调大模型和重复存向量，应该加图片 hash 去重
5. 编辑结果没有版本管理，多次编辑同一张图会覆盖
6. 没有做图片人脸/衣服主体检测，上传无关图片只能靠 VL 模型兜底
