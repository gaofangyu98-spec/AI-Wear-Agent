# AI-Wear-Agent

基于 LangChain / LangGraph 的多模态 AI 穿搭助手，集成图片审核、CLIP 向量检索、以图搜图与智能图片编辑 Agent。

## 项目结构

```
AI-Wear-Agent/
├── AIWear/                  # Java Spring Boot 后端（3.2）
│   ├── src/main/java/...
│   └── src/main/resources/application.yml.example
├── AIWearPython/            # Python Flask 智能服务
│   ├── server.py            # 主服务（图片审核/CLIP向量/搜索/Agent编辑）
│   ├── download.py           # CLIP 模型下载脚本
│   └── requirements.txt
```

## 快速开始

### 1. Python 服务

```bash
cd AIWearPython
cp .env.example .env        # 填入 DASHSCOPE_API_KEY
pip install -r requirements.txt
python server.py
```

### 2. Java 服务

```bash
cd AIWear
cp src/main/resources/application.yml.example src/main/resources/application.yml
# 填入 MySQL/Redis/OSS/邮件等配置
mvn spring-boot:run
```

### 3. 下载 CLIP 模型（首次运行前）

```bash
cd AIWearPython
python download.py
# 将打印的路径填入 server.py 的 CLIP_MODEL_DIR
```

## API 接口

| 接口 | 方法 | 功能 |
|---|---|---|
| `/api/validate-image` | POST | 图片内容审核（是否为服装/人像） |
| `/api/upload-image` | POST | 上传图片，生成描述 + CLIP 512维向量，写入 Redis |
| `/api/search-image` | POST | 按文/按图检索用户图片 |
| `/api/skill/image` | POST | Agent 编排：编辑/合并图片 |

## 技术栈

- **Java**: Spring Boot 3.2 / MyBatis-Plus / MySQL / Redis / 阿里云 OSS
- **Python**: Flask / CLIP (openai-mirror) / 通义千问 (qwen-vl-max / qwen-plus) / Dashscope 图像编辑
- **AI**: LangChain / LangGraph (deepagents) / 智能体编排
