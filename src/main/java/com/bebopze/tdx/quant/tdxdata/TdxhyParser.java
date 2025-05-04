package com.bebopze.tdx.quant.tdxdata;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.bebopze.tdx.quant.constant.TdxConst.TDX_PATH;


/**
 * tdxhy.cfg   -   解析
 * -
 * -   /T0002/hq_cache/tdxhy.cfg               个股 - 行业板块（研究行业 + 普通行业）
 *
 * @author: bebopze
 * @date: 2025/5/4
 */
@Slf4j
public class TdxhyParser {


    // --------------------------------------------------------------------------- 个股-行业板块
    private static final String filePath_hy = TDX_PATH + "/T0002/hq_cache/tdxhy.cfg";

    public static void main(String[] args) {

        parseTdxStockRelaHy(filePath_hy);

    }


    /**
     * tdx 板块数据   ->     个股 - 行业BK
     *
     * @param filePath \new_tdx\T0002\hq_cache\tdxhy.cfg
     * @return
     */
    public static void parseTdxStockRelaHy(String filePath) {

        try {
            List<String> lines = FileUtils.readLines(new File(filePath), "GB2312");

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);


                // 0|000001|T1001|||X500102
                //
                // 1|900901||||


                // 处理每一行
                if (StringUtils.hasText(line)) {


                    // 0|000001|T1001|||X500102


                    String[] strArr = line.trim().split("\\|");
                    if (strArr.length < 6) {

                        // 1|900901||||
                        // 0|002710||||       ->       B股     无行业信息，直接忽略
                        log.error("parseTdxStockRelaHy err     >>>     filePath : {} , 行数 : {} , line : {} ", filePath, i, line);
                        continue;
                    }


                    // 0-深A；1-沪A；2-京A；
                    String shj_type = strArr[0];

                    // 000001
                    String stockCode = strArr[1];

                    // T1001    -   2-普通行业
                    String hyCode_T = strArr[2];


                    String x_3 = strArr[3];
                    String x_4 = strArr[4];


                    // X500102    -   12-研究行业
                    String hyCode_X = strArr[5];


                    save2DB(shj_type, stockCode, hyCode_T, hyCode_X);


                    System.out.println(JSON.toJSONString(strArr));
                }
            }

        } catch (IOException e) {

            log.error("parseTdxStockRelaHy err     >>>     filePath : {},   errMsg : {}", filePath, e.getMessage(), e);
        }
    }


    private static void save2DB(String shjType,
                                String stockCode,
                                String hyCodeT,
                                String hyCodeX) {


        // TODO   save2DB


    }

}
