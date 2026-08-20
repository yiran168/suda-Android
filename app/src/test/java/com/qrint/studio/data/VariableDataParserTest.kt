package com.qrint.studio.data

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jxl.Workbook
import jxl.write.Label
import jxl.write.Number
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableDataParserTest {
    @Test fun csvSupportsQuotesNewLinesAndDuplicateHeaders() {
        val csv = "姓名,备注,姓名\n张三,\"第一行\n第二行\",A\n李四,普通,B"
        val table = VariableDataParser.parseDelimited("people.csv", csv)
        assertEquals(listOf("姓名", "备注", "姓名-2"), table.headers)
        assertEquals("第一行\n第二行", table.rows.first()["备注"])
        assertEquals(2, table.rows.size)
    }

    @Test fun xlsxSharedAndInlineStringsAreReadWithoutExternalLibrary() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("<sst><si><t>姓名</t></si><si><t>张三</t></si></sst>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(("<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"inlineStr\"><is><t>编号</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>1001</v></c></row>" +
                "</sheetData></worksheet>").toByteArray())
            zip.closeEntry()
        }
        val table = VariableDataParser.parse("wps.xlsx", ByteArrayInputStream(output.toByteArray()))
        assertEquals(listOf("姓名", "编号"), table.headers)
        assertEquals("张三", table.rows.single()["姓名"])
        assertEquals("1001", table.rows.single()["编号"])
    }

    @Test fun legacyXlsAndCompatibleWpsEtAreReadLocally() {
        val output = ByteArrayOutputStream()
        val workbook = Workbook.createWorkbook(output)
        val sheet = workbook.createSheet("数据", 0)
        sheet.addCell(Label(0, 0, "姓名"))
        sheet.addCell(Label(1, 0, "编号"))
        sheet.addCell(Label(0, 1, "张三"))
        sheet.addCell(Number(1, 1, 1001.0))
        workbook.write()
        workbook.close()

        listOf("legacy.xls", "wps-compatible.et").forEach { fileName ->
            val table = VariableDataParser.parse(fileName, ByteArrayInputStream(output.toByteArray()))
            assertEquals(listOf("姓名", "编号"), table.headers)
            assertEquals("张三", table.rows.single()["姓名"])
            assertEquals("1001", table.rows.single()["编号"])
        }
    }

    @Test fun xlsxSkipsEmptyLeadingSheetInsteadOfRejectingWorkbook() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<worksheet><sheetData/></worksheet>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            zip.write(("<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>货号</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>A-01</t></is></c></row>" +
                "</sheetData></worksheet>").toByteArray())
            zip.closeEntry()
        }
        val table = VariableDataParser.parse("multi-sheet.xlsx", ByteArrayInputStream(output.toByteArray()))
        assertEquals("A-01", table.rows.single()["货号"])
    }

    @Test fun xlsxExposesNamedWorksheetsForManualSelection() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            entry(
                "xl/workbook.xml",
                """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="订单" sheetId="1" r:id="rId1"/><sheet name="客户" sheetId="2" r:id="rId2"/></sheets></workbook>""",
            )
            entry(
                "xl/_rels/workbook.xml.rels",
                """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""",
            )
            entry(
                "xl/worksheets/sheet1.xml",
                """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>订单号</t></is></c></row><row><c r="A2" t="inlineStr"><is><t>O-01</t></is></c></row></sheetData></worksheet>""",
            )
            entry(
                "xl/worksheets/sheet2.xml",
                """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>姓名</t></is></c></row><row><c r="A2" t="inlineStr"><is><t>张三</t></is></c></row></sheetData></worksheet>""",
            )
        }

        val workbook = VariableDataParser.parseWorkbook("named.xlsx", ByteArrayInputStream(output.toByteArray()))

        assertEquals(listOf("订单", "客户"), workbook.sheets.map { it.sheetName })
        assertEquals("O-01", workbook.sheets[0].rows.single()["订单号"])
        assertEquals("张三", workbook.sheets[1].rows.single()["姓名"])
    }

    @Test fun xlsxRestoresCommonDateAndNumberFormats() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            entry(
                "xl/styles.xml",
                """<styleSheet><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="10"/></cellXfs></styleSheet>""",
            )
            entry(
                "xl/worksheets/sheet1.xml",
                """<worksheet><sheetData>
                    <row><c r="A1" t="inlineStr"><is><t>日期</t></is></c><c r="B1" t="inlineStr"><is><t>比例</t></is></c></row>
                    <row><c r="A2" s="1"><v>45292</v></c><c r="B2" s="2"><v>0.125</v></c></row>
                </sheetData></worksheet>""",
            )
        }

        val table = VariableDataParser.parse("formatted.xlsx", ByteArrayInputStream(output.toByteArray()))

        assertEquals("2024-01-01", table.rows.single()["日期"])
        assertEquals("12.50%", table.rows.single()["比例"])
    }

    @Test fun placeholdersResolveAcrossPrintableFields() {
        val source = LabelDocument(elements = listOf(
            LabelElement(kind = ElementKind.TEXT, text = "姓名：{{姓名}}"),
            LabelElement(kind = ElementKind.BARCODE, barcodeContent = "{{编号}}"),
        ))
        val resolved = source.resolveVariables(mapOf("姓名" to "张三", "编号" to "A-01"))
        assertEquals("姓名：张三", resolved.elements[0].text)
        assertEquals("A-01", resolved.elements[1].barcodeContent)
        assertTrue(source.variableFields().containsAll(listOf("姓名", "编号")))
    }

    @Test fun placeholdersAllowWhitespaceAndNeverInterpretMalformedBraces() {
        val source = "{{ 姓名 }} / {{编号}} / {{ 未闭合 / {{嵌{{套}}"
        val resolved = source.resolveVariables(mapOf("姓名" to "张三", "编号" to "A-01", "套" to "错误"))

        assertEquals("张三 / A-01 / {{ 未闭合 / {{嵌{{套}}", resolved)
    }

    @Test fun missingVariablesRemainEditableTokens() {
        assertEquals("订单 {{订单号}}", "订单 {{订单号}}".resolveVariables(emptyMap()))
    }

    @Test fun batchRangeIsClampedAndKeepsSourceOrder() {
        val table = VariableDataTable(
            sourceName = "range.csv",
            headers = listOf("编号"),
            rows = (1..5).map { mapOf("编号" to it.toString()) },
        )
        assertEquals(1..4, table.normalizeRange(1..99))
        assertEquals(listOf("2", "3", "4", "5"), table.rowsIn(1..99).map { it.getValue("编号") })
        assertEquals(4..4, table.normalizeRange(99..1))
    }
}
