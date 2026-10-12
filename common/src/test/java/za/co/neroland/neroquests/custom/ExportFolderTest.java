package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** J2 (RT-06): exports are timestamped templates outside datapacks/, in a non-custom namespace. */
class ExportFolderTest {

    @Test
    void exportFolderIsATimestampOutsideDatapacks() {
        assertEquals("20251009-084640", CustomPackIO.exportStamp(1_759_999_600_000L));
        assertEquals("neroquests_exports", CustomPackIO.EXPORTS_DIR);
        assertFalse(CustomPackIO.EXPORTS_DIR.contains("datapacks"));
        assertFalse("custom".equals(CustomPackIO.EXPORT_NAMESPACE));
    }
}
