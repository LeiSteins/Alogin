package top.steins.autologin.data

import org.junit.Assert.*
import org.junit.Test

class SavedAccountsTest {
    @Test
    fun saveAndSwitch_keepEachAccountsPassword() {
        val saved = SavedAccounts().save(" alice ", " secret A ").save("bob", "secret B")
        assertEquals("bob", saved.activeUsername)
        assertEquals(" secret A ", saved.select("alice").active?.password)
        assertEquals(2, saved.accounts.size)
    }

    @Test
    fun updatingExistingAccount_preservesOrderAndOtherPasswords() {
        val saved = SavedAccounts().save("alice", "old").save("bob", "other")
            .save("alice", "new")
        assertEquals(listOf("alice", "bob"), saved.accounts.map { it.username })
        assertEquals("new", saved.active?.password)
        assertEquals("other", saved.select("bob").active?.password)
    }

    @Test
    fun removingActiveAccount_selectsRemainingAndClearsLastCredentials() {
        val saved = SavedAccounts().save("alice", "a").save("bob", "b")
        val remaining = saved.remove("bob")
        assertEquals("alice", remaining.activeUsername)
        assertEquals("a", remaining.active?.password)
        val empty = remaining.remove("alice")
        assertTrue(empty.accounts.isEmpty())
        assertEquals("", empty.activeUsername)
        assertNull(empty.active)
        assertEquals(empty, SavedAccountsCodec.decode(SavedAccountsCodec.encode(empty)))
    }

    @Test
    fun removingInactiveAccount_doesNotChangeSelection() {
        val saved = SavedAccounts().save("alice", "a").save("bob", "b").remove("alice")
        assertEquals("bob", saved.activeUsername)
    }

    @Test
    fun codec_roundTripsUnicodeAndSpecialCharactersWithoutChangingPasswords() {
        val saved = SavedAccounts().save("账号😀", " \"\n密码:| ").save("bob", "b")
        assertEquals(saved, SavedAccountsCodec.decode(SavedAccountsCodec.encode(saved)))
    }

    @Test
    fun codec_rejectsCorruptOrInconsistentData() {
        assertNull(SavedAccountsCodec.decode("broken"))
        assertNull(SavedAccountsCodec.decode("""{"version":2,"accounts":[],"active":""}"""))
        assertNull(SavedAccountsCodec.decode("""{"version":1,"accounts":[],"active":"missing"}"""))
        assertNull(SavedAccountsCodec.decode("""{"version":1,"accounts":[
            {"username":"a","password":"a"},{"username":"a","password":"b"}
        ],"active":"a"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCredentials_areRejected() {
        SavedAccounts().save("alice", "  ")
    }
}
