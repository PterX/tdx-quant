package com.bebopze.tdx.quant.tdxdata;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.bebopze.tdx.quant.common.constant.TdxConst.TDX_PATH;


/**
 * 通达信   自定义板块   xxx.blk     -     读 + 写
 * -
 * -   /T0002/blocknew/xxx.blk               自定义 - 板块 （公式选股 - 股票池子）
 *
 * @author: bebopze
 * @date: 2025/5/9
 */
@Slf4j
public class BlockNewParser {


    private static final String filePath = TDX_PATH + "/T0002/blocknew/IDEA-test.blk";


    public static void main(String[] args) {

        parse();


        System.out.println();
        System.out.println("---------------------------------------");
        System.out.println();


        write(Lists.newArrayList("0000001", "0000002", "0000007"));


        parse();
    }


    /**
     * 股票池子   -   read
     * -
     * - /T0002/blocknew/xxx.blk
     *
     * @return
     */
    public static void parse() {

        try {
            List<String> lines = FileUtils.readLines(new File(filePath), "UTF-8");

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);


                // 1603985
                // 0000631
                // 0000605


                // 处理每一行
                if (StringUtils.hasText(line)) {


                    // 0-深；1-沪；2-北；
                    String marketType = line.substring(0, 1);

                    // 个股code
                    String stockCode = line.substring(1, 5);


                    save2DB(stockCode);


                    System.out.println(line);
                }
            }


        } catch (IOException e) {

            log.error("BlockNewParser#parse   err     >>>     filePath : {},   errMsg : {}", filePath, e.getMessage(), e);
        }
    }


    /**
     * 股票池子   -   write
     *
     * @param stockCodeList
     */
    public static void write(List<Object> stockCodeList) {

        try {
            FileUtils.writeLines(new File(filePath), "UTF-8", stockCodeList, true);

        } catch (IOException e) {

            log.error("BlockNewParser#write   err     >>>     filePath : {},   errMsg : {}", filePath, e.getMessage(), e);
        }
    }


    private static void save2DB(String stockCode) {


        // TODO   save2DB


    }

}