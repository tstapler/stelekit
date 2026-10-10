package dev.stapler.stelekit.query

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryParserTest {
    private fun ok(raw: String): SimpleQuery {
        val r = QueryParser.parse(raw)
        assertTrue(r is Either.Right, "expected Right for '$raw' but got $r")
        return r.value
    }

    private fun err(raw: String): DomainError.ParseError {
        val r = QueryParser.parse(raw)
        assertTrue(r is Either.Left, "expected Left for '$raw' but got $r")
        return r.value
    }

    @Test fun `task filter parses with case-folded canonical markers`() =
        assertEquals(QueryFilter.Task(setOf("NOW", "LATER")), ok("(task now later)"))

    @Test fun `todo alias parses to Task`() =
        assertEquals(QueryFilter.Task(setOf("TODO")), ok("(todo TODO)"))

    @Test fun `page-property with quoted value`() =
        assertEquals(QueryFilter.PageProperty("type", "book"), ok("(page-property type \"book\")"))

    @Test fun `page-property with bareword value`() =
        assertEquals(QueryFilter.PageProperty("type", "book"), ok("(page-property type book)"))

    @Test fun `between parses journal page pair`() =
        assertEquals(
            QueryFilter.Between("Dec 5th, 2020", "Dec 7th, 2020"),
            ok("(between [[Dec 5th, 2020]] [[Dec 7th, 2020]])"),
        )

    @Test fun `bare page ref and tag parse to PageRef`() {
        assertEquals(QueryFilter.PageRef("ProjectX"), ok("[[ProjectX]]"))
        assertEquals(QueryFilter.PageRef("tag"), ok("#tag"))
    }

    @Test fun `and combinator parses two PageRef operands one level deep`() =
        assertEquals(And(QueryFilter.PageRef("tag1"), QueryFilter.PageRef("tag2")), ok("(and [[tag1]] [[tag2]])"))

    @Test fun `or combinator parses`() =
        assertEquals(Or(QueryFilter.PageRef("a"), QueryFilter.Task(setOf("TODO"))), ok("(or [[a]] (task todo))"))

    @Test fun `not is accepted as an and operand`() =
        assertEquals(
            And(QueryFilter.PageRef("tag2"), Not(QueryFilter.PageRef("tag1"))),
            ok("(and [[tag2]] (not [[tag1]]))"),
        )

    @Test fun `unknown head symbol returns InvalidSyntax`() {
        assertIs<DomainError.ParseError.InvalidSyntax>(err("(frobnicate xyz)"))
    }

    @Test fun `unbalanced parens and empty body return InvalidSyntax`() {
        assertIs<DomainError.ParseError.InvalidSyntax>(err("(task now"))
        assertIs<DomainError.ParseError.InvalidSyntax>(err(""))
        assertIs<DomainError.ParseError.InvalidSyntax>(err("   "))
    }

    @Test fun `two levels of and nesting returns UnsupportedForm`() {
        assertIs<DomainError.ParseError.UnsupportedForm>(err("(and (and [[a]] [[b]]) [[c]])"))
    }

    @Test fun `relative-date between returns UnsupportedForm`() {
        assertIs<DomainError.ParseError.UnsupportedForm>(err("(between -7d +7d)"))
    }

    @Test fun `top-level not returns UnsupportedForm`() {
        assertIs<DomainError.ParseError.UnsupportedForm>(err("(not [[a]])"))
    }

    @Test fun `not under or returns UnsupportedForm`() {
        assertIs<DomainError.ParseError.UnsupportedForm>(err("(or [[tag1]] (not [[tag2]]))"))
    }

    @Test fun `double negation returns UnsupportedForm`() {
        assertIs<DomainError.ParseError.UnsupportedForm>(err("(and [[a]] (not (not [[b]])))"))
    }

    @Test fun `garbage input never throws and returns InvalidSyntax`() {
        val garbage = listOf(
            "\u0000", "((((", "))))", "[[", "]]", "\"", "(\"", "(task \"now)", "(and", "()", "(())",
            "😀😀", "(task \u0000)", "((((((((((((((((((((task now))))))))))))))))))))",
            "(and (", "[[a]] [[b]]", "(page-property)", "(between)", "(between [[a]])", "#", "[[]]",
            "(task now) (task later)", "(task foo)",
        )
        for (g in garbage) {
            val e = err(g)
            assertTrue(
                e is DomainError.ParseError.InvalidSyntax || e is DomainError.ParseError.UnsupportedForm,
                "unexpected error type for '$g': $e",
            )
        }
    }
}
