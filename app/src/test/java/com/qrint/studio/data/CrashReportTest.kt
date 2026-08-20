package com.qrint.studio.data

import com.qrint.studio.ProductIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    private val report = CrashReport(
        timestamp = 1_786_527_600_000L,
        stage = "editor-entry:test\nignored-line",
        summary = "IllegalStateException: test failure",
        details = "thread=main\njava.lang.IllegalStateException: test failure\n\tat demo.Editor.open(Editor.kt:42)",
    )

    @Test fun markdownHasStructuredMetadataAndFencedStackTrace() {
        val markdown = report.asMarkdown()
        assertTrue(markdown.startsWith("# ${ProductIdentity.NAME}异常报告"))
        assertTrue(markdown.contains("## 完整诊断"))
        assertTrue(markdown.contains("````text"))
        assertTrue(markdown.contains("Editor.kt:42"))
        assertTrue(markdown.substringBefore("## 完整诊断").contains("- 异常阶段：editor-entry:test ignored-line\n"))
    }

    @Test fun suggestedNameIsMarkdownAndFilesystemSafe() {
        val name = report.markdownFileName()
        assertTrue(name.startsWith("${ProductIdentity.NAME}-异常报告-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains(':'))
    }
}
