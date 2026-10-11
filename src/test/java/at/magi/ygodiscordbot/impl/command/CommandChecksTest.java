package at.magi.ygodiscordbot.impl.command;

import net.dv8tion.jda.api.Permission;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class CommandChecksTest {

    @Test
    public void manageServerAndAdministratorMayManage() {
        assertTrue(CommandChecks.canManageServer(Permission.MANAGE_SERVER.getRawValue()));
        assertTrue(CommandChecks.canManageServer(Permission.ADMINISTRATOR.getRawValue()));
        assertTrue(CommandChecks.canManageServer(Permission.getRaw(Permission.MESSAGE_SEND, Permission.MANAGE_SERVER)));
    }

    @Test
    public void otherPermissionsMayNot() {
        assertFalse(CommandChecks.canManageServer(0L));
        assertFalse(CommandChecks.canManageServer(Permission.getRaw(Permission.MESSAGE_SEND, Permission.MANAGE_ROLES)));
    }

    @Test
    public void textsStayAsUsersKnowThem() {
        assertEquals(CommandChecks.BUSY, "Too many requests right now. Please try again in a moment.");
        assertEquals(CommandChecks.UNKNOWN_SUBCOMMAND, "Unknown subcommand.");
    }
}
