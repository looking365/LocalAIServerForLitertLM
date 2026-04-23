# Local AI Server

Android 应用直接在设备上运行 LLM 推理服务器。提供兼容 OpenAI 的 API,允许任何 AI 客户端应用连接并使用完全离线运行的 AI 模型。

## 功能

- **双引擎**: 支持 MediaPipe (`.task`) 和 LiteRT GenAI (`.litertlm`)
- **兼容 OpenAI 的 API**: `/v1/chat/completions` 支持流式传输 (SSE)
- **12+ 模型**: Gemma 2/3/4, Qwen 2.5, DeepSeek R1, SmolLM, TinyLlama
- **聊天 UI**: 集成了实时流式传输的聊天界面
- **多轮对话**: 支持带系统提示的多轮对话
- **线程安全**: 推理队列确保多线程处理的安全性
- **CORS**: 允许基于浏览器的客户端访问 API
- **后台服务**: 服务器以后台服务运行并显示通知

## 安装

1. 在 Android 设备上安装 APK 文件 (ARM64, Android 8+)
2. 打开应用,从 **模型** 标签页下载模型
3. 选择模型并点击 **加载模型**
4. 点击 **启动** 按钮启动服务器

## API 端点

服务器默认运行在 `http://127.0.0.1:8765`

### OpenAI 兼容接口 (主要使用)

#### POST /v1/chat/completions

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "你好!"}
    ],
    "temperature": 0.7,
    "stream": false
  }'
```

**响应:**
```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "model": "Gemma 3 1B (Q8)",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "你好! 我能帮你什么吗?"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 12, "completion_tokens": 15, "total_tokens": 27}
}
```

#### 流式传输 (SSE)

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello"}], "stream": true}'
```

响应是服务器发送的事件:
```
data: {"id":"chatcmpl-abc","choices":[{"delta":{"role":"assistant"},"index":0}]}

data: {"id":"chatcmpl-abc","choices":[{"delta":{"content":"Hello"},"index":0}]}

data: {"id":"chatcmpl-abc","choices":[{"delta":{"content":"!"},"index":0}]}

data: {"id":"chatcmpl-abc","choices":[{"delta":{},"finish_reason":"stop","index":0}]}

data: [DONE]
```

#### GET /v1/models

```bash
curl http://127.0.0.1:8765/v1/models
```

### 媒体端点 (多模态 - 需要 Gemma 4)

这些端点允许**其他应用**通过 HTTP 处理音频/图像/视频。需要先加载 **Gemma 4 E2B** 或 **E4B** (.litertlm)。

#### POST /v1/audio/transcriptions

将音频转换为文本 (兼容 OpenAI Whisper API)。

```bash
curl -X POST http://127.0.0.1:8765/v1/audio/transcriptions \
  -F "file=@audio.wav" \
  -F "language=zh" \
  -F "response_format=json"
```

**响应:**
```json
{"text": "你好,我正在测试..."}
```

**表单字段:**
- `file` (必需): 音频文件 (wav, mp3, m4a, flac...)
- `language` (可选): 语言,例如 `zh`, `en`
- `prompt` (可选): 上下文指导
- `response_format` (可选): `json` | `text` | `verbose_json`

#### POST /v1/audio/translations

将音频转换为英文文本 (从任何语言翻译)。

```bash
curl -X POST http://127.0.0.1:8765/v1/audio/translations \
  -F "file=@chinese.wav"
```

#### POST /v1/images/describe

描述图像。

```bash
curl -X POST http://127.0.0.1:8765/v1/images/describe \
  -F "file=@photo.jpg" \
  -F "prompt=图片里有几个人?"
```

**响应:**
```json
{
  "description": "图片中有 3 个人正在...",
  "model": "Gemma 4 E2B",
  "processing_time_ms": 4532
}
```

**表单字段:**
- `file` 或 `image` (必需): 图像文件 (jpg, png, webp...)
- `prompt` (可选): 自定义提示词 (默认: 详细描述)

#### POST /v1/video/analyze

通过提取代表性帧来分析视频。

```bash
curl -X POST http://127.0.0.1:8765/v1/video/analyze \
  -F "file=@video.mp4" \
  -F "frame_count=8" \
  -F "prompt=总结视频中的事件"
```

**响应:**
```json
{
  "description": "视频拍摄了场景...",
  "frames_analyzed": 8,
  "duration_ms": 45000,
  "model": "Gemma 4 E2B",
  "processing_time_ms": 12345
}
```

**表单字段:**
- `file` 或 `video` (必需): 视频文件 (mp4, 3gp, mov...)
- `frame_count` (可选): 提取的帧数, 1-16 (默认 4)
- `prompt` (可选): 自定义提示词

应用将从视频中均匀提取 `frame_count` 个帧,调整为最大 1024px,然后发送给 Gemma 4。

### 从其他应用调用的示例

**Android (Kotlin) - 上传音频:**
```kotlin
val client = OkHttpClient()
val audioFile = File(audioPath)
val body = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", audioFile.name,
        audioFile.asRequestBody("audio/wav".toMediaType()))
    .addFormDataPart("language", "zh")
    .build()
val request = Request.Builder()
    .url("http://127.0.0.1:8765/v1/audio/transcriptions")
    .post(body)
    .build()
val response = client.newCall(request).execute()
val json = JSONObject(response.body!!.string())
val text = json.getString("text")
```

**Python - 上传图像:**
```python
import requests
with open('photo.jpg', 'rb') as f:
    r = requests.post(
        'http://127.0.0.1:8765/v1/images/describe',
        files={'file': f},
        data={'prompt': '这张图片里是什么?'}
    )
print(r.json()['description'])
```

**JavaScript (fetch) - 上传视频:**
```javascript
const fd = new FormData();
fd.append('file', videoBlob, 'video.mp4');
fd.append('frame_count', '6');
const r = await fetch('http://127.0.0.1:8765/v1/video/analyze', {
    method: 'POST', body: fd
});
const data = await r.json();
console.log(data.description);
```

### 传统 API

#### GET /api/health

```bash
curl http://127.0.0.1:8765/api/health
```

```json
{
  "status": "ok",
  "model": "Gemma 3 1B (Q8)",
  "port": 8765,
  "version": "2.0",
  "queue_depth": 0,
  "tokens_per_second": 12.5
}
```

#### POST /api/chat

```bash
curl http://127.0.0.1:8765/api/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello!", "temperature": 0.7}'
```

#### GET /api/models

```bash
curl http://127.0.0.1:8765/api/models
```

## 与其他应用集成

### Open WebUI

1. 设置 > 连接 > OpenAI API
2. 基础 URL: `http://127.0.0.1:8765/v1`
3. API Key: 任意 (服务器不需要身份验证)

### Continue.dev (VS Code)

```json
{
  "models": [{
    "title": "Local AI",
    "provider": "openai",
    "model": "local-model",
    "apiBase": "http://127.0.0.1:8765/v1",
    "apiKey": "none"
  }]
}
```

### LM Studio / 任何 OpenAI 客户端

基础 URL: `http://127.0.0.1:8765/v1`
API Key: 任意值

### Python (openai SDK)

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8765/v1",
    api_key="not-needed"
)

response = client.chat.completions.create(
    model="local-model",
    messages=[
        {"role": "system", "content": "You are helpful."},
        {"role": "user", "content": "Hello!"}
    ],
    stream=True
)

for chunk in response:
    if chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="")
```

### JavaScript / TypeScript

```javascript
const response = await fetch('http://127.0.0.1:8765/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        messages: [{ role: 'user', content: 'Hello!' }],
        stream: false
    })
});
const data = await response.json();
console.log(data.choices[0].message.content);
```

### Kotlin / Android 应用

```kotlin
val url = URL("http://127.0.0.1:8765/v1/chat/completions")
val conn = url.openConnection() as HttpURLConnection
conn.requestMethod = "POST"
conn.setRequestProperty("Content-Type", "application/json")
conn.doOutput = true

val body = """{"messages":[{"role":"user","content":"Hello!"}]}"""
conn.outputStream.write(body.toByteArray())

val response = conn.inputStream.bufferedReader().readText()
```

## 可用模型

| 模型 | 大小 | RAM | 备注 |
|-------|------|-----|------|
| Gemma 4 E2B | 2.4 GB | ~3 GB | 最新的 Gemma 4, 2B 参数 |
| Gemma 4 E4B | 3.5 GB | ~4.5 GB | 最强的 Gemma 4, 4B 参数 |
| Gemma 3 270M | 290 MB | ~0.5 GB | 超轻量, 高速 |
| Gemma 3 1B (Q4) | 529 MB | ~1 GB | 轻量, 质量/大小比例良好 |
| Gemma 3 1B (Q8) | 1 GB | ~1.5 GB | 平衡良好, 质量高 |
| Qwen 2.5 0.5B | 521 MB | ~0.8 GB | 多语言, Apache 2.0 |
| Qwen 2.5 1.5B | 1.5 GB | ~2 GB | 出色的多语言支持 |
| DeepSeek R1 1.5B | 1.8 GB | ~2 GB | 良好的逻辑推理 |
| SmolLM 135M | 159 MB | ~0.3 GB | 超小, 快速加载 |
| TinyLlama 1.1B | 1.1 GB | ~1.5 GB | 轻量, 聊天良好 |
| Gemma 2 2B (Q8) | 2.6 GB | ~3 GB | 稳定, 已验证 |

## 并发与性能

- **线程安全推理**: 信号量确保同一时间只运行 1 个推理
- **请求队列**: 请求按 FIFO 排列, 超时 60 秒
- **端口回退**: 自动尝试端口 8765 → 8766 → 8767 → 8780
- **流式传输**: SSE 允许逐个接收 token 响应,而无需等待全部
- **性能跟踪**: 在 UI 上显示令牌/秒和处理时间

## 系统要求

- Android 8.0+ (API 26)
- ARM64 (arm64-v8a)
- 最大 RAM: 根据模型 (0.3 GB - 4.5 GB)
- 空闲内存: 根据下载的模型

## 项目结构

```
app/src/main/java/com/localai/server/
├── LocalAIApp.kt          # 应用单例
├── MainActivity.kt        # 入口点
├── engine/
│   └── LlmEngine.kt      # 双引擎推理、流式传输、并发
├── service/
│   └── AiServerService.kt # HTTP 服务器、OpenAI API、SSE
└── ui/
    └── ServerScreen.kt    # Compose UI、聊天、设置
```

## 许可证

MIT
