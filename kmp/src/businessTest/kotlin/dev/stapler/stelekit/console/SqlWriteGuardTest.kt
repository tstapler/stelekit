// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.commands.SqlWriteGuard
import dev.stapler.stelekit.console.commands.SqlWriteGuard.Verdict
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlWriteGuardTest {
    private fun allowed(sql: String) = assertIs<Verdict.Allowed>(SqlWriteGuard.check(sql), sql)
    private fun denied(sql: String) = assertIs<Verdict.Denied>(SqlWriteGuard.check(sql), sql)

    @Test
    fun check_should_ParseVerbTableAndFlags_When_PlainDml() {
        allowed("update pages set is_favorite=1 where name like 'Proj%'").let {
            assertEquals(SqlWriteGuard.Verb.Update, it.verb)
            assertEquals("pages", it.table)
            assertFalse(it.destructive)
            assertFalse(it.nondeterministic)
        }
        assertEquals("blocks", allowed("DELETE FROM blocks WHERE uuid = 'x';").table)
        assertTrue(allowed("delete from properties where key='a'").destructive)
        assertEquals("pages", allowed("insert into main.pages(uuid,name,created_at,updated_at) values ('u','n',1,1)").table)
        assertEquals("pages", allowed("INSERT OR IGNORE INTO \"pages\"(uuid) VALUES ('u')").table)
        assertEquals("properties", allowed("update or ignore `properties` set value='v'").table)
    }

    @Test
    fun check_should_FlagNondeterministicStatements_When_RandomOrNowUsed() {
        assertTrue(allowed("update pages set updated_at = random()").nondeterministic)
        assertTrue(allowed("update pages set journal_date = datetime('now')").nondeterministic)
        assertTrue(allowed("update pages set journal_date = current_timestamp").nondeterministic)
    }

    @Test
    fun check_should_DenyEveryHardDeniedStatement_When_Confirmed_Or_Not() {
        val statements = listOf(
            "create table t(x)", "drop table pages", "alter table pages add column z", "attach database '/tmp/x.db' as o",
            "vacuum", "vacuum into '/tmp/x.db'", "pragma writable_schema=1", "pragma query_only=0",
            "insert into blocks_fts(rowid, content) values (1, 'x')", "update pages_fts set name='x'",
            "delete from migration_changelog", "update metadata set value='1'", "update git_config set https_token_key='k'",
            "insert into sqlite_master values (1)", "update pages set name = load_extension('x')",
            "with c as (select 1) delete from pages", "select 1", "analyze", "reindex",
        )
        statements.forEach { denied(it) }
    }

    @Test
    fun check_should_DenyReplaceOnFtsBackedTables_When_StaleIndexRowsWouldResult() {
        denied("insert or replace into blocks(uuid,page_uuid,content,position,created_at,updated_at) values ('a','p','c','a0',1,1)")
        denied("replace into pages(uuid,name,created_at,updated_at) values ('u','n',1,1)")
        denied("update or replace blocks set content='x'")
        // Replace on a table with no full-text index is allowed and counted destructive.
        assertTrue(allowed("insert or replace into properties(uuid,block_uuid,key,value) values ('u','b','k','v')").destructive)
    }

    @Test
    fun check_should_DenySecondStatement_When_SemicolonOutsideQuotes() {
        denied("update pages set name='a'; delete from pages")
        denied("update pages set name='a';update pages set name='b';")
        allowed("update pages set name='a;b' where uuid='x'")
        allowed("update pages set name='a' ;  ")
    }

    @Test
    fun check_should_IgnoreComments_When_HidingStatementsOrVerbs() {
        allowed("/* hi */ update pages set name='a' -- trailing")
        denied("/* x */ drop table pages")
        denied("update pages set name='a' /* ; */ ; drop table pages")
    }

    @Test
    fun check_should_DenyWithReason_When_TargetTableCannotBeFound() {
        val v = denied("update (select 1) set a=1")
        assertTrue(v.reason.contains("table"), v.reason)
        denied("update temp.pages set name='a'")
        denied("")
    }

    @Test
    fun check_should_NeverAllowNonDmlVerbs_When_PropertyChecked() = runTest {
        val verbs = listOf("create", "drop", "alter", "attach", "detach", "vacuum", "pragma", "select", "with", "explain", "reindex", "begin", "commit")
        checkAll(Arb.element(verbs), Arb.string(0..40)) { verb, tail ->
            assertIs<Verdict.Denied>(SqlWriteGuard.check("$verb $tail"), "$verb $tail")
        }
    }

    @Test
    fun check_should_NeverAllowFtsOrBookkeepingTargets_When_PropertyChecked() = runTest {
        val targets = listOf("blocks_fts", "pages_fts", "git_config", "migration_changelog", "metadata", "sqlite_sequence", "blocks_fts_data")
        val shapes = listOf("insert into %s values (1)", "update %s set a=1", "delete from %s", "insert or ignore into %s(a) values (1)")
        checkAll(Arb.element(targets), Arb.element(shapes)) { table, shape ->
            val sql = shape.replace("%s", table)
            assertIs<Verdict.Denied>(SqlWriteGuard.check(sql), sql)
        }
    }
}
