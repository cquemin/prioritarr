package org.yoshiz.app.prioritarr.backend.pagination

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.yoshiz.app.prioritarr.backend.errors.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaginationTest {

    private fun params(query: String) = run {
        var captured: PageParams? = null
        testApplication {
            application {
                routing {
                    get("/x") {
                        captured = pageParamsFrom(call, allowedSorts = setOf("a", "b"), defaultSort = "a")
                        call.respondText("ok")
                    }
                }
            }
            client.get("/x$query")
        }
        captured
    }

    @Test
    fun `defaults when nothing supplied`() {
        val p = params("")!!
        assertEquals(0, p.offset)
        assertEquals(50, p.limit)
        assertEquals("a", p.sort)
        assertEquals(SortDir.ASC, p.sortDir)
    }

    @Test
    fun `custom offset and limit`() {
        val p = params("?offset=100&limit=200")!!
        assertEquals(100, p.offset)
        assertEquals(200, p.limit)
    }

    @Test
    fun `sort_dir desc recognised`() {
        assertEquals(SortDir.DESC, params("?sort_dir=desc")!!.sortDir)
        assertEquals(SortDir.DESC, params("?sort_dir=DESC")!!.sortDir)
    }

    @Test
    fun `limit over max throws validation`() {
        assertFailsWith<ValidationException> {
            pageParamsFrom(
                callFor("?limit=1001"),
                allowedSorts = setOf("a"), defaultSort = "a",
            )
        }
    }

    @Test
    fun `unknown sort throws validation`() {
        assertFailsWith<ValidationException> {
            pageParamsFrom(
                callFor("?sort=z"),
                allowedSorts = setOf("a", "b"), defaultSort = "a",
            )
        }
    }

    @Test
    fun `paginate slices correctly`() {
        val env = paginate((1..10).toList(), PageParams(3, 4, "a", SortDir.ASC))
        assertEquals(listOf(4, 5, 6, 7), env.records)
        assertEquals(10, env.totalRecords)
        assertEquals(3, env.offset)
        assertEquals(4, env.limit)
    }

    // ---- harness to call pageParamsFrom without a real Ktor server ----
    private fun callFor(queryString: String): io.ktor.server.application.ApplicationCall {
        // testApplication is heavy for simple query-string tests; we use a small
        // fake. Only the pieces pageParamsFrom touches are exercised.
        var captured: io.ktor.server.application.ApplicationCall? = null
        testApplication {
            application {
                routing {
                    get("/probe") {
                        captured = call
                        call.respondText("ok", status = HttpStatusCode.OK)
                    }
                }
            }
            client.get("/probe$queryString")
        }
        return captured!!
    }
}
