package com.mumu.bot.kb;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 知识库文件 → 纯文本（P3，见 ADR-0007）。
 *
 * 支持 .docx / .xlsx（Apache POI）以及 .txt / .md（UTF-8 直读）。
 * 旧版 .doc / .xls 明确拒绝：POI 新格式 API 不覆盖，提示用 WPS/Office 另存为新格式。
 *
 * xlsx 纯文本布局：每个 sheet 一个「## sheet 名」标题行；行内单元格用 " | " 连接；
 * 全空行跳过；公式单元格取计算值。
 */
public final class KbTextExtractor {

    public static String extract(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".docx")) {
            return extractDocx(file);
        }
        if (name.endsWith(".xlsx")) {
            return extractXlsx(file);
        }
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        }
        if (name.endsWith(".doc") || name.endsWith(".xls")) {
            throw new IllegalArgumentException(
                "不支持旧版 Office 格式（" + file.getName() + "），请用 WPS/Office 另存为 .docx/.xlsx 后重试");
        }
        throw new IllegalArgumentException(
            "不支持的知识库文件类型：" + file.getName() + "（仅支持 .docx/.xlsx/.txt/.md）");
    }

    /** 支持的扩展名（小写、含点） */
    public static boolean isSupported(String fileName) {
        String n = fileName.toLowerCase();
        return n.endsWith(".docx") || n.endsWith(".xlsx")
            || n.endsWith(".txt") || n.endsWith(".md");
    }

    private static String extractDocx(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            // getText 已含正文段落与表格文本
            return ex.getText().trim();
        }
    }

    private static String extractXlsx(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream in = new FileInputStream(file);
             Workbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                StringBuilder body = new StringBuilder();
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    boolean hasContent = false;
                    int lastCol = row.getLastCellNum();
                    for (int ci = 0; ci < lastCol; ci++) {
                        Cell cell = row.getCell(ci);
                        String v = "";
                        if (cell != null) {
                            try {
                                v = fmt.formatCellValue(cell, evaluator).trim();
                            } catch (Exception formulaFail) {
                                v = fmt.formatCellValue(cell).trim();
                            }
                        }
                        if (!v.isEmpty()) hasContent = true;
                        if (line.length() > 0) line.append(" | ");
                        line.append(v);
                    }
                    if (hasContent) {
                        body.append(line.toString().stripTrailing()).append('\n');
                    }
                }
                if (body.length() > 0) {
                    sb.append("## ").append(sheet.getSheetName()).append('\n');
                    sb.append(body);
                }
            }
        }
        return sb.toString().trim();
    }

    private KbTextExtractor() {}
}
