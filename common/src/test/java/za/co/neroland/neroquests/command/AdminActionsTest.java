package za.co.neroland.neroquests.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** J1 (§13 operators): every admin mutation kind is counted under its own reason code, and only counted. */
class AdminActionsTest {

    @Test
    void eachKindHasItsOwnCounter() {
        int grants = AdminActions.count(AdminActions.Kind.GRANT);
        int edits = AdminActions.count(AdminActions.Kind.EDITOR_MUTATION);
        assertEquals(grants + 1, AdminActions.recordQuiet(AdminActions.Kind.GRANT));
        assertEquals(grants + 2, AdminActions.recordQuiet(AdminActions.Kind.GRANT));
        assertEquals(edits, AdminActions.count(AdminActions.Kind.EDITOR_MUTATION), "kinds never mix");
    }

    @Test
    void everyOperatorMutationHasAReasonCode() {
        Set<String> codes = Stream.of(AdminActions.Kind.values())
                .map(kind -> kind.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String required : new String[] {"grant", "revoke", "revoke_repay", "reset_player", "export",
                "manual_complete", "editor_mutation", "editor_allow", "editor_deny", "editor_lock", "editor_unlock",
                "custom_export", "custom_import", "custom_delete"}) {
            assertTrue(codes.contains(required), required);
        }
    }
}
