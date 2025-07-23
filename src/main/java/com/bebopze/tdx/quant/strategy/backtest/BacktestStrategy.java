package com.bebopze.tdx.quant.strategy.backtest;

import com.alibaba.fastjson2.JSON;
import com.bebopze.tdx.quant.common.cache.BacktestCache;
import com.bebopze.tdx.quant.common.config.BizException;
import com.bebopze.tdx.quant.common.constant.BtTradeTypeEnum;
import com.bebopze.tdx.quant.common.util.DateTimeUtil;
import com.bebopze.tdx.quant.common.util.NumUtil;
import com.bebopze.tdx.quant.dal.entity.*;
import com.bebopze.tdx.quant.dal.service.*;
import com.bebopze.tdx.quant.parser.check.TdxFunCheck;
import com.bebopze.tdx.quant.service.IndexService;
import com.bebopze.tdx.quant.service.InitDataService;
import com.bebopze.tdx.quant.strategy.buy.BuyStrategyFactory;
import com.bebopze.tdx.quant.strategy.sell.SellStrategyFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;


/**
 * BS策略 - 回测                    // BS策略 本质       =>       模式成功  🟰 大盘(70) ➕ 主线(25) ➕ 买点(5)
 *
 * @author: bebopze
 * @date: 2025/5/27
 */
@Data
@Slf4j
@Component
public class BacktestStrategy {


    BacktestCache data = new BacktestCache();

    // 统计
    static Stat x = new Stat();
    // private static ThreadLocal<Stat> x = new ThreadLocal<>();


    // -----------------------------------------------------------------------------------------------------------------


    /**
     * bt_trade_record   -   Cache
     */
    private Set<Long> tradeRecord___idSet__cache = Sets.newHashSet();
    private List<BtTradeRecordDO> tradeRecordList__cache = Lists.newArrayList();


    // -----------------------------------------------------------------------------------------------------------------


    @Autowired
    private IBaseStockService baseStockService;

    @Autowired
    private IBaseBlockService baseBlockService;

    @Autowired
    private IBaseBlockRelaStockService baseBlockRelaStockService;

    @Autowired
    private IBaseBlockNewRelaStockService baseBlockNewRelaStockService;


    @Autowired
    private IBtTaskService btTaskService;

    @Autowired
    private IBtTradeRecordService btTradeRecordService;

    @Autowired
    private IBtPositionRecordService btPositionRecordService;

    @Autowired
    private IBtDailyReturnService btDailyReturnService;


    @Autowired
    private InitDataService initDataService;


    @Autowired
    private BuyStrategyFactory buyStrategyFactory;

    @Autowired
    private SellStrategyFactory sellStrategyFactory;


    @Autowired
    private IndexService indexService;


    // -----------------------------------------------------------------------------------------------------------------


    // @Transactional(rollbackFor = Exception.class)
    public synchronized Long backtest(LocalDate startDate, LocalDate endDate) {
        log.info("backtest start     >>>     startDate : {} , endDate : {}", startDate, endDate);


        // -------------------------------------------------------------------------------------------------------------
        //                              回测-task   pre   ==>   板块、个股   行情数据 初始化
        // -------------------------------------------------------------------------------------------------------------


        // 数据初始化   ->   加载 全量行情数据
        initData(startDate, endDate);


        // -------------------------------------------------------------------------------------------------------------
        //                                            回测-task   创建
        // -------------------------------------------------------------------------------------------------------------


        BtTaskDO taskDO = createBacktestTask(startDate, endDate);


        // -------------------------------------------------------------------------------------------------------------
        //                                            回测-task   按日 循环执行
        // -------------------------------------------------------------------------------------------------------------


        LocalDate tradeDate = taskDO.getStartDate().minusDays(1);
        endDate = DateTimeUtil.min(taskDO.getEndDate(), data.dateList.get(data.dateList.size() - 1));


        // 总资金
        x.prevCapital = taskDO.getInitialCapital().doubleValue();
        // 可用金额
        x.prevAvlCapital = taskDO.getInitialCapital().doubleValue();


        // ----------------


        // 每日 收益率
        List<BigDecimal> dailyReturnList = Lists.newArrayList();


        while (tradeDate.isBefore(endDate)) {

            tradeDate = tradeDateIncr(tradeDate);


            try {
                // 每日 - 回测（B/S）
                execBacktestDaily(tradeDate, dailyReturnList, taskDO);
            } catch (Exception e) {
                log.error("execBacktestDaily     >>>     taskId : {} , tradeDate : {} , exMsg : {}", taskDO.getId(), tradeDate, e.getMessage(), e);
            }
        }


        // -------------------------------------------------------------------------------------------------------------
        //                                            总收益
        // -------------------------------------------------------------------------------------------------------------


        sumTotalReturn(taskDO, dailyReturnList);


        log.info("backtest end     >>>     startDate : {} , endDate : {}", startDate, endDate);


        return taskDO.getId();
    }


    private BtTaskDO createBacktestTask(LocalDate startDate, LocalDate endDate) {

        BtTaskDO taskDO = new BtTaskDO();
        // B/S策略
        taskDO.setBuyStrategy("Buy-Strategy-1");
        taskDO.setSellStrategy("Sell-Strategy-1");
        // 回测 - 时间段
        taskDO.setStartDate(startDate);
        taskDO.setEndDate(endDate);
        // 初始本金
        taskDO.setInitialCapital(BigDecimal.valueOf(100_0000));
        // 初始净值
        taskDO.setInitialNav(BigDecimal.valueOf(1.0000));

        btTaskService.save(taskDO);


        return taskDO;
    }


    private void execBacktestDaily(LocalDate tradeDate,
                                   List<BigDecimal> dailyReturnList,
                                   BtTaskDO taskDO) {


        Long taskId = taskDO.getId();


        x.taskId = taskDO.getId();
        x.tradeDate = tradeDate;


        x.avlCapital = x.prevAvlCapital;
        // 总资金     =>     今日 计算前 -> 先取 昨日总资金
        x.capital = x.prevCapital;


        // -------------------------------------------------------------------------------------------------------------
        //                                            每日持仓（S前）
        // -------------------------------------------------------------------------------------------------------------


        // -------------------------------------------------- 卖出策略（ 先S[淘汰]  =>  空余资金  ->  B[新上榜] ）


        // -------------------------------------------------------------------------------------------------------------
        //                                                1、大盘 -> 仓位
        // -------------------------------------------------------------------------------------------------------------


        // 大盘量化   ->   总仓位 限制
        market__position_limit(tradeDate);


        // S前 -> 账户数据
        sell_before__statData___step1__init();


        // -------------------------------------------------------------------------------------------------------------
        //                                            S策略
        // -------------------------------------------------------------------------------------------------------------


        Map<String, String> sell_infoMap = Maps.newHashMap();


        // 卖出策略
        List<String> sell__stockCodeList = sellStrategyFactory.get("A").rule(data, tradeDate, x.positionStockCodeList, sell_infoMap);

        log.info("S策略     >>>     {} , size : {} , sell__stockCodeList : {} , sell_infoMap : {}",
                 tradeDate, sell__stockCodeList.size(), JSON.toJSONString(sell__stockCodeList), JSON.toJSONString(sell_infoMap));


        // 持仓个股   ->   匹配 淘汰


        // 2.1、当日 S策略（破位 -> S淘汰） -> stockCodeList（对昨日 持股 -> S淘汰）

        // 2.2 每日 淘汰策略（S策略 - 2）[排名]走弱 -> 末位淘汰 ->  stockCodeList（对昨日 持股 -> 末位淘汰[设置末尾淘汰 - 分数线/排名线 ]）


        // -------------------------------------------------------------------------------------------------------------
        //                                            S策略 -> 交易 record
        // -------------------------------------------------------------------------------------------------------------


        // S策略   ->   SELL TradeRecord
        createAndSave__SELL_TradeRecord(taskId, tradeDate, sell__stockCodeList, x.stockCode_positionDO_Map, sell_infoMap);


        // S后  ->  账户统计数据
        refresh_statData();


        // ----------------------- S后 仓位校验   =>   是否需要 继续减仓

        // S后 总持仓市值  >  仓位总金额 上限     =>     等比减仓
        if (x.marketValue > x.positionLimitAmount) {

            // 实际 可用资金 < 0
            x.actAvlCapital = 0;

            // 等比减仓
            持仓_大于_持仓上限___等比减仓(taskId, tradeDate);


            // 减仓后（2次 S） ->  账户统计数据
            refresh_statData();
        }


        // -------------------------------------------------------------------------------------------------------------
        //                                            B策略
        // -------------------------------------------------------------------------------------------------------------


        // -------------------------------------------------------------------------------------------------------------


        // TODO     同一日  同时满足       S策略（高位爆量上影大阴）   +   B策略（月多,60日新高,SSF多,RPS三线红,大均线多头）

        // TODO       ==>       S半仓   /   S（清仓） -> 不B


        Map<String, String> buy_infoMap = Maps.newConcurrentMap();


        // 买入策略
        List<String> buy__stockCodeList = buyStrategyFactory.get("A").rule(data, tradeDate, buy_infoMap);

        log.info("B策略     >>>     {} , size : {} , buy__stockCodeList : {} , buy_infoMap : {}",
                 tradeDate, buy__stockCodeList.size(), JSON.toJSONString(buy__stockCodeList), JSON.toJSONString(buy_infoMap));


        // -------------------------------------------------------------------------------------------------------------
        //                                            B策略 -> 交易 record
        // -------------------------------------------------------------------------------------------------------------


//        if (tradeDate.isAfter(LocalDate.of(2022, 3, 10)) && tradeDate.isBefore(LocalDate.of(2022, 3, 19))) {
//            log.debug("debug - 交易数据 交叉验证     >>>     tradeDate : {}", tradeDate);
//        }


        log.debug("B策略 -> 交易 record - start     >>>     date : {} , prevAvlCapital : {} , sellCapital : {} , avlCapital : {} , prevCapital : {}",
                  tradeDate, x.prevAvlCapital, x.sellCapital, x.avlCapital, x.prevCapital);


        // B策略   ->   BUY TradeRecord
        createAndSave__BUY_TradeRecord(taskId, tradeDate, buy__stockCodeList, x.stockCode_positionDO_Map, buy_infoMap);


        // B后  ->  账户统计数据
        refresh_statData();


        // -------------------------------------------------------------------------------------------------------------
        //                                            每日持仓 -> record
        // -------------------------------------------------------------------------------------------------------------


        // 获取 -> 持仓列表
        List<BtPositionRecordDO> positionRecordDOList = getDailyPositions(taskId, tradeDate);

        if (positionRecordDOList.size() != x.positionRecordDOList.size()
                && !TdxFunCheck.equals(JSON.toJSONString(positionRecordDOList).hashCode(), JSON.toJSONString(x.positionRecordDOList).hashCode())) {

            log.error("calcDailyReturn - err     >>>     size : [{}-{}] , positionRecordDOList : {} , x.positionRecordDOList : {}",
                      positionRecordDOList.size(), x.positionRecordDOList.size(),
                      positionRecordDOList, x.positionRecordDOList);
        }


        btPositionRecordService.saveBatch(positionRecordDOList);


        // -------------------------------------------------------------------------------------------------------------
        //                                            每日收益
        // -------------------------------------------------------------------------------------------------------------


        try {
            BtDailyReturnDO dailyReturnDO = calcDailyReturn(taskId, taskDO.getInitialCapital(), x.prevCapital, x.avlCapital, x.buyCapital, x.sellCapital, tradeDate, positionRecordDOList);
            dailyReturnList.add(dailyReturnDO.getDailyReturn());


            refresh_statData__2(dailyReturnDO);


            log.debug("dailyReturnDO : {} , dailyReturnList : {}", JSON.toJSONString(dailyReturnDO), JSON.toJSONString(dailyReturnList));


        } catch (Exception e) {

            log.error("peakNav : {} , prevCapital : {} , maxDrawdown : {} , exMsg : {}",
                      x.peakNav, x.prevCapital, x.maxDrawdown, e.getMessage(), e);
        }


        // prev 赋值
        refresh_statData__prev();
    }


    // ------------------------------------------------------- S -------------------------------------------------------


    /**
     * S策略   ->   SELL TradeRecord
     *
     * @param taskId
     * @param tradeDate
     * @param sell__stockCodeList
     * @param sell_before__stockCode_positionDO_Map
     * @param sell_infoMap
     * @return
     */
    private void createAndSave__SELL_TradeRecord(Long taskId,
                                                 LocalDate tradeDate,
                                                 List<String> sell__stockCodeList,
                                                 Map<String, BtPositionRecordDO> sell_before__stockCode_positionDO_Map,
                                                 Map<String, String> sell_infoMap) {


        List<BtTradeRecordDO> sell__tradeRecordDO__List = Lists.newArrayList();


        for (String stockCode : sell__stockCodeList) {

            BtTradeRecordDO sell_tradeRecordDO = new BtTradeRecordDO();
            sell_tradeRecordDO.setTaskId(taskId);
            sell_tradeRecordDO.setTradeType(BtTradeTypeEnum.SELL.getTradeType());
            sell_tradeRecordDO.setStockId(data.stock__codeIdMap.get(stockCode));
            sell_tradeRecordDO.setStockCode(stockCode);
            sell_tradeRecordDO.setStockName(data.stock__codeNameMap.get(stockCode));
            sell_tradeRecordDO.setTradeDate(tradeDate);
            sell_tradeRecordDO.setTradeSignal(sell_infoMap.get(stockCode));
            sell_tradeRecordDO.setPrice(NumUtil.double2Decimal(getClosePrice(stockCode, tradeDate)));
            sell_tradeRecordDO.setQuantity(sell_before__stockCode_positionDO_Map.get(stockCode).getQuantity());

            // 成交额 = 价格 x 数量
            double amount = sell_tradeRecordDO.getPrice().doubleValue() * sell_tradeRecordDO.getQuantity();
            sell_tradeRecordDO.setAmount(of(amount));

            // 仓位占比 = 持仓市值 / 总资金
            double positionPct = amount / x.capital * 100;
            sell_tradeRecordDO.setPositionPct(of(positionPct));

            sell_tradeRecordDO.setFee(BigDecimal.ZERO);


            // btTradeRecordService.save(sell_tradeRecordDO);
            sell__tradeRecordDO__List.add(sell_tradeRecordDO);
        }


        // save
        btTradeRecordService.saveBatch(sell__tradeRecordDO__List);
    }


    // ------------------------------------------------------- S -------------------------------------------------------


    /**
     * 持仓 > 持仓上限     =>     等比减仓
     *
     * @param taskId
     * @param tradeDate
     */
    private void 持仓_大于_持仓上限___等比减仓(Long taskId, LocalDate tradeDate) {


        // ----------------------------------------------------------


        // 已清仓
        if (x.positionRecordDOList.isEmpty()) {
            return;
        }


        // ---------------------------------------------------------- 等比减仓


        // 减仓总金额  =  S后_持仓总市值 - 仓位总金额_上限
        double total_reduction_amount = x.marketValue - x.positionLimitAmount;


        // 减仓总金额 市值占比 < 5%       直接略过
        if (total_reduction_amount / x.marketValue < 0.05) {
            // 金额太小  ->  略过
            log.debug("持仓_大于_持仓限制___等比减仓  -  减仓总金额[{}] 市值占比[{}%]太小 -> 略过     >>>     marketValue : {} , positionLimitAmount : {}",
                      total_reduction_amount, of(total_reduction_amount / x.marketValue * 100),
                      x.marketValue, x.positionLimitAmount);
            return;
        }


        // 持仓总市值
        double totalMarketValue = x.marketValue;


        for (BtPositionRecordDO positionRecordDO : x.positionRecordDOList) {


            String stockCode = positionRecordDO.getStockCode();
            double marketValue = positionRecordDO.getMarketValue().doubleValue();
            int quantity = positionRecordDO.getQuantity();


            // ---------------------------------------------------------------


            // 个股 减仓金额  =  个股 市值占比  x  减仓总金额
            double stock_reduction_amount = marketValue / totalMarketValue * total_reduction_amount;
//            Assert.isTrue(stock_reduction_amount <= marketValue,
//                          String.format("超卖：个股减仓金额[%s] > 个股市值[%s]", stock_reduction_amount, marketValue));


            BtTradeRecordDO sell_tradeRecordDO = new BtTradeRecordDO();
            sell_tradeRecordDO.setTaskId(taskId);
            sell_tradeRecordDO.setTradeType(BtTradeTypeEnum.SELL.getTradeType());
            sell_tradeRecordDO.setStockId(data.stock__codeIdMap.get(stockCode));
            sell_tradeRecordDO.setStockCode(stockCode);
            sell_tradeRecordDO.setStockName(data.stock__codeNameMap.get(stockCode));

            sell_tradeRecordDO.setTradeDate(tradeDate);
            // sell_tradeRecordDO.setTradeSignal(sell_infoMap.get(stockCode));
            sell_tradeRecordDO.setTradeSignal("大盘仓位限制->等比减仓");


            double closePrice = getClosePrice(stockCode, tradeDate);
            sell_tradeRecordDO.setPrice(NumUtil.double2Decimal(closePrice));


            int qty = (int) (stock_reduction_amount / closePrice);
            // 不能超卖
            qty = Math.min(qty, quantity);
            sell_tradeRecordDO.setQuantity(qty);


            // 成交额 = 价格 x 数量
            double amount = sell_tradeRecordDO.getPrice().doubleValue() * sell_tradeRecordDO.getQuantity();
            sell_tradeRecordDO.setAmount(of(amount));


            // 仓位占比 = 持仓市值 / 总资金
            double positionPct = amount / x.capital * 100;
            sell_tradeRecordDO.setPositionPct(of(positionPct));

            sell_tradeRecordDO.setFee(BigDecimal.ZERO);


            btTradeRecordService.save(sell_tradeRecordDO);
        }
    }


    // ------------------------------------------------------- B -------------------------------------------------------


    /**
     * B策略   ->   BUY TradeRecord
     *
     * @param taskId
     * @param tradeDate
     * @param buy__stockCodeList
     * @param stockCode_positionDO_Map__S_after
     * @param buy_infoMap
     */
    private void createAndSave__BUY_TradeRecord(Long taskId,
                                                LocalDate tradeDate,
                                                List<String> buy__stockCodeList,
                                                Map<String, BtPositionRecordDO> stockCode_positionDO_Map__S_after,
                                                Map<String, String> buy_infoMap) {


        int size_B = buy__stockCodeList.size();
        if (size_B == 0) {
            return;
        }


        log.debug("B策略 -> 交易 record - end     >>>     date : {} , prevAvlCapital : {} , sellCapital : {} , avlCapital : {} , prevCapital : {} , buyCapital : {}",
                  tradeDate, x.prevAvlCapital, x.sellCapital, x.avlCapital, x.prevCapital, x.buyCapital);


        // ------------------------------------------


        // 等比买入
        BigDecimal avg_amount = of(x.actAvlCapital / size_B);
        // 单一个股   单次最大买入  剩余资金 x 10%
        avg_amount = avg_amount.min(of(x.actAvlCapital * 0.1));       // 可用资金 * 10%


        // 单一个股   最大仓位限制  =  总资金 x 10%
        double amount_limit = x.capital * 0.10;      // 总资金 * 10%


        // ------------------------------------------


        // B策略   ->   BUY TradeRecord
        List<BtTradeRecordDO> buy__tradeRecordDO__List = Lists.newArrayList();


        for (String stockCode : buy__stockCodeList) {


            // 当前  待买入个股  市值（如果 此前已持有 该个股）
            double marketValue = Optional.ofNullable(x.stockCode_positionDO_Map.get(stockCode)).map(e -> e.getMarketValue().doubleValue()).orElse(0.0);


            // 可买仓位  =  最大仓位限制 - 个股市值
            double amount = amount_limit - marketValue;
            if (amount <= 0) {
                continue;
            } else {
                amount = Math.min(amount, avg_amount.doubleValue());
            }


            // -----------------------------------------------------------


            BtTradeRecordDO tradeRecordDO = new BtTradeRecordDO();
            tradeRecordDO.setTaskId(taskId);
            tradeRecordDO.setTradeType(BtTradeTypeEnum.BUY.getTradeType());
            tradeRecordDO.setStockId(data.stock__codeIdMap.get(stockCode));
            tradeRecordDO.setStockCode(stockCode);
            tradeRecordDO.setStockName(data.stock__codeNameMap.get(stockCode));
            tradeRecordDO.setTradeDate(tradeDate);
            tradeRecordDO.setTradeSignal(buy_infoMap.get(stockCode));

            // 收盘价
            BigDecimal close = NumUtil.double2Decimal(getClosePrice(stockCode, tradeDate));
            tradeRecordDO.setPrice(close);

            // 买入数量   =   可买仓位 / 收盘价                                  （忽略 🐶💩共产主义特色   ->   100股 bug）
            double qty = amount / close.doubleValue();
            tradeRecordDO.setQuantity((int) qty);

            // 成交额 = 价格 x 数量
            tradeRecordDO.setAmount(of(amount));

            // 仓位占比 = 持仓市值 / 总资金
            double positionPct = amount / x.capital * 100;
            tradeRecordDO.setPositionPct(of(positionPct));

            tradeRecordDO.setFee(BigDecimal.ZERO);


            // 买入0股（     amount -> (0,1)     ）
            if (qty < 1) {
                continue;
            }


            buy__tradeRecordDO__List.add(tradeRecordDO);
        }


        btTradeRecordService.saveBatch(buy__tradeRecordDO__List);
    }


    // ------------------------------------------------------ 大盘 ------------------------------------------------------


    /**
     * 大盘量化   ->   总仓位 限制
     *
     * @param tradeDate
     */
    private void market__position_limit(LocalDate tradeDate) {

        QaMarketMidCycleDO qaMarketMidCycleDO = indexService.marketInfo(tradeDate);
        Assert.notNull(qaMarketMidCycleDO, "[大盘量化]数据为空：" + tradeDate);


        // 总仓位 - %上限
        double positionPct = qaMarketMidCycleDO.getPositionPct().doubleValue();
        x.positionLimitRate = positionPct == 0 ? 0 : positionPct / 100;
    }


    // --------------------------------------------------- statData ----------------------------------------------------


    /**
     * SELL - before
     */
    private void sell_before__statData___step1__init() {


        // 获取 -> 持仓列表
        List<BtPositionRecordDO> positionRecordDOList = getDailyPositions(x.taskId, x.tradeDate);


        // 计算
        CalcStat calcStat = new CalcStat(positionRecordDOList, null);


        // ------------------------------------------------------------------------

        // copy覆盖
        // BeanUtils.copyProperties(calcStat, x);


        x.positionRecordDOList = positionRecordDOList;
        x.positionStockCodeList = calcStat.getPositionStockCodeList();
        x.stockCode_positionDO_Map = calcStat.getStockCode_positionDO_Map();


        // ------------------------------------------------------------------------


        // 当前 总市值   =   S前 总市值
        x.marketValue = calcStat.getMarketValue();

        // S前 可用资金   =   昨日 可用资金
        x.avlCapital = x.prevAvlCapital;


        // ---------------------------------------------------------- 不变


        // S前 总资金   =   S前 总市值  +  S前 可用资金
        x.capital = x.marketValue + x.avlCapital;
        log.debug("init capital   -   date : {}     >>>     capital : {} , marketValue : {} , avlCapital : {}",
                  x.tradeDate, x.capital, x.marketValue, x.avlCapital);


        // ---------------------------------------------------------- 不变


        // 仓位总金额 上限   =   总资金  x  仓位百分比 上限
        x.positionLimitAmount = x.capital * x.positionLimitRate;


        // 当前 实际可用资金（策略 -> 仓位限制）  =   仓位总金额_上限   -   持仓总市值
        x.actAvlCapital = x.positionLimitAmount - x.marketValue;
    }


    // --------------------------------------------------- statData ----------------------------------------------------


    /**
     * refresh  ->  statData
     */
    private void refresh_statData() {
        // 获取  ->  当前 持仓列表
        List<BtPositionRecordDO> positionRecordDOList = getDailyPositions(x.taskId, x.tradeDate);
        // 获取  ->  今日 B/S记录
        List<BtTradeRecordDO> tradeRecordDOList = btTradeRecordService.listByTaskIdAndTradeDate(x.taskId, x.tradeDate);


        // 计算
        CalcStat calcStat = new CalcStat(positionRecordDOList, tradeRecordDOList);
        // copy覆盖
        BeanUtils.copyProperties(calcStat, x);
    }


    // --------------------------------------------------- statData ----------------------------------------------------


    private void refresh_statData__2(BtDailyReturnDO dailyReturnDO) {

        BigDecimal nav = dailyReturnDO.getNav();
        BigDecimal capital = dailyReturnDO.getCapital();


        if (!TdxFunCheck.equals(capital, x.capital)) {
            log.error("calcDailyReturn - err     >>>     capital : {} , x.capital : {}", capital, x.capital);
        }

        if (!TdxFunCheck.equals(dailyReturnDO.getMarketValue(), x.marketValue)) {
            log.error("calcDailyReturn - err     >>>     marketValue : {} , x.marketValue : {}", dailyReturnDO.getMarketValue(), x.marketValue);
        }
        if (!TdxFunCheck.equals(dailyReturnDO.getAvlCapital(), x.avlCapital)) {
            log.error("calcDailyReturn - err     >>>     avlCapital : {} , x.avlCapital : {}", dailyReturnDO.getAvlCapital(), x.avlCapital);
        }


        // 汇总统计 - 指标更新
        if (dailyReturnDO.getDailyReturn().doubleValue() > 0) x.winCount++;
        // 波峰净值
        x.peakNav = x.peakNav.max(nav);
        // 回撤 =（波峰净值 - 当日净值） / 波峰净值
        BigDecimal dd = x.peakNav.subtract(nav).divide(x.peakNav, 8, RoundingMode.HALF_UP);
        // 最大回撤
        x.maxDrawdown = x.maxDrawdown.max(dd);


        x.nav = nav.doubleValue();

        // x.prevNav = nav;
        // x.prevCapital = capital.doubleValue();
        // x.prevAvlCapital = x.avlCapital;
    }


    /**
     * prev 赋值
     */
    private void refresh_statData__prev() {

        Stat x_copy = new Stat();
        BeanUtils.copyProperties(x, x_copy);


        // 1、清空
        x = new Stat();


        // 2、today -> pre
        x.prevCapital = x_copy.capital;
        x.prevAvlCapital = x_copy.avlCapital;
        x.prevNav = of(x_copy.nav);


        x.taskId = x_copy.taskId;


        // --------------------------------


        x.peakNav = x_copy.peakNav;
        x.maxDrawdown = x_copy.maxDrawdown;
        x.winCount = x_copy.winCount;
    }


    // -----------------------------------------------------------------------------------------------------------------


    public LocalDate tradeDateIncr(LocalDate tradeDate) {
        Integer idx = data.dateIndexMap.get(tradeDate);

        // 非交易日
        while (idx == null) {
            // 下一自然日   ->   直至 交易日
            tradeDate = tradeDate.plusDays(1);
            idx = data.dateIndexMap.get(tradeDate);


            if (!DateTimeUtil.between(tradeDate, data.dateList.get(0), data.dateList.get(data.dateList.size() - 1))) {
                throw new BizException(String.format("[日期：%s]非法，超出有效交易日范围", tradeDate));
            }
        }


        // 下一个
        return data.dateList.get(idx + 1);
    }

    public LocalDate tradeDateDecr(LocalDate tradeDate) {
        Integer idx = data.dateIndexMap.get(tradeDate);

        // 非交易日
        while (idx == null) {
            // 上一自然日   ->   直至 交易日
            tradeDate = tradeDate.minusDays(1);
            idx = data.dateIndexMap.get(tradeDate);


            if (!DateTimeUtil.between(tradeDate, data.dateList.get(0), data.dateList.get(data.dateList.size() - 1))) {
                throw new BizException(String.format("[日期：%s]非法，超出有效交易日范围", tradeDate));
            }
        }


        // 上一个
        return data.dateList.get(idx - 1);
    }


    /**
     * 计算  ->  每日收益率
     *
     * @param taskId
     * @param initialCapital       本金
     * @param avlCapital
     * @param buyCapital
     * @param sellCapital
     * @param tradeDate            当前 交易日
     * @param positionRecordDOList 当前 持仓列表
     */
    private BtDailyReturnDO calcDailyReturn(Long taskId,
                                            BigDecimal initialCapital,
                                            double prevCapital,
                                            double avlCapital, double buyCapital, double sellCapital,
                                            LocalDate tradeDate,
                                            List<BtPositionRecordDO> positionRecordDOList) {


        // 当日 持仓市值   =   个股市值   汇总
        BigDecimal marketValue = positionRecordDOList.stream()
                                                     .map(BtPositionRecordDO::getMarketValue)
                                                     .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (!TdxFunCheck.equals(marketValue, x.marketValue)) {
            log.error("calcDailyReturn - err     >>>     marketValue : {} , x.marketValue : {}", marketValue, x.marketValue);
        }


        // 总资金  =  持仓市值 + 可用资金
        BigDecimal capital = marketValue.add(of(avlCapital));

        if (!TdxFunCheck.equals(capital, x.capital)) {
            log.error("calcDailyReturn - err     >>>     capital : {} , x.capital : {}", capital, x.capital);
        }


        if (!TdxFunCheck.equals(avlCapital, x.avlCapital)) {
            log.error("calcDailyReturn - err     >>>     avlCapital : {} , x.avlCapital : {}", avlCapital, x.avlCapital);
        }

        // 仓位占比 = 持仓市值 / 总资金
        BigDecimal positionPct = marketValue.divide(capital, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));


        // 净值 = 总资金 / 本金
        BigDecimal nav = capital.divide(initialCapital, 8, RoundingMode.HALF_UP);

        // 当日盈亏额 = 当日总资金 - 昨日总资金
        BigDecimal profitLossAmount = capital.subtract(of(prevCapital));

        // 当日收益率 = 当日总资金 / 昨日总资金 - 1
        // BigDecimal dailyReturn = capital.divide(prevCapital, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);

        // 当日收益率 = 当日盈亏额 / 昨日总资金
        BigDecimal dailyReturn = profitLossAmount.divide(of(prevCapital), 6, RoundingMode.HALF_UP);
        log.debug("calcDailyReturn     >>>     date : {} , marketValue : {} , avlCapital : {} , capital : {} , prevCapital : {} , profitLossAmount : {} , dailyReturn : {} , nav : {}",
                  tradeDate, marketValue, avlCapital, capital, prevCapital, profitLossAmount, dailyReturn, nav);


        BtDailyReturnDO dailyReturnDO = new BtDailyReturnDO();
        dailyReturnDO.setTaskId(taskId);
        // 日期
        dailyReturnDO.setTradeDate(tradeDate);
        // 当日收益率
        dailyReturnDO.setDailyReturn(dailyReturn);
        // 当日盈亏额
        dailyReturnDO.setProfitLossAmount(profitLossAmount);
        // 净值
        dailyReturnDO.setNav(nav);
        // 总资金
        dailyReturnDO.setCapital(capital);
        // 持仓市值
        dailyReturnDO.setMarketValue(marketValue);
        // 仓位占比（%）
        dailyReturnDO.setPositionPct(positionPct);
        // 仓位上限占比（%）
        dailyReturnDO.setPositionLimitPct(of(x.positionLimitRate * 100));
        // 可用资金
        dailyReturnDO.setAvlCapital(of(avlCapital));
        // 买入金额
        dailyReturnDO.setBuyCapital(of(buyCapital));
        // 卖出金额
        dailyReturnDO.setSellCapital(of(sellCapital));

        // 基准收益（沪深300）
        dailyReturnDO.setBenchmarkReturn(null);

        btDailyReturnService.save(dailyReturnDO);


        return dailyReturnDO;
    }


    /**
     * 汇总计算 -> 总收益
     *
     * @param taskDO
     * @param dailyReturnList
     */
    private void sumTotalReturn(BtTaskDO taskDO, List<BigDecimal> dailyReturnList) {


        // 4. 全期汇总：更新 bt_task


        // 持仓天数   ->   间隔  N个交易日
        int totalDays = between(tradeDateIncr(taskDO.getStartDate()), tradeDateIncr(taskDO.getEndDate()), data.dateIndexMap);


        BigDecimal finalNav = x.prevNav;
        BigDecimal finalCapital = of(x.prevCapital);


        BigDecimal totalReturn = finalNav.subtract(BigDecimal.ONE);         // 净值增幅
        BigDecimal totalReturnPct = totalReturn.multiply(of(100));      // %
        BigDecimal annualReturnPct = of(Math.pow(finalNav.doubleValue(), 252.0 / totalDays) - 1).multiply(of(100));


        // 夏普比率 = 平均日收益 / 日收益标准差 * sqrt(252)
        double mean = dailyReturnList.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double sd = Math.sqrt(dailyReturnList.stream()
                                             .mapToDouble(r -> Math.pow(r.doubleValue() - mean, 2)).sum()
                                      / dailyReturnList.size());
        BigDecimal sharpe = of(mean / sd * Math.sqrt(252));

        BigDecimal winRate = of((double) x.winCount / totalDays * 100);
        // 盈亏比 = 所有盈利日平均收益 / 所有亏损日平均亏损
        double avgWin = dailyReturnList.stream().filter(r -> r.doubleValue() > 0)
                                       .mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double avgLoss = dailyReturnList.stream().filter(r -> r.doubleValue() < 0)
                                        .mapToDouble(BigDecimal::doubleValue).map(Math::abs).average().orElse(0);
        BigDecimal profitFactor = avgLoss == 0
                ? of(Double.POSITIVE_INFINITY)
                : of(avgWin / avgLoss);


        taskDO.setFinalCapital(finalCapital);
        taskDO.setFinalNav(finalNav);
        taskDO.setTotalDay(totalDays);
        taskDO.setTotalReturnPct(totalReturnPct);
        taskDO.setAnnualReturnPct(annualReturnPct);
        taskDO.setSharpeRatio(sharpe);
        taskDO.setMaxDrawdownPct(x.maxDrawdown);
        taskDO.setWinRate(winRate);
        taskDO.setProfitFactor(profitFactor);


        btTaskService.updateById(taskDO);
    }


    /**
     * 获取 某回测任务 在指定日期的   持仓详情
     *
     * @param taskId
     * @param endTradeDate 统计 截止日期
     * @return
     */
    private List<BtPositionRecordDO> getDailyPositions(Long taskId, LocalDate endTradeDate) {


        // 每次  ->  全量查询
        // 拿到某任务到指定日期的所有交易记录（已按 trade_date、id 升序）
        // List<BtTradeRecordDO> allTrades_DB = btTradeRecordService.listByTaskIdAndTradeDateRange(taskId, null, endTradeDate);


        // -------------------------------------------------------------------------------------------------------------


        // 1、全量 B/S记录     =>     当前B/S（未清仓）   +   历史B/S（已清仓）


        // 每次  ->  增量查询     +     历史记录（cache）


        List<BtTradeRecordDO> incrQuery_tradeRecordList = btTradeRecordService.listByTaskIdAndTradeDate(taskId, endTradeDate);


        incrQuery_tradeRecordList.forEach(e -> {

            if (tradeRecord___idSet__cache.add(e.getId())) {
                tradeRecordList__cache.add(e);
            }
        });


//        if (allTrades_DB.size() != tradeRecordList__cache.size()) {
//            log.error("getDailyPositions - BtTradeRecordDOList err     >>>     DB : {} , cache : {}",
//                      allTrades_DB.size(), tradeRecordList__cache.size());
//        }


        // -------------------------------------------------------------------------------------------------------------


        // 2、剔除   ->   历史B/S（已清仓）


        // 当前B/S（未清仓）
        List<BtTradeRecordDO> doList2 = Lists.newArrayList();


        // 构建 FIFO 队列：stockCode -> 队列里存 剩余的买单
        Map<String, Deque<MutableTrade>> buyQueues = new HashMap<>();


        // 遍历所有记录，构建/抵销
        for (BtTradeRecordDO tr : tradeRecordList__cache) {

            String code = tr.getStockCode();
            int qty = tr.getQuantity();


            if (Objects.equals(tr.getTradeType(), BtTradeTypeEnum.BUY.getTradeType())) {

                // 买入：入队
                buyQueues.computeIfAbsent(code, k -> new LinkedList<>()).addLast(new MutableTrade(tr, qty));

            } else {

                // 卖出：用 FIFO 队头买单抵销
                Deque<MutableTrade> queue = buyQueues.get(code);
                int remaining = qty;
                while (remaining > 0 && queue != null && !queue.isEmpty()) {
                    MutableTrade head = queue.peekFirst();
                    if (head.remainingQty > remaining) {
                        head.remainingQty -= remaining;
                        remaining = 0;
                    } else {
                        remaining -= head.remainingQty;
                        queue.pollFirst(); // 这个买单完全抵销
                    }
                }

                // （可选）如果 remaining>0，说明卖空或超卖，按业务处理
            }
        }


        // 从各队列里收集所有剩余的买单，转换回原 DTO 并把 quantity 调成剩余数量
        for (Deque<MutableTrade> queue : buyQueues.values()) {
            for (MutableTrade mt : queue) {

                BtTradeRecordDO openBuy = mt.original;
                openBuy.setQuantity(mt.remainingQty);

                doList2.add(openBuy);
            }
        }

        // doList2 中即为“当前未清仓”的买入记录（quantity 已是剩余量）


        // -------------------------------------------------------------------------------------------------------------


        // 3. 汇总买卖
        Map<Long, Integer> quantityMap = Maps.newHashMap();     // 个股持仓 -   总数量
        Map<Long, Integer> avlQuantityMap = Maps.newHashMap();  // 个股持仓 - 可用数量（T+1）
        Map<Long, Double> amountMap = Maps.newHashMap();        // 个股持仓 -   总成本（买入价格 x 买入数量   ->   累加）

        Map<Long, PositionInfo> idInfoMap = Maps.newHashMap();  //


        for (BtTradeRecordDO tradeRecordDO : doList2) {


            Long stockId = tradeRecordDO.getStockId();
            String stockCode = tradeRecordDO.getStockCode();
            String stockName = tradeRecordDO.getStockName();
            // BUY 或 SELL
            Integer tradeType = tradeRecordDO.getTradeType();
            Integer quantity = tradeRecordDO.getQuantity();
            BigDecimal amount = tradeRecordDO.getAmount();

            // 实际 交易日期
            LocalDate tradeDate = tradeRecordDO.getTradeDate();


            // 买入累加 / 卖出累减   ->   总数量、总成本
            int sign = Objects.equals(BtTradeTypeEnum.BUY.getTradeType(), tradeType) ? +1 : -1;
            // 个股持仓 - 总数量
            quantityMap.merge(stockId, sign * quantity, Integer::sum);
            // 个股持仓 - 总成本
            amountMap.merge(stockId, sign * amount.doubleValue(), Double::sum);


            // T+1（🐶💩共产主义特色）
            if (!(sign == 1 && tradeDate.isEqual(endTradeDate))) {
                // 今日可用（排除 -> 当日 BUY）
                avlQuantityMap.merge(stockId, sign * quantity, Integer::sum);
            } else {
                avlQuantityMap.merge(stockId, 0, Integer::sum);
            }


            PositionInfo positionInfo = idInfoMap.get(stockId);
            if (positionInfo == null) {

                positionInfo = new PositionInfo(stockId, stockCode, stockName, tradeDate);
                idInfoMap.put(stockId, positionInfo);

            } else {

                // LocalDate buyDate = tradeDate.isBefore(positionInfo.buyDate) ? tradeDate : positionInfo.buyDate;


                // 更新  ->  最近一次  首次买入日期（用于计算 持仓天数）     =>     最近一次  avlQuantity = 0
                if (avlQuantityMap.get(stockId) == 0) {
                    // 最近一次
                    // LocalDate buyDate = tradeDate.isAfter(positionInfo.buyDate) ? tradeDate : positionInfo.buyDate;
                    LocalDate buyDate = tradeDate.isBefore(positionInfo.buyDate) ? tradeDate : positionInfo.buyDate;
                    positionInfo.setBuyDate(buyDate);
                }
            }
        }


        // 2. 构造持仓对象列表
        List<BtPositionRecordDO> positionRecordDOList = Lists.newArrayList();


        quantityMap.forEach((stockId, qty) -> {
            if (qty <= 0) {
                return;  // 当日未持仓 或 已全部卖出
            }


            Integer avlQuantity = avlQuantityMap.getOrDefault(stockId, 0);
            PositionInfo positionInfo = idInfoMap.get(stockId);


            // 总成本
            double totalCost = amountMap.getOrDefault(stockId, 0.0);
            // 平均成本 = 总成本 / 持仓数量
            double avgCost = totalCost / qty;


            // 每次B/S   ->   成本/收益/收益率   ->   独立事件（边界）     ==>     否则，上次B/S 亏损  ->  合并计入  本次B/S   =>   亏损 -> 负数bug（总成本 负数 -> 平均成本 负数）     =>     市值 爆减bug
            if (avgCost < 0) {
                log.error("getDailyPositions - avgCost err     >>>     totalCost : {} , qty : {} , avgCost : {}", totalCost, qty, avgCost);
            }


            // 当日收盘价
            double closePrice = getClosePrice(positionInfo.stockCode, endTradeDate);

            // 浮动盈亏 = （当日收盘价 - 平均成本）x 持仓数量
            double pnl = (closePrice - avgCost) * qty;


            BtPositionRecordDO positionRecordDO = new BtPositionRecordDO();
            positionRecordDO.setTaskId(taskId);
            positionRecordDO.setTradeDate(endTradeDate);
            positionRecordDO.setStockId(stockId);
            positionRecordDO.setStockCode(positionInfo.stockCode);
            positionRecordDO.setStockName(positionInfo.stockName);
            positionRecordDO.setAvgCostPrice(of(avgCost));
            positionRecordDO.setClosePrice(of(closePrice));
            positionRecordDO.setQuantity(qty);
            positionRecordDO.setAvlQuantity(avlQuantity);
            // 当前市值 = 持仓数量 x 当前收盘价
            positionRecordDO.setMarketValue(of(qty * closePrice));
            // 仓位占比 = 持仓市值 / 总资金
            BigDecimal positionPct = positionRecordDO.getMarketValue().divide(of(x.capital), 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            positionRecordDO.setPositionPct(positionPct);
            // 盈亏额
            positionRecordDO.setUnrealizedPnl(of(pnl));
            // 盈亏率 = 盈亏额 / 总成本
            positionRecordDO.setUnrealizedPnlRatio(of(pnl / totalCost));
            positionRecordDO.setBuyDate(positionInfo.buyDate);
            positionRecordDO.setHoldingDays(positionInfo.getHoldingDays(endTradeDate, data.dateIndexMap));


            positionRecordDOList.add(positionRecordDO);
        });


        // save2DB
        // btPositionRecordService.saveBatch(positionRecordDOList);


        return positionRecordDOList;
    }


    /**
     * 个股   指定日期 -> 收盘价
     *
     * @param stockCode
     * @param tradeDate
     * @return
     */
    private double getClosePrice(String stockCode, LocalDate tradeDate) {
        Double closePrice = data.stock__dateCloseMap.get(stockCode).get(tradeDate);


        // 停牌（603039 -> 2023-04-03）
        int count = 0;
        while (closePrice == null && count++ < 500) {
            // 交易日 往前一位
            tradeDate = tradeDateDecr(tradeDate);
            closePrice = data.stock__dateCloseMap.get(stockCode).get(tradeDate);
        }


        return closePrice == null ? 0.0 : closePrice;
    }


    private synchronized void initData(LocalDate startDate, LocalDate endDate) {

        // 重新初始化   统计数据
        x = new Stat();

        tradeRecord___idSet__cache = Sets.newHashSet();
        tradeRecordList__cache = Lists.newArrayList();


        // 全量行情
        data = initDataService.initData(startDate, endDate, false);
    }


    // -----------------------------------------------------------------------------------------------------------------


    @Data
    @AllArgsConstructor
    public static class CalcStat {


        // ----------------------------------------- 不变


        // 当前 仓位限制（不变）
        double positionLimitRate;       // 仓位百分比 上限     =>     大盘量化 -> 计算
        double positionLimitAmount;     // 仓位总金额 上限     =      总资金  x  仓位百分比 上限


        // 当前 总资金（B/S 任意变换   ->   capital 不变）   =   持仓总市值（跟随BS 变动） +  可用资金（跟随BS 变动）
        double capital;


        // ----------------------------------------- 不变


        // ------------------------------------------------------------


        // ----------------------------------------- 可变


        // 当前 持仓列表
        List<BtPositionRecordDO> positionRecordDOList;
        List<String> positionStockCodeList;
        Map<String, BtPositionRecordDO> stockCode_positionDO_Map = Maps.newHashMap();


        // 当前 持仓总市值   <=   仓位限制
        double marketValue;


        // 当前 可用资金   =   总资金 - 持仓总市值
        double avlCapital;

        // 当前 实际可用资金（大盘 -> 仓位限制）  =   仓位总金额 上限   -   持仓总市值
        double actAvlCapital;


        // ----------------------------------------- 可变


        // 今日 B/S记录
        List<BtTradeRecordDO> tradeRecordDOList;

        // 卖出总金额
        double sellCapital;
        // 买入总金额
        double buyCapital;


        // ------------------------------------------------------------


        public CalcStat(List<BtPositionRecordDO> positionRecordDOList, List<BtTradeRecordDO> tradeRecordDOList) {
            this.positionLimitRate = x.positionLimitRate;
            this.positionLimitAmount = x.positionLimitAmount;
            this.capital = x.capital;


            // ------------------------------------------


            // 今日 B/S记录
            this.tradeRecordDOList = tradeRecordDOList;

            // this.sellCapital = x.sellCapital;
            // this.buyCapital = x.buyCapital;
            this.sellCapital = getSellCapital();
            this.buyCapital = getBuyCapital();


            // ------------------------------------------


            // 当前 持仓列表
            this.positionRecordDOList = positionRecordDOList;
            this.positionStockCodeList = getPositionStockCodeList();
            this.stockCode_positionDO_Map = getStockCode_positionDO_Map();


            this.marketValue = getMarketValue();
            this.avlCapital = getAvlCapital();
            this.actAvlCapital = getActAvlCapital();


            // ------------------------------------------


            // check
            checkStatData();
        }


        private void checkStatData() {

            // 总资金  =  总市值 + 可用资金
            double capital_2 = marketValue + avlCapital;
            if (!TdxFunCheck.equals(capital, capital_2)) {
                log.error("check err     >>>     capital : {} , capital_2 : {}", capital, capital_2);
            }

            // 可用资金  =  prev_可用资金 + 卖出 - 买入
            double avlCapital_2 = x.prevAvlCapital + sellCapital - buyCapital;
            if (tradeRecordDOList != null && !TdxFunCheck.equals(avlCapital, avlCapital_2, 500, 0.01)) {
                log.error("check err     >>>     avlCapital : {} , avlCapital_2 : {}", avlCapital, avlCapital_2);
            }
        }


        // ------------------------------------------------------------


        // -------------------------- 持仓


        public List<String> getPositionStockCodeList() {
            // 持仓 code列表
            this.positionStockCodeList = positionRecordDOList.stream().map(BtPositionRecordDO::getStockCode).collect(Collectors.toList());
            return this.positionStockCodeList;
        }


        public Map<String, BtPositionRecordDO> getStockCode_positionDO_Map() {
            positionRecordDOList.stream().forEach(e -> stockCode_positionDO_Map.put(e.getStockCode(), e));
            return stockCode_positionDO_Map;
        }

        public double getMarketValue() {
            if (CollectionUtils.isEmpty(positionRecordDOList)) {
                return 0;
            }

            return positionRecordDOList.stream()
                                       .map(BtPositionRecordDO::getMarketValue)
                                       .reduce(BigDecimal.ZERO, BigDecimal::add)
                                       .doubleValue();
        }

        public double getAvlCapital() {
            double avlCapital_1 = capital - marketValue;
            double avlCapital_2 = x.prevAvlCapital + sellCapital - buyCapital;

            // 前置init阶段 -> 不校验 （capital -> 还未计算）
            if (tradeRecordDOList != null && !TdxFunCheck.equals(avlCapital_1, avlCapital_2, 500, 0.01)) {
                log.debug("getAvlCapital err     >>>     {} , {}", avlCapital_1, avlCapital_2);
            }

            return avlCapital_1;
        }

        public double getActAvlCapital() {
            return actAvlCapital = positionLimitAmount - marketValue;
        }


        // -------------------------- B/S


        public double getSellCapital() {
            if (CollectionUtils.isEmpty(tradeRecordDOList)) {
                return 0;
            }

            return tradeRecordDOList.stream()
                                    .filter(e -> e.getTradeType().equals(BtTradeTypeEnum.SELL.getTradeType()))
                                    .map(BtTradeRecordDO::getAmount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .doubleValue();
        }

        public double getBuyCapital() {
            if (CollectionUtils.isEmpty(tradeRecordDOList)) {
                return 0;
            }

            return tradeRecordDOList.stream()
                                    .filter(e -> e.getTradeType().equals(BtTradeTypeEnum.BUY.getTradeType()))
                                    .map(BtTradeRecordDO::getAmount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .doubleValue();
        }
    }


    /**
     * 统计数据
     */
    @Data
    public static class Stat {


        // -------------------- 每日


        // 总资金
        double prevCapital;
        // 可用资金
        double prevAvlCapital;
        // 净值 1.0000
        BigDecimal prevNav = BigDecimal.ONE;


        // -------------------- 汇总


        // 收益率 - 峰值
        BigDecimal peakNav = of(0.000001);
        // 最大回撤
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        // 盈利天数
        int winCount = 0;


        // ----------------------------------------------------------------------------------


        // taskId
        Long taskId;

        // 当前 交易日
        LocalDate tradeDate;


        // ----------------------------------------------------------------------------------


        // S前（昨日持仓） -> S -> S后（减仓前） -> 减仓 -> 减仓后（B前） -> B -> B后


        // ----------------------------------------- 不变


        // 当前 仓位限制（不变）
        double positionLimitRate;       // 仓位百分比 上限     =>     大盘量化 -> 计算
        double positionLimitAmount;     // 仓位总金额 上限     =      总资金  x  仓位百分比 上限


        // 当前 总资金（B/S 任意变换   ->   capital 不变）   =   持仓总市值（跟随BS 变动） +  可用资金（跟随BS 变动）
        double capital;


        // 净值  =  总资金 / 初始本金
        double nav;


        // ----------------------------------------- 可变


        // 持仓列表
        List<BtPositionRecordDO> positionRecordDOList;
        List<String> positionStockCodeList;
        Map<String, BtPositionRecordDO> stockCode_positionDO_Map = Maps.newHashMap();


        // --------------------


        // 当前 持仓总市值   <=   仓位限制
        double marketValue;


        // 当前 可用资金   =   总资金 - 持仓总市值
        double avlCapital;

        // 当前 实际可用资金（大盘 -> 仓位限制）  =   仓位总金额 上限   -   持仓总市值
        double actAvlCapital;


        // ----------------------------------------- 可变


        // -------------------- B/S策略

        double sellCapital;
        double buyCapital;


        // --------------------
    }


    @Data
    @AllArgsConstructor
    public static class PositionInfo {
        private Long stockId;
        private String stockCode;
        private String stockName;
        private LocalDate buyDate;
        // private Integer holdingDays;

        public Integer getHoldingDays(LocalDate endTradeDate, Map<LocalDate, Integer> dateIndexMap) {
            // 持仓天数   ->   间隔  N个交易日
            return between(buyDate, endTradeDate, dateIndexMap);
        }
    }


    /**
     * 两个交易日   间隔天数(交易日)
     *
     * @param start
     * @param end
     * @param dateIndexMap 交易日-idx
     * @return
     */
    public static int between(LocalDate start, LocalDate end, Map<LocalDate, Integer> dateIndexMap) {
        Assert.isTrue(!start.isAfter(end), String.format("start[%s]不能大于end[%s]", start, end));


        Integer idx1 = dateIndexMap.get(start);
        Integer idx2 = dateIndexMap.get(end);

        Assert.notNull(idx1, String.format("start[%s]非交易日", start));
        Assert.notNull(idx2, String.format("end[%s]非交易日", end));

        return idx2 - idx1;
    }


//    private BigDecimal of(double val) {
//        return BigDecimal.valueOf(val);
//    }

    private static BigDecimal of(Number val) {
        return BigDecimal.valueOf(val.doubleValue()).setScale(4, RoundingMode.HALF_UP);
    }


    /**
     * 辅助类：包装一条买入记录及其剩余可抵销数量
     **/
    @Data
    @AllArgsConstructor
    private static class MutableTrade {
        // 买入记录
        final BtTradeRecordDO original;
        // 剩余可抵销数量
        int remainingQty;
    }

}
