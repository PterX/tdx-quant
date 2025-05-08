package com.bebopze.tdx.quant.tdxdata;

import com.alibaba.fastjson.JSON;
import com.bebopze.tdx.quant.common.constant.BlockTypeEnum;
import com.google.common.collect.Lists;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.bebopze.tdx.quant.common.constant.TdxConst.TDX_PATH;


/**
 * 通达信   报表导出（板块成份）   -   解析
 * -
 * -   34（数据导出）   ->   板块成份导出   ->   逗号分隔
 * -
 *
 * @author: bebopze
 * @date: 2025/5/7
 */
@Slf4j
public class ExportBlockParser {


    private static final String filePath_hy = TDX_PATH + "/T0002/export/行业板块.txt";
    private static final String filePath_gn = TDX_PATH + "/T0002/export/概念板块.txt";
    private static final String filePath_fg = TDX_PATH + "/T0002/export/风格板块.txt";
    private static final String filePath_dq = TDX_PATH + "/T0002/export/地区板块.txt";
    private static final String filePath_zs = TDX_PATH + "/T0002/export/指数板块.txt";
    private static final String filePath_zdy = TDX_PATH + "/T0002/export/自定义板块.txt";


    public static void main(String[] args) {

        parseExport();
    }


    public static void parseExport() {

        parse(filePath_hy, BlockTypeEnum.HY_PT);
        parse(filePath_hy, BlockTypeEnum.HY_YJ);

        parse(filePath_gn, BlockTypeEnum.GN);
        parse(filePath_fg, BlockTypeEnum.FG);
        parse(filePath_dq, BlockTypeEnum.DQ);
        parse(filePath_zs, BlockTypeEnum.ZS);

        parse(filePath_zdy, BlockTypeEnum.ZDY);
    }


    /**
     * 个股-所属板块   [报表]       解析
     * -
     * - /new_tdx/T0002/export/
     *
     * @return
     */
    public static List<ExportBlockDTO> parse(String filePath,
                                             BlockTypeEnum blockTypeEnum) {

        List<ExportBlockDTO> dtoList = Lists.newArrayList();


        try {
            List<String> lines = FileUtils.readLines(new File(filePath), "GB2312");
            for (String line : lines) {

                // 处理每一行
                if (StringUtils.hasText(line)) {


                    // 880515,通达信88,000100,TCL科技


                    String[] strArr = line.trim().split(",");

                    if (strArr.length < 4) {
                        continue;
                    }


                    // 板块code
                    String blockCode = strArr[0];
                    // 板块name
                    String blockName = strArr[1];


                    // 个股code
                    String stockCode = strArr[2];
                    // 个股name
                    String stockName = strArr[3];


                    // -------------------------------------------


                    ExportBlockDTO dto = new ExportBlockDTO();
                    dto.setBlockCode(blockCode);
                    dto.setBlockName(blockName);
                    dto.setStockCode(stockCode);
                    dto.setStockName(stockName);

                    dtoList.add(dto);


                    // System.out.println(JSON.toJSONString(strArr));
                }
            }


        } catch (IOException e) {

            log.error("ExportBlockParser#parse err     >>>     filePath : {} , blockType : {} , errMsg : {}", filePath, blockTypeEnum, e.getMessage(), e);
        }


        log.info("ExportBlockParser#parse suc     >>>     filePath : {} , blockType : {} , totalNum : {} , dtoList : {}", filePath, blockTypeEnum, dtoList.size(), JSON.toJSONString(dtoList));
        return dtoList;
    }


    // -----------------------------------------------------------------------------------------------------------------


    @Data
    static class ExportBlockDTO {
        /**
         * 板块code
         */
        private String blockCode;
        /**
         * 板块name
         */
        private String blockName;

        /**
         * 个股code
         */
        private String stockCode;
        /**
         * 个股name
         */
        private String stockName;
    }

}