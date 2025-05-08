package com.bebopze.tdx.quant.common.constant;

import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;


/**
 * A股 - 交易所
 *
 * @author: bebopze
 * @date: 2025/5/9
 */
@AllArgsConstructor
public enum StockMarketEnum {


    //   深圳证券交易所（深交所）

    //   |   前缀    | 含义       |      说明                  |   示例    |
    //   | -------  | -----     | ---------------           | ----------|
    //   | 000/001  | 主板A股    | 深市最早期A股               |  000001   |
    //   | 002/003  | 中小企业板  | 中小板，于2004年启用        |  002001   |
    //   | 300/301  | 创业板     | 聚焦高成长性中小企业         |  300001   |
    //   | 200      | B股       | 深市B股，人民币特定投资者参与  |    —      |
    //
    // https://blog.csdn.net/abclhq2005/article/details/78710900   "沪深a股股票代码 - CSDN博客"
    // https://zhuanlan.zhihu.com/p/2238853771   "沪市主板代码以几开头？各板块开头代码是多少"


    // "000", "001", "002", "003", "004",
    // "300", "301"
    SZ("深交所", 0, "SA", Lists.newArrayList("00", "30")),


    //   上海证券交易所（上交所）

    //  | 前缀   | 含义     | 说明             | 示例         |
    //  | ---   | ----    | ---------       | ------------|
    //  | 600   | 主板A股  | 最早上市的大盘股   | 600000      |
    //  | 601   | 主板A股  | 后续发新股        | 601111     |
    //  | 603   | 主板A股  | 后期续发，可再融资 | 603308     |
    //  | 605   | 主板A股  | 新增号段         | —          |
    //  | 688   | 科创板   | 聚焦科技创新企业  | 688001     |
    //  | 900   | B股     | 面向海外投资者   | 900901     |
    //
    //
    // https://xueqiu.com/5673534898/216241741   "基础常识：股票代码含义大全和涨跌幅限制 - 雪球"
    // https://blog.csdn.net/qq_33269520/article/details/80881568   "000、002、200、300、400等开头的股票代表什么？ 原创 - CSDN博客"
    // https://finance.sina.cn/2024-06-29/detail-incaktwf3572024.d.html?cid=76524&node_id=76524&vt=4   "如何理解科创板股票的代码规则 - 新浪财经"

    //  "600", "601", "603", "605"
    //  "688"
    SH("上交所", 1, "HA", Lists.newArrayList("60", "688")),


    //   | 前缀  |   含义      |      说明             |   示例      |
    //   | ---  | ---------  | -------------------  | ----------- |
    //   |  4   | 北交所A股   |   服务创新型中小微企业   |   430001    |
    //
    //   https://xueqiu.com/5673534898/216241741   "基础常识：股票代码含义大全和涨跌幅限制 - 雪球"

    // 43
    // 83   87
    // 92
    BJ("北交所", 2, "B", Lists.newArrayList("4", "8", "9"));


    /**
     * A股 交易所（深交所、上交所、北交所）
     */
    @Getter
    private String marketDesc;

    /**
     * 通达信 - 交易所 类型
     */
    @Getter
    private Integer tdxMarketType;

    /**
     * 东方财富 - 交易所 类型
     */
    @Getter
    private String eastMoneyMarket;

    /**
     * A股 前缀（前2位）
     */
    @Getter
    private List<String> codePrefixList;


    /**
     * stockCode  ->  东财 market
     *
     * @param stockCode
     * @return
     */
    public static String getEastMoneyMarketByStockCode(String stockCode) {

        // 前2位
        String codePrefix = stockCode.trim().substring(0, 1);

        for (StockMarketEnum value : StockMarketEnum.values()) {

            if (value.codePrefixList.contains(codePrefix)) {
                return value.eastMoneyMarket;
            }
        }
        return null;
    }

}