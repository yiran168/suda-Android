package com.qrint.studio.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchPrintCursorTest {
    @Test fun partialProtocolBatchResumesAtFirstUnconfirmedCopy() {
        assertEquals(BatchResumeCursor(0, 0), failedBatchCursor(0, firstCopy = 0, completedCopies = 0, copiesPerRecord = 5))
        assertEquals(BatchResumeCursor(0, 2), failedBatchCursor(0, firstCopy = 0, completedCopies = 2, copiesPerRecord = 5))
        assertEquals(BatchResumeCursor(2, 3), failedBatchCursor(2, firstCopy = 2, completedCopies = 1, copiesPerRecord = 5))
        assertEquals(BatchResumeCursor(2, 4), failedBatchCursor(2, firstCopy = 4, completedCopies = 8, copiesPerRecord = 5))
    }

    @Test fun skippingFailureAdvancesToNextRecordAndNeverRepeatsPartialCopies() {
        assertEquals(BatchResumeCursor(2, 0), nextRecordCursor(1, 4))
        assertNull(nextRecordCursor(3, 4))
    }

    @Test fun immediatePauseKeepsTheUnconfirmedCopyAndItsRasterRowCheckpoint() {
        assertEquals(
            BatchResumeCursor(1, 2, rowOffset = 384),
            failedBatchCursor(
                recordIndex = 1,
                firstCopy = 0,
                completedCopies = 2,
                copiesPerRecord = 5,
                resumeRowOffset = 384,
            ),
        )
    }
}
