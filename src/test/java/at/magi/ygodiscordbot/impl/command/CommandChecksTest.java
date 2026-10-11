package at.magi.ygodiscordbot.impl.command;

import at.magi.ygodiscordbot.testutil.FakeInteractions;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;

import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
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

    @DataProvider
    public Object[][] memberPermissions() {
        return new Object[][]{
                {"no permissions", 0L},
                {"Manage Server", Permission.MANAGE_SERVER.getRawValue()},
                {"Administrator", Permission.ADMINISTRATOR.getRawValue()},
                {"Send Messages and Manage Roles", Permission.getRaw(Permission.MESSAGE_SEND, Permission.MANAGE_ROLES)},
        };
    }

    @Test(dataProvider = "memberPermissions")
    public void eventCheckAgreesWithThePermissionBits(String name, long raw) {
        // Like JDA's PermissionUtil: Administrator grants every permission
        Member member = FakeInteractions.fake(Member.class, Map.of("hasPermission", (method, args) -> {
            long wanted = Permission.getRaw((Permission[]) args[0]);
            return (raw & Permission.ADMINISTRATOR.getRawValue()) != 0 || (raw & wanted) == wanted;
        }));
        var event = new FakeInteractions().event(Map.of("getMember", returns(member)));
        assertEquals(CommandChecks.canManageServer(event), CommandChecks.canManageServer(raw), name);
    }

    @Test
    public void noOneManagesTheServerInTheBotDm() {
        assertFalse(CommandChecks.canManageServer(new FakeInteractions().event(Map.of())));
    }

    @Test
    public void textsStayAsUsersKnowThem() {
        assertEquals(CommandChecks.BUSY, "Too many requests right now. Please try again in a moment.");
        assertEquals(CommandChecks.UNKNOWN_SUBCOMMAND, "Unknown subcommand.");
    }
}
