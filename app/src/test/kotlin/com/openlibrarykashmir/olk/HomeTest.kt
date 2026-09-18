package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.repository.HomeFeed
import com.openlibrarykashmir.olk.core.data.repository.HomeRepository
import com.openlibrarykashmir.olk.core.data.repository.CommunityStats
import com.openlibrarykashmir.olk.feature.home.HomeViewModel
import com.openlibrarykashmir.olk.feature.home.activityName
import com.openlibrarykashmir.olk.feature.home.timeAgo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun `time ago matches the website's wording, from Postgres timestamps`() {
        assertEquals("just now", timeAgo("2026-09-18T11:59:30.123456+00:00", now))
        assertEquals("5m ago", timeAgo("2026-09-18T11:55:00+00:00", now))
        assertEquals("3h ago", timeAgo("2026-09-18T09:00:00+00:00", now))
        assertEquals("2d ago", timeAgo("2026-09-16T11:00:00+00:00", now))
        assertEquals("", timeAgo("not a date", now))
    }

    @Test
    fun `activity names drop an email's domain, and fall back to Someone`() {
        assertEquals("owais", activityName("owais@example.com"))
        assertEquals("Owais Saleem", activityName("Owais Saleem"))
        assertEquals("Someone", activityName(null))
        assertEquals("Someone", activityName("@example.com"))
    }

    @Test
    fun `home loads its feed`() = runTest {
        val feed = HomeFeed(stats = CommunityStats(totalBooks = 3, totalUsers = 2, completedExchanges = 1))
        val vm = HomeViewModel(object : HomeRepository {
            override suspend fun feed() = feed
        })
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(feed, vm.uiState.value.feed)
    }

    @Test
    fun `no connection says so instead of an empty page`() = runTest {
        val vm = HomeViewModel(object : HomeRepository {
            override suspend fun feed(): HomeFeed = throw RuntimeException("Unable to resolve host")
        })
        vm.refresh()
        advanceUntilIdle()

        assertEquals("No connection. Check your network and try again.", vm.uiState.value.error)
    }
}
