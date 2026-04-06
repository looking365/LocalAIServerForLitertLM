# Local AI Server

Android app chạy LLM inference server trực tiếp trên thiết bị. Cung cấp API tương thích OpenAI, cho phép mọi ứng dụng AI client kết nối và sử dụng model AI chạy hoàn toàn offline.

## Tính năng

- **Dual-engine**: Hỗ trợ MediaPipe (`.task`) và LiteRT GenAI (`.litertlm`)
- **OpenAI-compatible API**: `/v1/chat/completions` với streaming (SSE)
- **12+ models**: Gemma 2/3/4, Qwen 2.5, DeepSeek R1, SmolLM, TinyLlama
- **Chat UI**: Giao diện chat tích hợp với streaming real-time
- **Multi-turn**: Hỗ trợ hội thoại nhiều lượt với system prompt
- **Thread-safe**: Hàng đợi inference đảm bảo xử lý đa luồng an toàn
- **CORS**: Cho phép browser-based clients truy cập API
- **Background service**: Server chạy nền với notification

## Cài đặt

1. Cài file APK trên thiết bị Android (ARM64, Android 8+)
2. Mở app, tải model từ tab **Models**
3. Chọn model và nhấn **Load Model**
4. Nhấn **Start** để khởi động server

## API Endpoints

Server mặc định chạy tại `http://127.0.0.1:8765`

### OpenAI-compatible (khuyên dùng)

#### POST /v1/chat/completions

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "Xin chao!"}
    ],
    "temperature": 0.7,
    "stream": false
  }'
```

**Response:**
```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "model": "Gemma 3 1B (Q8)",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "Xin chao! Toi co the giup gi cho ban?"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 12, "completion_tokens": 15, "total_tokens": 27}
}
```

#### Streaming (SSE)

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello"}], "stream": true}'
```

Response là Server-Sent Events:
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

### Media Endpoints (Multimodal - yêu cầu Gemma 4)

Các endpoint này cho phép **app khác** xử lý audio/image/video thông qua HTTP. Cần load **Gemma 4 E2B** hoặc **E4B** (.litertlm) trước.

#### POST /v1/audio/transcriptions

Chuyển audio thành text (tương thích OpenAI Whisper API).

```bash
curl -X POST http://127.0.0.1:8765/v1/audio/transcriptions \
  -F "file=@audio.wav" \
  -F "language=vi" \
  -F "response_format=json"
```

**Response:**
```json
{"text": "Xin chào, tôi đang thử nghiệm..."}
```

**Form fields:**
- `file` (required): audio file (wav, mp3, m4a, flac...)
- `language` (optional): ngôn ngữ, ví dụ `vi`, `en`
- `prompt` (optional): context guidance
- `response_format` (optional): `json` | `text` | `verbose_json`

#### POST /v1/audio/translations

Chuyển audio thành text tiếng Anh (dịch từ bất kỳ ngôn ngữ nào).

```bash
curl -X POST http://127.0.0.1:8765/v1/audio/translations \
  -F "file=@vietnamese.wav"
```

#### POST /v1/images/describe

Mô tả ảnh.

```bash
curl -X POST http://127.0.0.1:8765/v1/images/describe \
  -F "file=@photo.jpg" \
  -F "prompt=Có bao nhiêu người trong ảnh?"
```

**Response:**
```json
{
  "description": "Trong ảnh có 3 người đang...",
  "model": "Gemma 4 E2B",
  "processing_time_ms": 4532
}
```

**Form fields:**
- `file` hoặc `image` (required): image file (jpg, png, webp...)
- `prompt` (optional): custom prompt (mặc định: mô tả chi tiết)

#### POST /v1/video/analyze

Phân tích video bằng cách trích xuất các khung hình đại diện.

```bash
curl -X POST http://127.0.0.1:8765/v1/video/analyze \
  -F "file=@video.mp4" \
  -F "frame_count=8" \
  -F "prompt=Tóm tắt sự việc trong video"
```

**Response:**
```json
{
  "description": "Video quay cảnh...",
  "frames_analyzed": 8,
  "duration_ms": 45000,
  "model": "Gemma 4 E2B",
  "processing_time_ms": 12345
}
```

**Form fields:**
- `file` hoặc `video` (required): video file (mp4, 3gp, mov...)
- `frame_count` (optional): số khung hình trích xuất, 1-16 (mặc định 4)
- `prompt` (optional): custom prompt

App sẽ trích xuất `frame_count` khung hình đều nhau trong video, resize về tối đa 1024px, rồi gửi cho Gemma 4.

### Ví dụ gọi từ app khác

**Android (Kotlin) - upload audio:**
```kotlin
val client = OkHttpClient()
val audioFile = File(audioPath)
val body = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", audioFile.name,
        audioFile.asRequestBody("audio/wav".toMediaType()))
    .addFormDataPart("language", "vi")
    .build()
val request = Request.Builder()
    .url("http://127.0.0.1:8765/v1/audio/transcriptions")
    .post(body)
    .build()
val response = client.newCall(request).execute()
val json = JSONObject(response.body!!.string())
val text = json.getString("text")
```

**Python - upload image:**
```python
import requests
with open('photo.jpg', 'rb') as f:
    r = requests.post(
        'http://127.0.0.1:8765/v1/images/describe',
        files={'file': f},
        data={'prompt': 'What is in this image?'}
    )
print(r.json()['description'])
```

**JavaScript (fetch) - upload video:**
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

### Legacy API

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

## Tích hợp với các ứng dụng khác

### Open WebUI

1. Settings > Connections > OpenAI API
2. Base URL: `http://127.0.0.1:8765/v1`
3. API Key: bất kỳ (server không yêu cầu auth)

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

### LM Studio / Any OpenAI Client

Base URL: `http://127.0.0.1:8765/v1`
API Key: bất kỳ giá trị

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

### Kotlin / Android App

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

## Models có sẵn

| Model | Size | RAM | Ghi chú |
|-------|------|-----|---------|
| Gemma 4 E2B | 2.4 GB | ~3 GB | Gemma 4 mới nhất, 2B params |
| Gemma 4 E4B | 3.5 GB | ~4.5 GB | Gemma 4 mạnh nhất, 4B params |
| Gemma 3 270M | 290 MB | ~0.5 GB | Siêu nhẹ, tốc độ cao |
| Gemma 3 1B (Q4) | 529 MB | ~1 GB | Nhẹ, tỷ lệ chất lượng/kích thước tốt |
| Gemma 3 1B (Q8) | 1 GB | ~1.5 GB | Cân bằng tốt, chất lượng cao |
| Qwen 2.5 0.5B | 521 MB | ~0.8 GB | Đa ngôn ngữ, Apache 2.0 |
| Qwen 2.5 1.5B | 1.5 GB | ~2 GB | Đa ngôn ngữ xuất sắc |
| DeepSeek R1 1.5B | 1.8 GB | ~2 GB | Lý luận logic tốt |
| SmolLM 135M | 159 MB | ~0.3 GB | Siêu nhỏ, tải nhanh |
| TinyLlama 1.1B | 1.1 GB | ~1.5 GB | Nhẹ, chat tốt |
| Gemma 2 2B (Q8) | 2.6 GB | ~3 GB | Ổn định, đã kiểm chứng |

## Concurrency & Performance

- **Thread-safe inference**: Semaphore đảm bảo chỉ 1 inference chạy tại một thời điểm
- **Request queue**: Các request xếp hàng FIFO, timeout 60s
- **Port fallback**: Tự động thử port 8765 → 8766 → 8767 → 8780
- **Streaming**: SSE cho phép nhận response từng token thay vì đợi toàn bộ
- **Performance tracking**: Tokens/sec và processing time hiển thị trên UI

## Yêu cầu hệ thống

- Android 8.0+ (API 26)
- ARM64 (arm64-v8a)
- RAM tối thiểu: tùy model (0.3 GB - 4.5 GB)
- Bộ nhớ trống: tùy model download

## Cấu trúc dự án

```
app/src/main/java/com/localai/server/
├── LocalAIApp.kt          # Application singleton
├── MainActivity.kt        # Entry point
├── engine/
│   └── LlmEngine.kt      # Dual-engine inference, streaming, concurrency
├── service/
│   └── AiServerService.kt # HTTP server, OpenAI API, SSE
└── ui/
    └── ServerScreen.kt    # Compose UI, chat, settings
```

## License

MIT
