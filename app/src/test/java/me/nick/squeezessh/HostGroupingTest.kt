package me.nick.squeezessh

import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.ui.hosts.HostGrouping
import me.nick.squeezessh.ui.hosts.HostSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGroupingTest {
    private fun host(name: String, group: String? = null, last: Long? = null, count: Int = 0) =
        HostEntity(id = name, nickname = name, address = "$name.example", group = group, lastConnectedAt = last, connectCount = count)

    private val hosts = listOf(
        host("zeta", "Work", last = 10, count = 1),
        host("alpha", "work ", last = 30, count = 5),
        host("home-nas", "Home", last = 20, count = 9),
        host("loose", null, last = 5, count = 2),
    )

    @Test
    fun groupingIsOffByDefaultSoTheListIsFlat() {
        val s = HostGrouping.sections(hosts, HostSort.Name, grouped = false, collapsed = emptySet(), searching = false)
        assertEquals(1, s.size)
        assertNull(s.single().title)
        assertEquals(listOf("alpha", "home-nas", "loose", "zeta"), s.single().hosts.map { it.nickname })
    }

    @Test
    fun groupsAreAlphabeticalWithUngroupedLastAndCaseInsensitive() {
        val s = HostGrouping.sections(hosts, HostSort.Name, grouped = true, collapsed = emptySet(), searching = false)
        assertEquals(listOf("Home", "work", HostGrouping.UNGROUPED), s.map { it.title })
        // "Work" and "work " are one group, titled from the host that is first by name ("alpha" -> "work").
        assertEquals(listOf("alpha", "zeta"), s.first { it.title == "work" }.hosts.map { it.nickname })
        // The title does not depend on the sort.
        val byCount = HostGrouping.sections(hosts, HostSort.Frequent, true, emptySet(), false)
        assertEquals(s.map { it.title }, byCount.map { it.title })
    }

    @Test
    fun sortAppliesWithinEachGroupIndependentlyOfGrouping() {
        val recent = HostGrouping.sections(hosts, HostSort.Recent, true, emptySet(), false)
        assertEquals(listOf("alpha", "zeta"), recent.first { it.title == "work" }.hosts.map { it.nickname })
        val frequent = HostGrouping.sections(hosts, HostSort.Frequent, true, emptySet(), false)
        assertEquals(listOf("alpha", "zeta"), frequent.first { it.title == "work" }.hosts.map { it.nickname })
        // Every sort keeps the same groups in the same order.
        assertEquals(recent.map { it.title }, frequent.map { it.title })
    }

    @Test
    fun collapsedGroupsAreMarkedButSearchingExpandsThem() {
        val collapsed = setOf("work")
        val plain = HostGrouping.sections(hosts, HostSort.Name, true, collapsed, searching = false)
        assertTrue(plain.first { it.title == "work" }.collapsed)
        assertFalse(plain.first { it.title == "Home" }.collapsed)
        val searching = HostGrouping.sections(hosts, HostSort.Name, true, collapsed, searching = true)
        assertFalse(searching.first { it.title == "work" }.collapsed)
    }

    @Test
    fun noGroupsAtAllStaysFlatEvenWhenGroupingIsOn() {
        val s = HostGrouping.sections(listOf(host("a"), host("b")), HostSort.Name, true, emptySet(), false)
        assertEquals(1, s.size)
        assertNull(s.single().title)
    }

    @Test
    fun blankGroupCountsAsUngrouped() {
        assertEquals("", HostGrouping.keyOf("   "))
        assertEquals("", HostGrouping.keyOf(null))
        assertEquals("work", HostGrouping.keyOf(" Work "))
    }
}
