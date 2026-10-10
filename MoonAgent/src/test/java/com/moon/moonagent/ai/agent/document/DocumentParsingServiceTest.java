package com.moon.moonagent.ai.agent.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParsingServiceTest {

    @Test
    void parsesMarkdownAndPreservesHeadingsAndTables() {
        DocumentParsingService service = new DocumentParsingService(new DocumentProperties());

        ParsedDocument document = service.parse(
                """
                # 项目说明

                | 镜头 | 时长 |
                | --- | --- |
                | 1 | 3s |
                """.getBytes(StandardCharsets.UTF_8),
                "brief.md");

        assertThat(document.markdown()).contains("# 项目说明");
        assertThat(document.markdown()).contains("|镜头|时长|");
        assertThat(document.mimeType()).isEqualTo("text/markdown");
        assertThat(document.truncated()).isFalse();
    }

    @Test
    void parsesCommonTextFormats() {
        DocumentParsingService service = new DocumentParsingService(new DocumentProperties());

        assertThat(service.parse("普通文本内容".getBytes(StandardCharsets.UTF_8), "note.txt")
                .markdown()).contains("普通文本内容");
        assertThat(service.parse("日志第一行\n日志第二行".getBytes(StandardCharsets.UTF_8), "run.log")
                .markdown()).contains("日志第二行");
        assertThat(service.parse("镜头,时长\n1,3s".getBytes(StandardCharsets.UTF_8), "shots.csv")
                .markdown()).contains("镜头");
        assertThat(service.parse("{\"标题\":\"计划\"}".getBytes(StandardCharsets.UTF_8), "data.json")
                .markdown()).contains("计划");
        assertThat(service.parse("<root><title>计划</title></root>".getBytes(StandardCharsets.UTF_8), "data.xml")
                .markdown()).contains("计划");
        assertThat(service.parse("title: 计划".getBytes(StandardCharsets.UTF_8), "data.yaml")
                .markdown()).contains("计划");
        assertThat(service.parse("<h1>计划</h1>".getBytes(StandardCharsets.UTF_8), "page.html")
                .markdown()).contains("计划");
    }

    @Test
    void parsesWordExcelPowerPointAndPdf() throws Exception {
        DocumentParsingService service = new DocumentParsingService(new DocumentProperties());

        assertThat(service.parse(docx(), "brief.docx").markdown()).contains("Word 正文");
        assertThat(service.parse(xlsx(), "data.xlsx").markdown()).contains("镜头");
        assertThat(service.parse(pptx(), "deck.pptx").markdown()).contains("第一页");
        assertThat(service.parse(pdf(), "script.pdf").markdown()).contains("Hello PDF");
    }

    @Test
    void reportsNoTextForImageOnlyPdf() throws Exception {
        DocumentParsingService service = new DocumentParsingService(new DocumentProperties());

        assertThatThrownBy(() -> service.parse(emptyPdf(), "scan.pdf"))
                .isInstanceOf(DocumentParseException.class)
                .extracting("code")
                .isEqualTo("no_text");
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() {
        DocumentParsingService service = new DocumentParsingService(new DocumentProperties());

        assertThatThrownBy(() -> service.parse(
                "<html><body>not markdown</body></html>".getBytes(StandardCharsets.UTF_8),
                "fake.md"))
                .isInstanceOf(DocumentParseException.class)
                .extracting("code")
                .isEqualTo("unsupported");
    }

    @Test
    void marksOutputTruncatedAtConfiguredLimit() {
        DocumentProperties properties = new DocumentProperties();
        properties.setMaxParseChars(20);
        DocumentParsingService service = new DocumentParsingService(properties);

        ParsedDocument document = service.parse(
                ("# 标题\n\n" + "内容".repeat(50)).getBytes(StandardCharsets.UTF_8),
                "brief.md");

        assertThat(document.truncated()).isTrue();
        assertThat(document.markdown()).hasSize(20);
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Word 正文");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("分镜");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("镜头");
            row.createCell(1).setCellValue("时长");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = slideShow.createSlide();
            slide.createTextBox().setText("第一页");
            slideShow.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Hello PDF");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] emptyPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
