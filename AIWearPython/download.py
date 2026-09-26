"""
下载 CLIP 模型到本地（一次性脚本）。

从 ModelScope 拉取 openai-mirror/clip-vit-base-patch16，
打印下载目录，供 server.py 的 CLIP_MODEL_DIR 使用。
"""
# 模型下载
from modelscope import snapshot_download
model_dir = snapshot_download('openai-mirror/clip-vit-base-patch16')

# 打印下载目录，部署时把该路径填到 server.py 的 CLIP_MODEL_DIR
print(model_dir)
