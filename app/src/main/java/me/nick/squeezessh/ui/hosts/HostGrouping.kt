package me.nick.squeezessh.ui.hosts

import me.nick.squeezessh.data.HostEntity

/** One block of the host list: a named group, or the whole flat list when grouping is off. */
data class HostSection(
    /** The group's display name, or null for the flat list. */
    val title: String?,
    /** Case-insensitive key used to remember whether the group is collapsed. */
    val key: String?,
    val hosts: List<HostEntity>,
    val collapsed: Boolean,
)

/**
 * Splits hosts into the sections the list shows. Sorting and grouping are independent: [sort] orders the hosts
 * within each section, so "Group hosts" can be on with any of Name / Recent / Most used.
 */
object HostGrouping {
    const val UNGROUPED = "Ungrouped"

    /** The key a group is remembered under: trimmed and lower-cased, so "Work" and "work " are the same group. */
    fun keyOf(group: String?): String = group?.trim()?.lowercase().orEmpty()

    fun sort(hosts: List<HostEntity>, sort: HostSort): List<HostEntity> = when (sort) {
        HostSort.Name -> hosts.sortedBy { it.nickname.lowercase() }
        HostSort.Recent -> hosts.sortedByDescending { it.lastConnectedAt ?: 0 }
        HostSort.Frequent -> hosts.sortedByDescending { it.connectCount }
    }

    /**
     * @param grouped whether "Group hosts" is on.
     * @param collapsed keys (see [keyOf]) of the groups the user has collapsed.
     * @param searching a search is active: matches are shown expanded so a result can't hide in a collapsed group.
     */
    fun sections(
        hosts: List<HostEntity>,
        sort: HostSort,
        grouped: Boolean,
        collapsed: Set<String>,
        searching: Boolean,
    ): List<HostSection> {
        val sorted = sort(hosts, sort)
        val anyGroup = hosts.any { keyOf(it.group).isNotEmpty() }
        // With no groups at all there is nothing to group by: a lone "Ungrouped" header would just be noise.
        if (!grouped || !anyGroup) return listOf(HostSection(null, null, sorted, collapsed = false))

        val byKey = sorted.groupBy { keyOf(it.group) }
        val named = byKey.filterKeys { it.isNotEmpty() }
            .map { (key, list) ->
                // The title's casing must not change when the sort does, so take it from the host that comes
                // first by name rather than from whichever host the chosen sort puts first.
                val title = list.minByOrNull { it.nickname.lowercase() }!!.group!!.trim()
                HostSection(title, key, list, collapsed = !searching && key in collapsed)
            }
            .sortedBy { it.title!!.lowercase() }
        val ungrouped = byKey[""]?.let { HostSection(UNGROUPED, "", it, collapsed = !searching && "" in collapsed) }
        return named + listOfNotNull(ungrouped)
    }
}
