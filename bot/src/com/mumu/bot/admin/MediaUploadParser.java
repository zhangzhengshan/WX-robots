package com.mumu.bot.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * multipart/form-data 原生解析（零新 jar，JDK 自带）。
 *
 * 仅支持单文件 + 若干普通字段（符合 P5 知识库/媒体上传场景）。
 * 不支持多文件同字段、不支持 charset 头参数。
 *
 * 用法：
 *   String ct = exchange.getRequestHeaders().getFirst("Content-Type");
 *   if (ct != null && ct.startsWith("multipart/form-data")) {
 *       String boundary = "--" + ct.substring(ct.indexOf("boundary=") + 9).trim();
 *       MediaUploadParser.ParseResult r = MediaUploadParser.parse(exchange.getRequestBody(), boundary);
 *       File file = r.files.get("file");  // 字段名固定为 file
 *       String name = r.fields.get("name");
 *   }
 */
public final class MediaUploadParser {

    public static final class ParseResult {
        /** 普通字段名 -> 值 */
        public final Map<String, String> fields = new LinkedHashMap<>();
        /** 文件字段名 -> 上传文件 */
        public final Map<String, UploadedFile> files = new LinkedHashMap<>();
    }

    public static final class UploadedFile {
        public final String filename;
        public final String contentType;
        public final byte[] data;
        public UploadedFile(String filename, String contentType, byte[] data) {
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }

    /**
     * 解析 multipart/form-data 请求体。
     * @param in 请求输入流
     * @param boundary 边界字符串（含前导 --）
     */
    public static ParseResult parse(InputStream in, String boundary) throws IOException {
        ParseResult result = new ParseResult();
        byte[] body = readAll(in);
        byte[] delim = (boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] finalDelim = (boundary + "--").getBytes(StandardCharsets.US_ASCII);

        int start = 0;
        while (true) {
            int partStart = indexOf(body, delim, start);
            if (partStart < 0) break;
            partStart += delim.length;

            int partEnd = indexOf(body, delim, partStart);
            int finalEnd = indexOf(body, finalDelim, partStart);
            int end = (partEnd < 0) ? finalEnd : partEnd;
            if (end < 0) break;

            // part 内容 = body[partStart .. end-2]（去末尾 \r\n）
            int contentLen = end - partStart - 2;  // -2 去末尾 \r\n
            if (contentLen <= 0) { start = end; continue; }

            parsePart(body, partStart, contentLen, result);
            start = end;
        }
        return result;
    }

    private static void parsePart(byte[] body, int off, int len, ParseResult result) {
        // 头部与正文分隔：\r\n\r\n
        int sep = indexOf(body, CRLFCRLF, off, off + len);
        if (sep < 0) return;
        String header = new String(body, off, sep - off, StandardCharsets.UTF_8);
        int bodyStart = sep + 4;  // 跳过 \r\n\r\n
        int bodyLen = (off + len) - bodyStart;
        if (bodyLen < 0) return;

        // 解析 Content-Disposition
        String name = extractHeader(header, "name=\"", "\"");
        if (name == null) return;
        String filename = extractHeader(header, "filename=\"", "\"");
        String contentType = extractHeader(header, "Content-Type: ", "\r\n");

        if (filename == null) {
            // 普通字段
            String value = new String(body, bodyStart, bodyLen, StandardCharsets.UTF_8);
            result.fields.put(name, value);
        } else {
            // 文件字段
            byte[] data = new byte[bodyLen];
            System.arraycopy(body, bodyStart, data, 0, bodyLen);
            result.files.put(name, new UploadedFile(filename, contentType, data));
        }
    }

    private static String extractHeader(String header, String prefix, String suffix) {
        int i = header.indexOf(prefix);
        if (i < 0) return null;
        int j = header.indexOf(suffix, i + prefix.length());
        if (j < 0) return null;
        return header.substring(i + prefix.length(), j);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static final byte[] CRLFCRLF = { '\r', '\n', '\r', '\n' };

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        return indexOf(hay, needle, from, hay.length);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from, int to) {
        outer: for (int i = from; i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private MediaUploadParser() {}
}
