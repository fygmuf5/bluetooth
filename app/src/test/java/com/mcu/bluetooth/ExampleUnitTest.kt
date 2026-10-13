package com.mcu.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExampleUnitTest {

    @Test
    fun testColRefToIndex() {
        assertEquals(0, CourseScheduleParser.colRefToIndex("A"))
        assertEquals(0, CourseScheduleParser.colRefToIndex("A1"))
        assertEquals(1, CourseScheduleParser.colRefToIndex("B12"))
        assertEquals(2, CourseScheduleParser.colRefToIndex("C99"))
        assertEquals(25, CourseScheduleParser.colRefToIndex("Z5"))
        assertEquals(26, CourseScheduleParser.colRefToIndex("AA1"))
        assertEquals(27, CourseScheduleParser.colRefToIndex("AB20"))
        assertEquals(-1, CourseScheduleParser.colRefToIndex("123"))
        assertEquals(-1, CourseScheduleParser.colRefToIndex(""))
    }

    @Test
    fun testCleanNumberString() {
        assertEquals("101", CourseScheduleParser.cleanNumberString("101.0"))
        assertEquals("0", CourseScheduleParser.cleanNumberString("0.0"))
        assertEquals("-5", CourseScheduleParser.cleanNumberString("-5.0"))
        assertEquals("F609", CourseScheduleParser.cleanNumberString("F609"))
        assertEquals("人工智慧", CourseScheduleParser.cleanNumberString("\"人工智慧\""))
    }

    @Test
    fun testCsvStandardParsing() {
        val csv = """
            節次,課程名稱,教室
            週二 05-07,人工智慧,F609
            週三 02-04,計算機概論,S210
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val schedule = CourseScheduleParser.processRowsToSchedule(rows)

        assertEquals(2, schedule.size)
        assertEquals(ScheduleItem("週二 05-07", "人工智慧", "F609"), schedule[0])
        assertEquals(ScheduleItem("週三 02-04", "計算機概論", "S210"), schedule[1])
    }

    @Test
    fun testCsvSchoolHeadersAndReversedCols() {
        // School table with many columns
        val csv = """
            開課代碼,學分,科目名稱,授課教師,上課時間,上課地點
            CS101,3,人工智慧,林老師,週二 05-07,F609
            EE201,3,計算機概論,王老師,週三 02-04,S210
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val schedule = CourseScheduleParser.processRowsToSchedule(rows)

        assertEquals(2, schedule.size)
        assertEquals(ScheduleItem("週二 05-07", "人工智慧", "F609"), schedule[0])
        assertEquals(ScheduleItem("週三 02-04", "計算機概論", "S210"), schedule[1])
    }

    @Test
    fun testCsvWithQuotedFieldsAndCommas() {
        val csv = """
            "節次","課程名稱","教室"
            "週二 05-07","人工智慧, 進階版","F609"
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val schedule = CourseScheduleParser.processRowsToSchedule(rows)

        assertEquals(1, schedule.size)
        assertEquals(ScheduleItem("週二 05-07", "人工智慧, 進階版", "F609"), schedule[0])
    }

    @Test
    fun testCsvMatrixFormat() {
        val csv = """
            節次,星期一,星期二,星期三,星期四,星期五
            02-04,,,計算機概論 (S210),模型 (英) (S401),
            05-07,,人工智慧 (F609),,,
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val schedule = CourseScheduleParser.processRowsToSchedule(rows)
        println("Matrix schedule: $schedule")

        assertEquals(3, schedule.size)
        assertTrue(schedule.any { it.period == "週三 02-04" && it.courseName == "計算機概論" && it.location == "S210" })
        assertTrue(schedule.any { it.period == "週四 02-04" && it.courseName == "模型 (英)" && it.location == "S401" })
        assertTrue(schedule.any { it.period == "週二 05-07" && it.courseName == "人工智慧" && it.location == "F609" })
    }

    @Test
    fun testXlsxWithSharedStringsParsing() {
        // Build a mock XLSX file in memory
        val tempFile = File.createTempFile("test_schedule_", ".xlsx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                // Shared strings
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                val sstXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="6" uniqueCount="6">
                        <si><t>節次</t></si>
                        <si><t>課程名稱</t></si>
                        <si><t>教室</t></si>
                        <si><t>週二 05-07</t></si>
                        <si><t>人工智慧</t></si>
                        <si><t>F609</t></si>
                    </sst>
                """.trimIndent()
                zos.write(sstXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Worksheet
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val sheetXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <sheetData>
                            <row r="1">
                                <c r="A1" t="s"><v>0</v></c>
                                <c r="B1" t="s"><v>1</v></c>
                                <c r="C1" t="s"><v>2</v></c>
                            </row>
                            <row r="2">
                                <c r="A2" t="s"><v>3</v></c>
                                <c r="B2" t="s"><v>4</v></c>
                                <c r="C2" t="s"><v>5</v></c>
                            </row>
                        </sheetData>
                    </worksheet>
                """.trimIndent()
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val rows = CourseScheduleParser.parseXlsxFile(tempFile)
            val schedule = CourseScheduleParser.processRowsToSchedule(rows)

            assertEquals(1, schedule.size)
            assertEquals(ScheduleItem("週二 05-07", "人工智慧", "F609"), schedule[0])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testXlsxWithInlineStringsParsing() {
        val tempFile = File.createTempFile("test_inline_", ".xlsx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val sheetXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <sheetData>
                            <row r="1">
                                <c r="A1" t="inlineStr"><is><t>週五 06-08</t></is></c>
                                <c r="B1" t="inlineStr"><is><t>電資探索</t></is></c>
                                <c r="C1" t="inlineStr"><is><t>S105</t></is></c>
                            </row>
                        </sheetData>
                    </worksheet>
                """.trimIndent()
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val rows = CourseScheduleParser.parseXlsxFile(tempFile)
            val schedule = CourseScheduleParser.processRowsToSchedule(rows)

            assertEquals(1, schedule.size)
            assertEquals(ScheduleItem("週五 06-08", "電資探索", "S105"), schedule[0])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testStudentRosterWithHeaderAndIgnoredLastCol() {
        val csv = """
            名字,姓氏,編號,最後一欄未知資訊
            大明,王,12360615,ABC
            小華,陳,12360596,XYZ
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        assertTrue(CourseScheduleParser.isStudentRoster(rows))

        val students = CourseScheduleParser.parseStudentRosterRows(rows)
        assertEquals(2, students.size)
        assertEquals(StudentItem(id = "12360615", name = "王大明", firstName = "大明", lastName = "王"), students[0])
        assertEquals(StudentItem(id = "12360596", name = "陳小華", firstName = "小華", lastName = "陳"), students[1])
    }

    @Test
    fun testStudentRosterWithoutHeaderAndIgnoredLastCol() {
        // 沒有標題列，直接為 "名字"、"姓氏"、"編號"、"末欄未知"
        val csv = """
            大明,王,12360615,100
            美美,林,12360651,95
        """.trimIndent()

        val rows = CourseScheduleParser.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        assertTrue(CourseScheduleParser.isStudentRoster(rows))

        val students = CourseScheduleParser.parseStudentRosterRows(rows)
        assertEquals(2, students.size)
        assertEquals(StudentItem(id = "12360615", name = "王大明", firstName = "大明", lastName = "王"), students[0])
        assertEquals(StudentItem(id = "12360651", name = "林美美", firstName = "美美", lastName = "林"), students[1])
    }

    @Test
    fun testStudentRosterInXlsx() {
        val tempFile = File.createTempFile("test_student_roster_", ".xlsx")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                val sstXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="8" uniqueCount="8">
                        <si><t>名字</t></si>
                        <si><t>姓氏</t></si>
                        <si><t>編號</t></si>
                        <si><t>未知名稱</t></si>
                        <si><t>冠宇</t></si>
                        <si><t>張</t></si>
                        <si><t>12363033</t></si>
                        <si><t>已選課</t></si>
                    </sst>
                """.trimIndent()
                zos.write(sstXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val sheetXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <sheetData>
                            <row r="1">
                                <c r="A1" t="s"><v>0</v></c>
                                <c r="B1" t="s"><v>1</v></c>
                                <c r="C1" t="s"><v>2</v></c>
                                <c r="D1" t="s"><v>3</v></c>
                            </row>
                            <row r="2">
                                <c r="A2" t="s"><v>4</v></c>
                                <c r="B2" t="s"><v>5</v></c>
                                <c r="C2" t="s"><v>6</v></c>
                                <c r="D2" t="s"><v>7</v></c>
                            </row>
                        </sheetData>
                    </worksheet>
                """.trimIndent()
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val rows = CourseScheduleParser.parseXlsxFile(tempFile)
            assertTrue(CourseScheduleParser.isStudentRoster(rows))

            val students = CourseScheduleParser.parseStudentRosterRows(rows)
            assertEquals(1, students.size)
            assertEquals("12363033", students[0].id)
            assertEquals("張冠宇", students[0].name)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCourseNameExtractionAndMatching() {
        assertEquals("人工智慧", CourseScheduleParser.extractCourseNameFromFileName("人工智慧.xlsx"))
        assertEquals("計算機概論", CourseScheduleParser.extractCourseNameFromFileName("計算機概論_學生名單.xlsx"))
        assertEquals("電資探索", CourseScheduleParser.extractCourseNameFromFileName("電資探索_名單.csv"))

        val courses = listOf(
            ScheduleItem("週二 05-07", "人工智慧", "F609"),
            ScheduleItem("週三 02-04", "計算機概論", "S210")
        )

        val matched = CourseScheduleParser.findMatchingCourse(courses, "人工智慧")
        assertNotNull(matched)
        assertEquals("人工智慧", matched?.courseName)

        val matchedWithLoc = CourseScheduleParser.findMatchingCourse(courses, "人工智慧 (F609)")
        assertNotNull(matchedWithLoc)
        assertEquals("人工智慧", matchedWithLoc?.courseName)
    }

    @Test
    fun testFormatStudentName() {
        assertEquals("王大明", CourseScheduleParser.formatStudentName("大明", "王"))
        assertEquals("John Doe", CourseScheduleParser.formatStudentName("John", "Doe"))
        assertEquals("小明", CourseScheduleParser.formatStudentName("小明", ""))
        assertEquals("陳", CourseScheduleParser.formatStudentName("", "陳"))
    }
}