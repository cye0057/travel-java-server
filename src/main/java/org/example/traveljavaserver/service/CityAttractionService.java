package org.example.traveljavaserver.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地城市数据源：Function Calling 工具函数的执行方。
 * 先用内置数据把“模型要工具→本地执行→结果回传→模型再答”的循环跑通；
 * 未来接真实旅游 API / MySQL 查询时只需替换本类实现，对模型的工具协议不变。
 */
@Service
public class CityAttractionService {

    /** 推荐日均预算（元）+ 主要景点 */
    private static final Map<String, CityData> CITIES = new LinkedHashMap<>();

    static {
        CITIES.put("北京", new CityData("北京", 800, "故宫、八达岭长城、颐和园、天坛、798艺术区、南锣鼓巷"));
        CITIES.put("上海", new CityData("上海", 900, "外滩、东方明珠、豫园、田子坊、迪士尼乐园"));
        CITIES.put("杭州", new CityData("杭州", 650, "西湖、灵隐寺、西溪湿地、宋城、河坊街"));
        CITIES.put("成都", new CityData("成都", 600, "大熊猫繁育研究基地、锦里、杜甫草堂、望江楼、春熙路"));
        CITIES.put("西安", new CityData("西安", 550, "秦始皇兵马俑、西安城墙、华清宫、回民街、大唐不夜城"));
    }

    private record CityData(String city, int dailyAverageCost, String mainAttractions) {}

    /** 工具的入口：入参是模型给的字符串（它可能给错），所以返回值也要能表达“查不到” */
    public String getCityInfo(String city) {
        if (city == null || city.isBlank()) {
            return "参数错误：city 不能为空";
        }
        //容错：模型有时会给“北京市”这种带后缀的写法
        String normalized = city.trim().replaceAll("市$", "");
        CityData data = CITIES.get(normalized);
        if (data == null) {
            return "暂无城市[" + city + "]的数据，当前支持：" + String.join("、", CITIES.keySet());
        }
        return "城市：%s；推荐日均预算：%d元；主要景点：%s"
                .formatted(data.city(), data.dailyAverageCost(), data.mainAttractions());
    }
}
