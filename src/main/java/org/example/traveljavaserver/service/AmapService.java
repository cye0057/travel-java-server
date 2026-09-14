package org.example.traveljavaserver.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 高德开放平台工具执行方：天气 + POI。失败一律转成文字返回，交给模型下一轮处理。
 *
 * 思考题的答案——为什么不能照抄 LLMutils 的 readTimeout 120 秒？
 * 因为两类下游的"慢"性质完全不同：LLM 推理慢是真在算结果，值得等；
 * 高德是毫秒级接口，10 秒不返基本等于网络故障或 key 被封。
 * 若共用 120s，一次高德故障会把整条 SSE 链路拖住两分钟，用户端表现为"AI 卡死"；
 * 超时短 → 快速失败 → 失败变文字回传 → 模型下一轮立刻能换话术安抚用户。
 * 超时参数本质是"我愿意为这个下游花多少等待预算"，必须按下游特性逐个定。
 */
@Slf4j
@Service
public class AmapService {

    private static final String BASE_URL = "https://restapi.amap.com/v3";

    /** 高德 Web服务 key。构造器注入 + ${amap.key:} 给空串兜底：key 没填只影响这两个工具，不能拖垮整个应用启动 */
    private final String key;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapService(@Value("${amap.key:}") String key) {
        this.key = key == null ? "" : key.trim();
    }

    /**
     * 工具一：city(中文城市名) → 未来几天天气。内部是两次 HTTP 编排。
     *
     * 为什么要编排两次：高德天气接口只认 adcode（北京=110000 这种行政区划码），
     * 而模型只会传"北京"这类人类词，所以先用 district 接口把城市名翻译成 adcode。
     * 复杂度全部藏在工具内部——模型只看到一个"查天气"的入口，这正是 Function Calling 的意义。
     */
    public String getWeather(String city) {
        if (city == null || city.isBlank()) {
            return "参数错误：city 不能为空";
        }
        String unavailable = checkKeyConfigured();
        if (unavailable != null) {
            return unavailable;
        }
        //容错同 CityAttractionService：模型偶尔会给"北京市"这种带后缀写法
        String normalized = city.trim().replaceAll("市$", "");
        try {
            String adcode = resolveAdcode(normalized);
            if (adcode == null) {
                return "未找到城市[" + city + "]，请和用户确认城市名后重试";
            }
            JsonNode root = get("/weather/weatherInfo", Map.of("city", adcode, "extensions", "all"));
            String apiError = checkStatus(root, "天气查询");
            if (apiError != null) {
                return apiError;
            }
            JsonNode forecast = root.path("forecasts").path(0);
            StringBuilder sb = new StringBuilder();
            sb.append(forecast.path("city").asText("")).append("天气预报（发布时间 ")
                    .append(forecast.path("reporttime").asText("")).append("）：");
            JsonNode casts = forecast.path("casts");
            int days = Math.min(4, casts.size());
            for (int i = 0; i < days; i++) {
                JsonNode cast = casts.get(i);
                sb.append(cast.path("date").asText("")).append("（").append(weekName(cast.path("week").asText(""))).append("）")
                        .append(cast.path("dayweather").asText("")).append("/").append(cast.path("nightweather").asText(""))
                        .append(" ").append(cast.path("daytemp").asText("")).append("/").append(cast.path("nighttemp").asText("")).append("℃；");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("天气查询异常: city={}", city, e);
            return "天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 工具二：搜城市里的 POI（景点/美食等），返回前5条的名称/地址/评分。
     * 结果压成紧凑短文本：这段文字会占下一轮请求的 token，且随会话历史一直背着走。
     */
    public String searchPoi(String city, String keyword) {
        if (city == null || city.isBlank() || keyword == null || keyword.isBlank()) {
            return "参数错误：city 和 keyword 都不能为空";
        }
        String unavailable = checkKeyConfigured();
        if (unavailable != null) {
            return unavailable;
        }
        try {
            JsonNode root = get("/place/text", Map.of(
                    "keywords", keyword.trim(),
                    "city", city.trim().replaceAll("市$", ""),
                    "offset", "5",
                    "page", "1",
                    "extensions", "all"));
            String apiError = checkStatus(root, "POI搜索");
            if (apiError != null) {
                return apiError;
            }
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.size() == 0) {
                return "[" + city + "]没有找到与[" + keyword + "]相关的地点";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("在").append(city).append("搜索[").append(keyword).append("]的地点（按相关度排序）：");
            int n = Math.min(5, pois.size());
            for (int i = 0; i < n; i++) {
                JsonNode poi = pois.get(i);
                //高德对无值字段返回空数组[]（不是缺字段），容器节点 asText() 给空串而非默认值——所以判 blank 再兜底
                String rating = poi.path("biz_ext").path("rating").asText("");
                String address = poi.path("address").asText("");
                sb.append(i + 1).append(".").append(poi.path("name").asText(""));
                sb.append(rating.isEmpty() ? "（暂无评分）" : "（评分" + rating + "）");
                sb.append("地址：").append(address.isBlank() ? "未提供" : address).append("；");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("POI搜索异常: city={}, keyword={}", city, keyword, e);
            return "POI搜索失败：" + e.getMessage();
        }
    }

    /** 城市名 → adcode；查不到（接口失败/列表空）统一返回 null，由调用方降级成给模型看的文字 */
    private String resolveAdcode(String city) throws IOException {
        JsonNode root = get("/config/district", Map.of("keywords", city, "subdistrict", "0"));
        if (checkStatus(root, "行政区划查询") != null) {
            return null;
        }
        String adcode = root.path("districts").path(0).path("adcode").asText("");
        return adcode.isEmpty() ? null : adcode;
    }

    /** 统一的 GET 出口：参数交给 HttpUrl.Builder 加（它自动做 URL 编码，中文城市名/关键字不踩转义坑） */
    private JsonNode get(String path, Map<String, String> params) throws IOException {
        HttpUrl.Builder builder = HttpUrl.parse(BASE_URL + path).newBuilder()
                .addQueryParameter("key", key);
        params.forEach(builder::addQueryParameter);
        Request request = new Request.Builder().url(builder.build()).get().build();
        try (Response response = client.newCall(request).execute()) {
            //非 2xx 也带正文抛出：裸 status code 排障时什么都说明不了（同 LLMutils 的教训）
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("高德HTTP " + response.code() + "，响应: " + body);
            }
            return objectMapper.readTree(body);
        }
    }

    /** 高德约定：status="1" 才算成功，失败时 info 带原因（key 无效、配额超限都在这看出来）；成功返回 null */
    private String checkStatus(JsonNode root, String action) {
        if (!"1".equals(root.path("status").asText(""))) {
            log.warn("高德接口失败: action={}, info={}, infocode={}", action,
                    root.path("info").asText(""), root.path("infocode").asText(""));
            return action + "失败：" + root.path("info").asText("未知错误")
                    + "(" + root.path("infocode").asText("") + ")";
        }
        return null;
    }

    /** key 未配置时不让请求白发：直接给模型一句能转述的话 */
    private String checkKeyConfigured() {
        if (key.isEmpty()) {
            return "天气/POI查询暂不可用：服务器未配置高德key，请基于通用知识回答，并告知用户未获取到实时数据";
        }
        return null;
    }

    /** 高德 week 字段是 1-7 的数字串，转成人话，省得模型自己猜 */
    private String weekName(String week) {
        return switch (week) {
            case "1" -> "周一";
            case "2" -> "周二";
            case "3" -> "周三";
            case "4" -> "周四";
            case "5" -> "周五";
            case "6" -> "周六";
            case "7" -> "周日";
            default -> week;
        };
    }
}
