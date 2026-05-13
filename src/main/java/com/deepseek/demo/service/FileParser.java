package com.deepseek.demo.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileParser {

    private static final Logger log = LoggerFactory.getLogger(FileParser.class);

    /** 根据文件扩展名自动选择解析方式
     * @param path 文件绝对路径
     * @return 提取的纯文本内容
     * @throws IOException 文件读取失败或格式不支持 */
    public String extractText(Path path) throws IOException {
        String name = path.toString().toLowerCase();
        if (name.endsWith(".docx")) {
            return extractDocx(path);
        } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return extractExcel(path);
        } else if (name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".csv") || name.endsWith(".json")
                || name.endsWith(".xml") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".properties") || name.endsWith(".html") || name.endsWith(".css")) {
            return Files.readString(path);
        }
        throw new IOException("不支持的文件格式: " + path);
    }

    /** 解析 .docx 文件为纯文本（使用 Apache POI）
     * @param path Word 文件路径
     * @return 段落文本 */
    private String extractDocx(Path path) throws IOException {
        log.debug("解析Word: {}", path);
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(path));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /** 解析 .xlsx/.xls 文件为制表符分隔文本（使用 Apache POI）
     * 逐 sheet 逐行逐列读取，多 sheet 间用分隔线标记
     * @param path Excel 文件路径
     * @return 所有 sheet 的表格文本 */
    private String extractExcel(Path path) throws IOException {
        log.debug("解析Excel: {}", path);
        try (InputStream is = Files.newInputStream(path);
             Workbook wb = new XSSFWorkbook(is)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                if (wb.getNumberOfSheets() > 1) {
                    sb.append("===== Sheet: ").append(sheetName).append(" =====\n\n");
                }
                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            String val = getCellValue(cell);
                            if (!val.isEmpty()) {
                                sb.append(val).append("\t");
                            }
                        }
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
            return sb.toString().strip();
        }
    }

    /** 读取 Excel 单元格中的文本值，自动处理 STRING/NUMERIC/BOOLEAN/FORMULA 类型
     * @param cell POI Cell 对象
     * @return 单元格文本 */
    private String getCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().strip();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return "";
        }
    }
}
