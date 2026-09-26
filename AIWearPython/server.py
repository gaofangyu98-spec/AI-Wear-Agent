"""
AIWear 图片智能服务（Flask）。

提供四类 HTTP 接口：
  - POST /api/validate-image  图片内容审核（是否为服装/人像）
  - POST /api/upload-image    上传图片：生成文字描述 + CLIP 向量，写入 Redis
  - POST /api/search-image    按文/按图检索用户图片
  - POST /api/skill/image     Agent 编排：编辑/合并图片

依赖：Flask、Redis（存图片描述与 512 维 CLIP 向量）、本地 CLIP 模型、
通义千问 qwen-vl-max（看图描述）/ qwen-plus（审核、Agent 决策）、
Dashscope 图像编辑模型、deepagents 智能体编排。
"""

import base64
import json
import os
import tempfile
import uuid
import threading
import re
import difflib

from PIL import Image
import requests
import redis
import torch
from transformers import CLIPModel, CLIPProcessor
from dashscope.aigc import MultiModalConversation
from deepagents import create_deep_agent
from flask import Flask, request, jsonify
from io import BytesIO

from flask.cli import load_dotenv
from langchain_community.chat_models.tongyi import ChatTongyi
from langchain_core.messages.human import HumanMessage
from langchain_core.prompts.chat import ChatPromptTemplate
from langchain_core.tools import tool

# 创建Flask应用实例
app = Flask(__name__)

# 获取配置信息
load_dotenv()

API_KEY = os.getenv("DASHSCOPE_API_KEY")

# Redis 配置（按你给的配置）
# 键结构：clip:image:{imageId}=图片完整信息(JSON)；
#         clip:user:{userId}:image_ids=该用户所有 imageId 的集合(SET)
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = 6379
REDIS_DB = 0
REDIS_TIMEOUT_SECS = 2

_redis_client = redis.Redis(
    host=REDIS_HOST,
    port=REDIS_PORT,
    db=REDIS_DB,
    socket_timeout=REDIS_TIMEOUT_SECS,
    socket_connect_timeout=REDIS_TIMEOUT_SECS,
    decode_responses=True,
)

CLIP_MODEL_DIR = r"/root/bite/app/AIWear/clip-vit-base-patch16"
_clip_model = None
_clip_processor = None
_clip_lock = threading.Lock()


def get_clip_model_and_processor():
    """
    延迟加载本地 CLIP 模型，避免每次请求重复加载。
    该模型的 image embedding 维度通常为 512。
    """
    global _clip_model, _clip_processor
    if _clip_model is not None and _clip_processor is not None:
        return _clip_model, _clip_processor

    # 简单单例锁，确保并发下只加载一次
    with _clip_lock:
        if _clip_model is None or _clip_processor is None:
            _clip_processor = CLIPProcessor.from_pretrained(CLIP_MODEL_DIR)
            _clip_model = CLIPModel.from_pretrained(CLIP_MODEL_DIR)
            _clip_model.eval()

    return _clip_model, _clip_processor

def process_image(image_data : bytes) -> str:
    """把原始图片 bytes 转成 data:image/xxx;base64,... 的 data URI。"""
    img = Image.open(BytesIO(image_data))
    image_format = (img.format).lower()
    image_base64 = base64.b64encode(image_data).decode("utf-8")
    data_uri = f"data:{image_format};base64,{image_base64}"
    return data_uri

def describe_image(image_data : bytes) -> str:
    """调用 qwen-vl-max 生成一句话描述 + 3~5 个关键词；失败返回空串。"""
    try:
        # 1. 先把图片转化成base64
        data_uri = process_image(image_data)
        # 2. 构建LangChain的请求
        human_content = [
            {"image": data_uri},
            {
                "text": (
                    "用一句话简要地概括这张图片的内容，"
                    "并给出3到5个关键词（使用逗号分隔开），不要过多地解释"
                )
            },
        ]
        # 3. 构建访问大模型的实例
        vl_llm = ChatTongyi(
            model_name="qwen-vl-max",
            temperature=0.0,
            dashscope_api_key=API_KEY
        )
        # 4. 把访问大模型得到的结果进行处理返回
        resp = vl_llm.invoke([HumanMessage(content=human_content)])
        return resp.content[0]['text']
    except Exception as e :
        print(f"生成图片的文字描述信息出现异常:{e}")
        return ""




def validate_image(image_desc : str) -> bool:
    """根据图片描述文本，调用 qwen-plus 判定是否为服装/人像类图片。"""
    try:
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    "你是一个图片审核助手，当前的业务只允许两种图片："
                    "1) 衣服/服装/穿搭相关;"
                    "2) 人物人像(人脸照、半身照、全身照).\n"
                    "需要你来判断当前图片的内容是否是以上两类图片，如果是输出是，如果否输出否。"
                    "请严格只输出是或者否，不要别的内容"
                ),
                (
                    "human", f"图片文字描述: {image_desc}"
                )
            ]
        )

        llm = ChatTongyi(
            model_name="qwen-plus",
            temperature=0.7,
            dashscope_api_key=API_KEY
        )

        resp = llm.invoke(prompt.format_messages())
        text = resp.content
        return text.startswith("是")
    except Exception as e:
        print(f"做图片内容判定的时候出现异常:{e}")
        return False


def clip_image_to_512d(image_data: bytes) -> list:
    """
    把上传图片转成 CLIP 512 维向量（float 列表）。

    归一化后的向量更适合做余弦相似度检索。
    """
    print("========== 开始生成CLIP向量 ==========")

    model, processor = get_clip_model_and_processor()

    print("模型加载成功")

    img = Image.open(BytesIO(image_data)).convert("RGB")

    print("图片读取成功")

    inputs = processor(images=img, return_tensors="pt")

    print("processor成功")

    with torch.no_grad():
        # 当前 transformers 版本下，get_image_features 返回 BaseModelOutputWithPooling
        # 其中向量通常在 pooler_output 字段里。
        out = model.get_image_features(**inputs)
        print("CLIP推理成功")

        if hasattr(out, "pooler_output") and out.pooler_output is not None:
            image_features = out.pooler_output
        elif hasattr(out, "image_embeds") and out.image_embeds is not None:
            image_features = out.image_embeds
        else:
            image_features = out
        image_features = image_features / image_features.norm(dim=-1, keepdim=True)

    vec = image_features[0].detach().cpu().tolist()
    if len(vec) != 512:
        raise ValueError(f"CLIP embedding 维度期望为 512，实际为 {len(vec)}")

    # 降低 Redis 里 JSON 体积，检索时精度通常足够
    vec = [round(float(x), 6) for x in vec]
    print("向量长度：", len(vec))
    return vec


def cosine_similarity_512(a: list, b: list) -> float:
    """
    计算两个 512 维向量的余弦相似度。
    约定：向量已经做过 L2 归一化时，余弦相似度可近似为点积。
    """
    if a is None or b is None:
        return 0.0
    if len(a) != 512 or len(b) != 512:
        return 0.0
    try:
        s = 0.0
        for i in range(512):
            s += float(a[i]) * float(b[i])
        return float(s)
    except Exception:
        return 0.0


def _normalize_text_for_similarity(s: str) -> str:
    if s is None:
        return ""
    s = str(s).strip().lower()
    if not s:
        return ""
    # 把各种空白压缩成单空格；移除大多数标点符号（保留中英文数字与空白）
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^\w\u4e00-\u9fff\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def _char_ngrams(s: str, n: int = 2) -> set:
    if not s:
        return set()
    if len(s) <= n:
        return {s}
    return {s[i : i + n] for i in range(0, len(s) - n + 1)}


def text_similarity(query: str, description: str) -> float:
    """
    文本相似度（不使用向量/模型）：
    - 使用 difflib 的序列相似度（对中英文都能工作）
    - 叠加 2-gram 字符集合的 Jaccard，相对更适合中文短句/关键词
    返回 [0,1]，越大越相似。
    """
    q = _normalize_text_for_similarity(query)
    d = _normalize_text_for_similarity(description)
    if not q or not d:
        return 0.0

    seq = difflib.SequenceMatcher(None, q, d).ratio()
    q2 = _char_ngrams(q, 2)
    d2 = _char_ngrams(d, 2)
    if not q2 or not d2:
        jac = 0.0
    else:
        jac = len(q2 & d2) / max(1, len(q2 | d2))

    # 取更“乐观”的相似度，以满足关键词命中场景
    return float(max(seq, jac))


# 定义审核图片的接口路由
@app.route("/api/validate-image", methods = ["POST"])
def validate_image_api():
    """POST /api/validate-image：上传图片文件，返回 allow(是否放行)。"""
    try:
        file = request.files["file"]
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc)     # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        return jsonify({"code":200, "allow": allow}), 200
    except Exception as e:
        print(f"执行审核图片操作捕获异常:{e}")
        return jsonify({"code":500, "allow": False}), 500


def _get_field_from_request(name: str, default=None):
    """兼容 JSON body 与 form body 的字段读取。"""
    # JSON body（不强依赖 Content-Type，避免前端没设置导致读取失败）
    body = request.get_json(silent=True) or {}
    if name in body:
        return body.get(name, default)

    # form-data / x-www-form-urlencoded
    if name in request.form:
        return request.form.get(name, default)

    # query string
    if name in request.args:
        return request.args.get(name, default)

    return default


def _extract_user_id():
    user_id = _get_field_from_request("userId")
    if user_id is None:
        user_id = _get_field_from_request("user_id")
    if user_id is None:
        raise ValueError("userId 不能为空")

    # 允许前端传字符串数字
    try:
        return int(user_id)
    except Exception:
        return str(user_id)


def _extract_oss_url():
    for key in [
        "ossUrl",
        "oss_url",
        "oss_address",
        "ossAddress",
        "imageOssUrl",
        "image_oss_url",
    ]:
        v = _get_field_from_request(key)
        if v:
            return v

    # 兜底：有些前端把字段叫 url
    v = _get_field_from_request("url")
    return v


@app.route("/api/upload-image", methods=["POST"])
def upload_image_api():
    """
    接收参数:
    - ossUrl / oss_address: 图片 OSS 地址（已可访问的 http(s) URL）
    - userId: 用户 ID

    处理流程:
    1) 下载 OSS 图片 -> bytes
    2) 调用 qwen-vl-max 生成图片描述
    3) 调用本地 CLIP 生成 512 维向量
    4) 把 {userId, description, embedding} 写入 Redis（JSON 格式）
    """
    try:
        user_id = _extract_user_id()
        oss_url = _extract_oss_url()
        if not oss_url:
            return jsonify({"success": False, "error": "ossUrl/oss_address 不能为空"}), 400

        # 下载图片（OSS 通常是可直接 GET 的公开/内网 URL）
        resp = requests.get(oss_url, timeout=20)
        if resp.status_code != 200:
            return (
                jsonify(
                    {
                        "success": False,
                        "error": f"下载图片失败，status={resp.status_code}",
                    }
                ),
                400,
            )

        image_data = resp.content
        if not image_data:
            return jsonify({"success": False, "error": "下载到的图片为空"}), 400

        # 1) qwen-vl-max: 描述
        description = describe_image(image_data)
        if not description:
            return jsonify({"success": False, "error": "生成图片描述失败"}), 500

        # 2) CLIP: 向量（512维）
        embedding = clip_image_to_512d(image_data)

        # 3) 写入 Redis
        image_id = uuid.uuid4().hex
        payload = {
            "userId": user_id,
            "imageId": image_id,
            "ossUrl": oss_url,
            "description": description,
            "embedding512": embedding,
        }

        redis_key = f"clip:image:{image_id}"
        print("======== 开始写 Redis ========")
        print("redis_key =", redis_key)

        result1 = _redis_client.set(redis_key, json.dumps(payload, ensure_ascii=False))
        # 同时维护 user 的索引集合，后续你按 userId 扫描会更快（避免 keys * 全库扫描）
        result2 = _redis_client.sadd(f"clip:user:{user_id}:image_ids", image_id)

        print("set result =", result1)
        print("sadd result =", result2)


        print("======== Redis写入结束 ========")

        return (
            jsonify(
                {
                    "success": True,
                    "imageId": image_id,
                    "description": description,
                    "embeddingDim": 512,
                }
            ),
            200,
        )
    except Exception as e:
        print(f"/api/upload-image 执行失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/search-image", methods=["POST"])
def search_image_api():
    """
    POST /api/search-image

    multipart/form-data:
    - userId: 必填
    - file: 可选（图搜图）
    - query: 可选（文搜图）

    逻辑：
    1) 读取 Redis: clip:user:{userId}:image_ids
    2) 再读取每个 clip:image:{imageId} 的缓存（包含 description, embedding512, ossUrl）
    3) 文搜图：query 与 description 做相似度，>0.5 按相似度倒序
    4) 图搜图：file 抽取 CLIP 向量，与 embedding512 做余弦相似度，>0.7 按相似度倒序
    """
    try:
        user_id = _extract_user_id()
        query = _get_field_from_request("query")
        file_storage = request.files.get("file")

        is_image_search = file_storage is not None and getattr(file_storage, "filename", "") != ""
        is_text_search = query is not None and str(query).strip() != ""

        if not is_image_search and not is_text_search:
            return jsonify({"code": 400, "message": "file 或 query 至少需要一个", "data": []}), 400

        # 1) 读取用户图片ID集合
        user_key = f"clip:user:{user_id}:image_ids"

        print("========== search ==========")
        print("userId =", user_id)
        print("user_key =", user_key)

        image_ids = list(_redis_client.smembers(user_key) or [])

        print("image_ids =", image_ids)
        if not image_ids:
            return jsonify({"code": 200, "message": "查询成功", "data": []}), 200

        # 2) 拉取缓存内容
        items = []

        for image_id in image_ids:
            try:
                raw = _redis_client.get(f"clip:image:{image_id}")

                print("读取:", image_id)
                print("raw =", raw)

                if not raw:
                    continue

                obj = json.loads(raw)
                print("description =", obj.get("description"))

                items.append(obj)

            except Exception as e:
                print("读取Redis异常：", e)
                continue

        if not items:
            return jsonify({"code": 200, "message": "查询成功", "data": []}), 200

        results = []

        if is_image_search:
            image_data = file_storage.read()
            if not image_data:
                return jsonify({"code": 400, "message": "上传图片为空", "data": []}), 400

            query_vec = clip_image_to_512d(image_data)
            for obj in items:
                emb = obj.get("embedding512")
                if not isinstance(emb, list) or len(emb) != 512:
                    continue
                sim = cosine_similarity_512(query_vec, emb)
                if sim >= 0.7:
                    results.append(
                        {
                            "filePath": obj.get("ossUrl") or obj.get("filePath") or "",
                            "similarity": round(float(sim), 4),
                        }
                    )

        else:
            for obj in items:
                desc = obj.get("description")
                if not desc:
                    continue
                sim = text_similarity(str(query), str(desc))
                print("query =", query)
                print("description =", desc)
                print("similarity =", sim)
                print(sim)
                if sim >= 0.1:
                    results.append(
                        {
                            "filePath": obj.get("ossUrl") or obj.get("filePath") or "",
                            "similarity": round(float(sim), 4),
                        }
                    )

        print("items数量 =", len(items))
        print("results =", results)
        results.sort(key=lambda x: x.get("similarity", 0.0), reverse=True)

        return jsonify({"code": 200, "message": "查询成功", "data": results}), 200
    except Exception as e:
        print(f"/api/search-image 执行失败: {e}")
        return jsonify({"code": 500, "message": "查询失败", "data": []}), 500

# 全局的agent智能体
deep_agent = None

llm = ChatTongyi(
    model_name="qwen-plus",
    temperature=0.1,
    dashscope_api_key = API_KEY
)

@tool
def edit_image_tool(image_path: str, instruction: str) -> str:
    """编辑单张图片
    输入本地图片地址路径与编辑指令   调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path, "rb") as f:
            image_data = f.read()
        image_data_uri = process_image(image_data)

        messages = [
            {
                "role": "user",
                "content": [
                    {
                        "image": image_data_uri
                    },
                    {
                        "text": instruction
                    }
                ]
            }
        ]

        # 构建请求大模型的参数
        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages
        }

        # 调用Dashscope编辑模型
        response = MultiModalConversation.call(**params)

        url = response['output']['choices'][0]['message']['content'][0]['image']
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用编辑模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


@tool
def merge_image_tool(image_path1: str, image_path2: str, instruction: str) -> str:
    """合并两张图片
    输入两张本地图片地址路径与合并指令，调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path1, "rb") as f:
            image_data1 = f.read()
        with open(image_path2, "rb") as f:
            image_data2 = f.read()

        image_data_uri1 = process_image(image_data1)
        image_data_uri2 = process_image(image_data2)

        # 多图输入：把两张图都放进同一个 user content 里
        messages = [
            {
                "role": "user",
                "content": [
                    {"image": image_data_uri1},
                    {"image": image_data_uri2},
                    {"text": instruction},
                ],
            }
        ]

        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages,
        }

        response = MultiModalConversation.call(**params)
        url = response["output"]["choices"][0]["message"]["content"][0]["image"]
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用合并模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


# 声明一下工具
skills_tools = [edit_image_tool, merge_image_tool]

# 创建agent
try:
    deep_agent = create_deep_agent(
        model=llm,
        tools=skills_tools,
        skills=["/skills/"],
        system_prompt=(
            "你是一个智能图片处理助手，你可以调用一个工具：\n"
            "- edit_image_tool：编辑单张图片 \n"
            "- merge_image_tool：合并两张图片 \n"
            "最终的输出格式JSON: {\"success\":true, \"url\":\"字符串类型\"} 或者 {\"success\":false, \"error\":\"字符串类型\"} "
        )
    )
    print("Agent 已经启用")
except Exception as e:
    print("Agent 未启用")
    deep_agent = None


# agent执行函数
def invoke_agent(param : str) -> dict:
    """把用户指令交给 deep_agent，取最后一条消息解析成 dict 返回。"""
    try:
        state = deep_agent.invoke(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": param
                    }
                ]
            }
        )

        msg = (state or {}).get("messages") or []
        last = msg[-1] if msg else None
        content = last.content
        obj = json.loads(content)
        return obj
    except Exception as e:
        print(f"执行agent函数出现异常{e}")
        return {"success": False, "error": f"执行agent函数失败{e}"}

# 操作图片的函数
def skill_image() -> str:
    """从请求读取图片(单张或两张)落临时文件，拼提示词后调用 Agent 处理。"""
    if deep_agent is None:
        print("错误: deep_agent 为空！")
        return ""
    instruction = request.form.get("instruction")
    if not instruction:
        print("错误: instruction 为空！")
        return ""

    tmp_dir = tempfile.gettempdir()
    tmp_paths = []

    prompt_lines = [
        "你必须调用一个工具，并且只输出 JSON格式",
        f"instruction: {instruction}"
    ]

    if "file1" in request.files and "file2" in request.files:
        data1 = request.files["file1"].read()
        data2 = request.files["file2"].read()
        p1 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_1.bin")
        p2 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_2.bin")
        with open(p1, "wb") as f:
            f.write(data1)
        with open(p2, "wb") as f:
            f.write(data2)
        tmp_paths.extend([p1, p2])
        prompt_lines.append(f"image_path1: {p1}")
        prompt_lines.append(f"image_path2: {p2}")
    elif "file" in request.files:
        data = request.files["file"].read()
        p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
        with open(p, "wb") as f:
            f.write(data)
        tmp_paths.append(p)
        prompt_lines.append(f"image_path: {p}")
    else:
        print("错误: 请求中没有找到文件（file 或 file1/file2）！")
        return ""

    out = invoke_agent("\n".join(prompt_lines))
    print(f"Agent 最终返回结果: {out}")
    return out.get("url", "")

# 定义操作图片（编辑图片+合并图片）的接口路由
@app.route("/api/skill/image", methods = ["POST"])
def skill_image_api():
    """POST /api/skill/image：Agent 编辑/合并图片，返回结果图 URL。"""
    try:
        out = skill_image()
        return jsonify(
            {
                "success": True,
                "url": out,
            }
        ), 200
    except Exception as e:
        print(f"调用skill处理失败{e}")
        return jsonify(
            {
                "success": False,
                "error": str(e)
            }
        ), 500


# 服务启动函数
if __name__ == "__main__":
    print("AI服务启动成功！")
    app.run(debug=False, host="0.0.0.0", port=5000)
