import express from "express";
import { createHash, timingSafeEqual } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { z } from "zod";

const PORT = Number(process.env.PORT || 8787);
const RAW_LINJIAN_URL = process.env.LINJIAN_URL || "";
const CLEAN_LINJIAN_URL = RAW_LINJIAN_URL.trim().replace(/\/$/, "");
const LINJIAN_URL = !CLEAN_LINJIAN_URL || /^https?:\/\//i.test(CLEAN_LINJIAN_URL)
  ? CLEAN_LINJIAN_URL
  : /\.onrender\.com(?::\d+)?$/i.test(CLEAN_LINJIAN_URL)
    ? `https://${CLEAN_LINJIAN_URL}`
    : `http://${CLEAN_LINJIAN_URL}`;
const LINJIAN_TOKEN = process.env.LINJIAN_TOKEN || "";
const MCP_ACCESS_KEY = process.env.MCP_ACCESS_KEY || "";
const DEFAULT_DEVICE = process.env.LINJIAN_DEFAULT_DEVICE || "android-phone";

function accessKeyFrom(req) {
  const pathKey = req.params?.accessKey ?? req.params?.[0];
  if (pathKey) return String(pathKey);

  const headerKey = req.get("x-mcp-access-key");
  if (headerKey) return headerKey;

  const authorization = req.get("authorization") || "";
  return authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
}

function accessKeyMatches(candidate) {
  if (!MCP_ACCESS_KEY || !candidate) return false;
  const expected = createHash("sha256").update(MCP_ACCESS_KEY).digest();
  const supplied = createHash("sha256").update(candidate).digest();
  return timingSafeEqual(expected, supplied);
}

function requireMcpAccess(req, res, next) {
  res.set("Cache-Control", "no-store");
  if (!MCP_ACCESS_KEY) {
    return res.status(503).json({ ok: false, error: "MCP access lock is not configured." });
  }
  if (!accessKeyMatches(accessKeyFrom(req))) {
    return res.status(404).json({ ok: false, error: "Not found." });
  }
  next();
}

function requireConfig() {
  if (!LINJIAN_URL) throw new Error("Missing env LINJIAN_URL, for example https://linjian-peek.onrender.com");
  if (!LINJIAN_TOKEN) throw new Error("Missing env LINJIAN_TOKEN");
}

async function linjianFetch(path, options = {}) {
  requireConfig();
  const res = await fetch(`${LINJIAN_URL}${path}`, {
    ...options,
    headers: { "X-Auth-Token": LINJIAN_TOKEN, ...(options.headers || {}) }
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Linjian server HTTP ${res.status}: ${text || res.statusText}`);
  }
  return res;
}

async function postCommand(payload) {
  const res = await linjianFetch("/api/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  return await res.json();
}

async function commandStatus(id) {
  const res = await linjianFetch(`/api/command/status?id=${encodeURIComponent(id)}`);
  return await res.json();
}


function weatherCodeText(code) {
  const map = {0:"晴",1:"大部晴朗",2:"多云",3:"阴",45:"雾",48:"雾凇",51:"小毛毛雨",53:"毛毛雨",55:"强毛毛雨",61:"小雨",63:"中雨",65:"大雨",71:"小雪",73:"中雪",75:"大雪",80:"阵雨",81:"中等阵雨",82:"强阵雨",95:"雷暴",96:"雷暴伴冰雹",99:"强雷暴伴冰雹"};
  return map[Number(code)] || `天气代码 ${code}`;
}

function buildWeatherAdvice(weather, name="当前地区") {
  if (!weather?.ok) return `宝宝，${name}天气没查到，先按体感穿衣。`;
  const now = weather.current || {};
  const daily = weather.daily || {};
  const temp = Math.round(Number(now.temperature_2m ?? now.temperature ?? 0));
  const codeText = weatherCodeText(now.weather_code ?? now.weathercode);
  const rain = Number(daily.precipitation_probability_max?.[0] ?? 0);
  const max = Math.round(Number(daily.temperature_2m_max?.[0] ?? temp));
  const min = Math.round(Number(daily.temperature_2m_min?.[0] ?? temp));
  const parts = [`${name}现在${codeText}，约 ${temp}℃，今天 ${min}~${max}℃。`];
  if (rain >= 50 || codeText.includes("雨") || codeText.includes("雪")) parts.push("出门把伞带上，别淋到。");
  else if (max >= 32) parts.push("今天偏热，水杯带着，记得喝水。");
  else if (min <= 8 || max - min >= 10) parts.push("温差有点明显，外套带着，别硬撑。");
  else parts.push("天气暂时还行，正常出门就好。");
  return `宝宝，${parts.join(" ")}`;
}

async function fetchWeather(city) {
  const q = encodeURIComponent(city || "");
  if (!q) return { ok: false, error: "missing_city" };
  const geoUrl = `https://geocoding-api.open-meteo.com/v1/search?name=${q}&count=1&language=zh&format=json`;
  const geo = await fetch(geoUrl).then(r => r.json());
  const hit = geo?.results?.[0];
  if (!hit) return { ok: false, error: "city_not_found", city };
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${hit.latitude}&longitude=${hit.longitude}&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2`;
  const data = await fetch(url).then(r => r.json());
  return { ok: true, location: { name: hit.name, country: hit.country, admin1: hit.admin1, latitude: hit.latitude, longitude: hit.longitude }, current: data.current, daily: data.daily, source: "open-meteo" };
}

async function waitCommand(id, seconds = 8) {
  const deadline = Date.now() + seconds * 1000;
  let last = null;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 800));
    last = await commandStatus(id).catch(() => last);
    const status = last?.command?.status;
    if (status === "completed" || status === "failed") return last;
  }
  return last;
}

async function latestInfo() {
  const res = await linjianFetch("/api/latest.json");
  return await res.json();
}

async function latestMtime() {
  try { const info = await latestInfo(); return Number(info.mtime || 0); } catch { return 0; }
}

async function fetchLatestImage() {
  const res = await linjianFetch("/api/latest");
  const mimeType = res.headers.get("content-type")?.split(";")[0] || "image/jpeg";
  const ab = await res.arrayBuffer();
  const buf = Buffer.from(ab);
  return { mimeType, data: buf.toString("base64"), bytes: buf.byteLength };
}

function makeServer() {
  const server = new McpServer({ name: "掌心窗", version: "0.4.0-lean" });

  server.tool(
    "peek_screen",
    "向掌心窗手机端请求一张新截图，并等待手机上传后把图片返回。手机端必须已启动、无障碍截图权限已开启。",
    { wait_seconds: z.number().int().min(3).max(60).default(25).describe("等待手机上传新截图的秒数，默认 25。Render 免费实例刚醒时可以调大。") },
    async ({ wait_seconds = 25 }) => {
      const before = await latestMtime();
      await postCommand({ action: "peek", device_id: DEFAULT_DEVICE });
      const deadline = Date.now() + wait_seconds * 1000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        const info = await latestInfo().catch(() => null);
        if (info && Number(info.mtime || 0) > before) {
          const img = await fetchLatestImage();
          return { content: [
            { type: "text", text: `掌心窗已收到新截图：${info.filename || "latest"}，大小约 ${info.size || img.bytes} bytes。` },
            { type: "image", data: img.data, mimeType: img.mimeType }
          ] };
        }
      }
      return { content: [{ type: "text", text: `等待 ${wait_seconds} 秒后还没有收到新截图。请检查：手机 App 是否点了启动、无障碍权限是否开启、服务器地址和 Token 是否一致、Render 是否刚从休眠中醒来。` }], isError: true };
    }
  );

  server.tool("latest_screen", "不敲门，直接读取服务器里最近一次掌心窗截图。", {}, async () => {
    const info = await latestInfo(); const img = await fetchLatestImage();
    return { content: [
      { type: "text", text: `最近截图：${info.filename || "latest"}，时间戳 ${info.mtime || "unknown"}。` },
      { type: "image", data: img.data, mimeType: img.mimeType }
    ] };
  });

  server.tool("linjian_status", "检查掌心窗后端是否在线，以及 MCP 是否配置了 LINJIAN_URL 和 LINJIAN_TOKEN。", {}, async () => {
    requireConfig();
    const health = await fetch(`${LINJIAN_URL}/health`).then((r) => r.json()).catch((e) => ({ ok: false, error: String(e) }));
    const latest = await latestInfo().catch(() => null);
    return { content: [{ type: "text", text: JSON.stringify({ ok: true, linjian_url: LINJIAN_URL, health, has_latest: Boolean(latest), latest }, null, 2) }] };
  });

  server.tool("get_phone_state", "读取手机最近状态。返回 current_package、screen_text、accessibility_ready。", { device_id: z.string().default(DEFAULT_DEVICE) }, async ({ device_id = DEFAULT_DEVICE }) => {
    const res = await linjianFetch(`/api/device/state?device_id=${encodeURIComponent(device_id)}`);
    const data = await res.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  });



  server.tool("get_screen_nodes", "读取当前屏幕无障碍节点：文字、控件类型、可点击状态与 bounds/center 坐标，用于看标题后精准点击。", {
    device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const result = await postCommand({ action: "get_screen_nodes", device_id });
    const id = result?.command?.id;
    const observed = id ? await waitCommand(id, wait_seconds) : null;
    return { content: [{ type: "text", text: JSON.stringify({ queued: result, observed_status: observed?.command || null, note: "result 是节点数组 JSON 字符串，包含 text/left/top/right/bottom/center_x/center_y/clickable。" }, null, 2) }] };
  });

  server.tool("tap_text", "按当前屏幕文字精准点击。会寻找包含/完全匹配 target_text 的无障碍节点，优先点击可点击父节点，否则点击文字中心坐标。", {
    target_text: z.string(), match: z.string().default("contains"), index: z.number().int().min(1).default(1), device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ target_text, match = "contains", index = 1, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const result = await postCommand({ action: "tap_text", device_id, target_text, match, index, payload: { target_text, match, index } });
    const id = result?.command?.id;
    const observed = id ? await waitCommand(id, wait_seconds) : null;
    return { content: [{ type: "text", text: JSON.stringify({ queued: result, observed_status: observed?.command || null }, null, 2) }] };
  });

  server.tool("input_text", "把文字输入到当前已聚焦或第一个可编辑输入框。适合评论草稿；不会自动点击发送。", {
    text: z.string(), append: z.boolean().default(false), device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ text, append = false, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const result = await postCommand({ action: "input_text", device_id, text, append, payload: { text, append } });
    const id = result?.command?.id;
    const observed = id ? await waitCommand(id, wait_seconds) : null;
    return { content: [{ type: "text", text: JSON.stringify({ queued: result, observed_status: observed?.command || null, note: "只输入草稿，不会发送。发送前需要用户明确确认。" }, null, 2) }] };
  });

  server.tool("open_app", "打开指定 App。app 可填 小红书/微信/QQ/抖音/ChatGPT/Gemini/Claude/微博/X/Speedcat，或直接传 package。会等待几秒查看手机是否回传执行结果。", { app: z.string().default(""), package: z.string().default(""), device_id: z.string().default(DEFAULT_DEVICE) }, async ({ app = "", package: pkg = "", device_id = DEFAULT_DEVICE }) => {
    const result = await postCommand({ action: "open_app", app, package: pkg, device_id });
    const id = result?.command?.id;
    if (!id) return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    const observed = await waitCommand(id, 8);
    return { content: [{ type: "text", text: JSON.stringify({ ...result, observed_status: observed?.command || null, note: "若 observed_status 仍是 pending/dispatched，说明手机端尚未回传；可稍后查 command/status 或看调试日志。" }, null, 2) }] };
  });

  server.tool("phone_home", "让手机回到桌面。", { device_id: z.string().default(DEFAULT_DEVICE) }, async ({ device_id = DEFAULT_DEVICE }) => ({ content: [{ type: "text", text: JSON.stringify(await postCommand({ action: "home", device_id }), null, 2) }] }));
  server.tool("phone_back", "让手机执行返回。", { device_id: z.string().default(DEFAULT_DEVICE) }, async ({ device_id = DEFAULT_DEVICE }) => ({ content: [{ type: "text", text: JSON.stringify(await postCommand({ action: "back", device_id }), null, 2) }] }));
  server.tool("phone_recents", "打开手机最近任务。", { device_id: z.string().default(DEFAULT_DEVICE) }, async ({ device_id = DEFAULT_DEVICE }) => ({ content: [{ type: "text", text: JSON.stringify(await postCommand({ action: "recents", device_id }), null, 2) }] }));

  server.tool("set_alarm", "设置系统闹钟。只在用户明确要求时使用。hour 为 0-23，minute 为 0-59。", {
    hour: z.number().int().min(0).max(23), minute: z.number().int().min(0).max(59), message: z.string().default("掌心窗闹钟"), vibrate: z.boolean().default(true), skip_ui: z.boolean().default(true), device_id: z.string().default(DEFAULT_DEVICE)
  }, async ({ hour, minute, message = "掌心窗闹钟", vibrate = true, skip_ui = true, device_id = DEFAULT_DEVICE }) => {
    const result = await postCommand({ action: "set_alarm", device_id, payload: { hour, minute, message, vibrate, skip_ui } });
    return { content: [{ type: "text", text: JSON.stringify({ ...result, note: "部分手机系统可能仍会弹出闹钟 App 确认界面。" }, null, 2) }] };
  });


  const stepSchema = z.object({
    action: z.string().describe("动作：open_app/home/back/recents/tap/swipe/peek/send_notification/set_alarm/wait/get_life_state"),
    label: z.string().default(""),
    app: z.string().default(""),
    package: z.string().default(""),
    x: z.number().default(0), y: z.number().default(0),
    x1: z.number().default(0), y1: z.number().default(0), x2: z.number().default(0), y2: z.number().default(0),
    duration: z.number().int().default(350),
    wait_ms: z.number().int().min(0).max(5000).default(800),
    title: z.string().default("掌心窗提醒"),
    message: z.string().default("宝宝，看一眼这里。"),
    expect_app: z.string().default(""),
    target_text: z.string().default(""),
    text: z.string().default(""),
    match: z.string().default("contains"),
    index: z.number().int().default(1),
    append: z.boolean().default(false)
  }).passthrough();

  server.tool("run_sequence", "一次执行多步手机动作，并让手机端返回每一步成功/失败日志。适合强制抱回、最近任务切换、通知后打开 App。", {
    device_id: z.string().default(DEFAULT_DEVICE),
    steps: z.array(stepSchema).min(1).max(12),
    stop_on_error: z.boolean().default(true),
    wait_seconds: z.number().int().min(3).max(45).default(25)
  }, async ({ device_id = DEFAULT_DEVICE, steps, stop_on_error = true, wait_seconds = 25 }) => {
    const result = await postCommand({ action: "run_sequence", device_id, steps, payload: { steps, stop_on_error }, stop_on_error });
    const id = result?.command?.id;
    const observed = id ? await waitCommand(id, wait_seconds) : null;
    return { content: [{ type: "text", text: JSON.stringify({ queued: result, observed_status: observed?.command || null, note: "手机端会在 result 里写清每一步：index/label/action/ok/detail。" }, null, 2) }] };
  });
  return server;
}

const app = express();
const jsonBody = express.json({ limit: "32mb" });
app.get("/", (_req, res) => res.type("text/plain").send("掌心窗 lean MCP is running. The MCP endpoint is access-key protected."));
app.get("/health", (_req, res) => res.json({ ok: true, service: "linjian-unified-mcp", version: "0.4.0-lean", has_url: Boolean(LINJIAN_URL), has_token: Boolean(LINJIAN_TOKEN), has_access_key: Boolean(MCP_ACCESS_KEY) }));

async function handleMcp(req, res) {
  try { const server = makeServer(); const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined }); res.on("close", () => transport.close()); await server.connect(transport); await transport.handleRequest(req, res, req.body); }
  catch (err) { console.error(err); if (!res.headersSent) res.status(500).json({ jsonrpc: "2.0", error: { code: -32603, message: String(err?.message || err) }, id: null }); }
}

app.post("/mcp", requireMcpAccess, jsonBody, handleMcp);
app.post("/mcp/:accessKey(*)", requireMcpAccess, jsonBody, handleMcp);
app.get("/mcp", requireMcpAccess, (_req, res) => res.status(405).json({ ok: false, error: "Use POST for Streamable HTTP MCP." }));
app.get("/mcp/:accessKey(*)", requireMcpAccess, (_req, res) => res.status(405).json({ ok: false, error: "Use POST for Streamable HTTP MCP." }));

const sseTransports = new Map();
async function handleSse(req, res) {
  const pathKey = req.params?.accessKey ?? req.params?.[0];
  const messagesPath = pathKey ? `/messages/${encodeURIComponent(pathKey)}` : "/messages";
  try { const transport = new SSEServerTransport(messagesPath, res); sseTransports.set(transport.sessionId, transport); res.on("close", () => { sseTransports.delete(transport.sessionId); transport.close(); }); await makeServer().connect(transport); }
  catch (err) { console.error(err); if (!res.headersSent) res.status(500).end(String(err?.message || err)); }
}

async function handleSseMessage(req, res) {
  const sessionId = req.query.sessionId;
  const transport = sseTransports.get(sessionId);
  if (!transport) return res.status(404).send("No SSE transport for sessionId");
  await transport.handlePostMessage(req, res, req.body);
}

app.get("/sse", requireMcpAccess, handleSse);
app.get("/sse/:accessKey(*)", requireMcpAccess, handleSse);
app.post("/messages", requireMcpAccess, jsonBody, handleSseMessage);
app.post("/messages/:accessKey(*)", requireMcpAccess, jsonBody, handleSseMessage);
app.listen(PORT, "0.0.0.0", () => { console.log(`掌心窗 lean MCP listening on 0.0.0.0:${PORT}`); console.log(`LINJIAN_URL=${LINJIAN_URL || "<missing>"}`); });
