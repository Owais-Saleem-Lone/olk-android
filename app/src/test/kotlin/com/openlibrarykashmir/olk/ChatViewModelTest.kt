package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.repository.ChatInfo
import com.openlibrarykashmir.olk.core.data.repository.ChatMessage
import com.openlibrarykashmir.olk.core.data.repository.Conversation
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.SendOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.messages.ChatUiState
import com.openlibrarykashmir.olk.feature.messages.ChatViewModel
import com.openlibrarykashmir.olk.feature.messages.mergeById
import com.openlibrarykashmir.olk.ui.parseTimestamp
import com.openlibrarykashmir.olk.ui.timeAgo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun message(id: String, createdAt: String, sender: String = "other", content: String = "msg $id") =
        ChatMessage(id = id, requestId = "r1", senderId = sender, content = content, createdAt = createdAt)

    private class FakeAuth : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = "me"
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeMessages(
        var history: List<ChatMessage> = emptyList(),
        var status: RequestStatus = RequestStatus.ACCEPTED,
        var sendOutcome: (String) -> SendOutcome = { SendOutcome.NotAllowed },
    ) : MessagesRepository {
        val live = MutableSharedFlow<ChatMessage>()
        val sent = mutableListOf<String>()

        override suspend fun conversations(userId: String) = emptyList<Conversation>()
        override suspend fun chatInfo(requestId: String, viewerId: String) =
            ChatInfo(requestId, "A Book", status, otherParty = null)
        override suspend fun history(requestId: String) = history
        override suspend fun send(requestId: String, senderId: String, content: String): SendOutcome {
            sent += content
            return sendOutcome(content)
        }
        override fun newMessages(requestId: String): Flow<ChatMessage> = live
    }

    @Test
    fun `timestamps parse in both the REST and the Realtime format`() {
        val rest = parseTimestamp("2026-09-17T12:50:01.783555+00:00")
        val realtime = parseTimestamp("2026-09-17 12:50:01.783555+00")
        assertNotNull(rest)
        assertEquals(rest!!.toInstant(), realtime!!.toInstant())
        assertEquals(
            Instant.parse("2026-09-17T07:20:01Z"),
            parseTimestamp("2026-09-17 12:50:01+0530")!!.toInstant(),
        )
    }

    @Test
    fun `a message seen twice shows once, in time order across formats`() {
        val a = message("a", "2026-09-17T10:00:00+00:00")
        val b = message("b", "2026-09-17 10:05:00+00")
        val aAgain = message("a", "2026-09-17 10:00:00+00")

        assertEquals(listOf("a", "b"), mergeById(listOf(b), listOf(aAgain, a)).map { it.id })
    }

    @Test
    fun `inbox times use the web's buckets`() {
        val now = Instant.parse("2026-09-17T12:00:00Z")
        assertEquals("just now", timeAgo("2026-09-17T11:59:40+00:00", now))
        assertEquals("5m ago", timeAgo("2026-09-17T11:55:00+00:00", now))
        assertEquals("3h ago", timeAgo("2026-09-17T09:00:00+00:00", now))
        assertEquals("yesterday", timeAgo("2026-09-16T10:00:00+00:00", now))
        assertEquals("3d ago", timeAgo("2026-09-14T10:00:00+00:00", now))
        // Older than a week: a short date. The month's abbreviation depends on the
        // JDK's locale data ("Sep" or "Sept"), so only its start is pinned.
        assertTrue(timeAgo("2026-09-02T10:00:00+00:00", now, ZoneOffset.UTC).startsWith("2 Sep"))
    }

    @Test
    fun `a sent message that also arrives over Realtime appears once`() = runTest {
        val repo = FakeMessages()
        val echo = message("m1", "2026-09-17T10:00:00+00:00", sender = "me", content = "hi")
        repo.sendOutcome = { SendOutcome.Sent(echo) }
        val viewModel = ChatViewModel("r1", repo, FakeAuth())
        viewModel.start()
        advanceUntilIdle()

        viewModel.onDraftChange("  hi  ")
        viewModel.send()
        advanceUntilIdle()
        repo.live.emit(echo)
        advanceUntilIdle()

        val state = viewModel.uiState.value as ChatUiState.Ready
        assertEquals(listOf("hi"), repo.sent)
        assertEquals(listOf("m1"), state.messages.map { it.id })
        assertEquals("", state.draft)
    }

    @Test
    fun `a refused send puts the text back and explains why`() = runTest {
        val repo = FakeMessages(sendOutcome = { SendOutcome.NotAllowed })
        val viewModel = ChatViewModel("r1", repo, FakeAuth())
        viewModel.start()
        advanceUntilIdle()

        viewModel.messages.test {
            viewModel.onDraftChange("please keep me")
            viewModel.send()
            advanceUntilIdle()
            assertEquals("This chat is closed, so the message wasn't sent.", awaitItem())
        }
        val state = viewModel.uiState.value as ChatUiState.Ready
        assertEquals("please keep me", state.draft)
        assertFalse(state.isSending)
    }

    @Test
    fun `nothing can be sent before a request is accepted`() = runTest {
        val repo = FakeMessages(status = RequestStatus.PENDING)
        val viewModel = ChatViewModel("r1", repo, FakeAuth())
        viewModel.start()
        advanceUntilIdle()

        viewModel.onDraftChange("hello?")
        viewModel.send()
        advanceUntilIdle()

        assertFalse((viewModel.uiState.value as ChatUiState.Ready).canMessage)
        assertEquals(emptyList<String>(), repo.sent)
    }

    @Test
    fun `a new message over Realtime is added to the open chat`() = runTest {
        val repo = FakeMessages(history = listOf(message("old", "2026-09-17T09:00:00+00:00")))
        val viewModel = ChatViewModel("r1", repo, FakeAuth())
        viewModel.start()
        advanceUntilIdle()

        repo.live.emit(message("new", "2026-09-17 09:30:00+00"))
        advanceUntilIdle()

        assertEquals(listOf("old", "new"), (viewModel.uiState.value as ChatUiState.Ready).messages.map { it.id })
        viewModel.stop()
    }
}
