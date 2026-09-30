package io.legado.app.help.readaloud.offline

import org.junit.Assert.*
import org.junit.Test

class AudioPrefetchLifecycleTest {
    private val lifecycle = AudioPrefetchLifecycle()
    private val book = "physical-book-a"

    private fun start(service: AudioPrefetchLifecycle.Service): AudioPrefetchLifecycle.Work {
        val request = lifecycle.requestUserPlay(book)
        assertNotNull("用户播放必须签发内存请求", request)
        assertTrue(lifecycle.acceptUserPlay(service, request, book))
        return lifecycle.capture(service, book)!!
    }

    @Test fun `user play waits for assembly then exposes three chapters`() {
        val service = lifecycle.createService()
        val work = start(service)
        assertFalse(lifecycle.isAllowed(work, 1))
        assertEquals(1..3, lifecycle.assembled(work, book, 0, 20))
        assertTrue(lifecycle.isAllowed(work, 1))
        assertFalse(lifecycle.isAllowed(work, 0))
        assertFalse(lifecycle.isAllowed(work, 4))
    }

    @Test fun `onCreate and internal play without user action never grant`() {
        val service = lifecycle.createService()
        assertNull(lifecycle.capture(service, book))
        assertFalse(lifecycle.acceptUserPlay(service, null, book))
        assertTrue(lifecycle.assembled(null, book, 0, 20).isEmpty())
    }

    @Test fun `internal reassembly and cross chapter retain token`() {
        val service = lifecycle.createService()
        val first = start(service)
        lifecycle.assembled(first, book, 0, 20)
        val next = lifecycle.capture(service, book)!!
        assertSame(first.token, next.token)
        assertEquals(3..5, lifecycle.assembled(next, book, 2, 20))
        assertFalse(lifecycle.isAllowed(first, 1))
        assertTrue(lifecycle.isAllowed(next, 5))
    }

    @Test fun `late assembly cannot move newer position even in same session`() {
        val service = lifecycle.createService()
        val first = start(service)
        val second = lifecycle.capture(service, book)!!
        assertEquals(6..8, lifecycle.assembled(second, book, 5, 20))
        assertTrue(lifecycle.assembled(first, book, 0, 20).isEmpty())
        assertTrue(lifecycle.isAllowed(second, 6))
    }

    @Test fun `pause blocks late work and automatic resume`() {
        val service = lifecycle.createService()
        val work = start(service)
        lifecycle.pause(service)
        assertNull(lifecycle.capture(service, book))
        assertTrue(lifecycle.assembled(work, book, 0, 20).isEmpty())
    }

    @Test fun `service pause revokes user request not yet delivered`() {
        val service = lifecycle.createService()
        val request = lifecycle.requestUserPlay(book)
        lifecycle.pause(service)
        assertFalse(lifecycle.acceptUserPlay(service, request, book))
        assertNull(lifecycle.capture(service, book))
    }

    @Test fun `service pause revokes replacement request while old token remains bound`() {
        val service = lifecycle.createService()
        start(service)
        val request = lifecycle.requestUserPlay(book)
        lifecycle.pause(service)
        assertFalse(lifecycle.acceptUserPlay(service, request, book))
    }

    @Test fun `new assembly cannot use the previous chapter window before completion`() {
        val service = lifecycle.createService()
        val first = start(service)
        lifecycle.assembled(first, book, 0, 20)
        val next = lifecycle.capture(service, book)!!
        assertNull(lifecycle.currentWindow())
        assertFalse(lifecycle.isAllowed(next, 1))
        assertEquals(6..8, lifecycle.assembled(next, book, 5, 20))
        assertTrue(lifecycle.isAllowed(next, 6))
    }

    @Test fun `explicit continue after pause grants a new token`() {
        val service = lifecycle.createService()
        val before = start(service)
        lifecycle.pause(service)
        val after = start(service)
        assertNotSame(before.token, after.token)
        assertEquals(5..7, lifecycle.assembled(after, book, 4, 20))
        assertTrue(lifecycle.assembled(before, book, 0, 20).isEmpty())
        assertTrue(lifecycle.isAllowed(after, 5))
    }

    @Test fun `stop and book switch revoke pending requests before service delivery`() {
        listOf<() -> Unit>({ lifecycle.revoke() }, { lifecycle.bookChanged(book, "other") })
            .forEach { cancel ->
                val request = lifecycle.requestUserPlay(book)
                cancel()
                val service = lifecycle.createService()
                assertFalse(lifecycle.acceptUserPlay(service, request, book))
            }
    }

    @Test fun `same physical book assignment does not revoke`() {
        val service = lifecycle.createService()
        val work = start(service)
        lifecycle.bookChanged(book, book)
        assertEquals(1..3, lifecycle.assembled(work, book, 0, 10))
    }

    @Test fun `request is single use and only latest pending request survives`() {
        val service = lifecycle.createService()
        val old = lifecycle.requestUserPlay(book)
        val latest = lifecycle.requestUserPlay(book)
        assertFalse(lifecycle.acceptUserPlay(service, old, book))
        assertTrue(lifecycle.acceptUserPlay(service, latest, book))
        assertFalse(lifecycle.acceptUserPlay(service, latest, book))
        assertNotNull(lifecycle.capture(service, book))
    }

    @Test fun `onCreate internal assembly cannot consume pending user request`() {
        val request = lifecycle.requestUserPlay(book)
        val service = lifecycle.createService()
        assertNull(lifecycle.capture(service, book))
        assertTrue(lifecycle.acceptUserPlay(service, request, book))
        assertNotNull(lifecycle.capture(service, book))
    }

    @Test fun `request and work from another process instance are rejected`() {
        val service = lifecycle.createService()
        val work = start(service)
        val request = lifecycle.requestUserPlay(book)
        val restarted = AudioPrefetchLifecycle()
        val otherService = restarted.createService()
        assertFalse(restarted.acceptUserPlay(otherService, request, book))
        assertTrue(restarted.assembled(work, book, 0, 10).isEmpty())
        assertFalse(restarted.isAllowed(work, 1))
    }

    @Test fun `new service cannot inherit consumed authorization`() {
        val oldService = lifecycle.createService()
        val work = start(oldService)
        lifecycle.assembled(work, book, 0, 10)
        val newService = lifecycle.createService()
        assertNull(lifecycle.capture(newService, book))
        assertFalse(lifecycle.isAllowed(work, 1))
    }

    @Test fun `old service destroy and pause cannot revoke newer user authorization`() {
        val oldService = lifecycle.createService()
        val oldWork = start(oldService)
        val current = lifecycle.createService()
        val work = start(current)
        lifecycle.destroy(oldService)
        lifecycle.pause(oldService)
        assertTrue(lifecycle.assembled(oldWork, book, 0, 10).isEmpty())
        assertEquals(4..6, lifecycle.assembled(work, book, 3, 10))
    }

    @Test fun `request targeted at existing service cannot migrate to replacement`() {
        lifecycle.createService()
        val request = lifecycle.requestUserPlay(book)
        val replacement = lifecycle.createService()
        assertFalse(lifecycle.acceptUserPlay(replacement, request, book))
    }

    @Test fun `physical book mismatch cannot authorize or update`() {
        val service = lifecycle.createService()
        val request = lifecycle.requestUserPlay(book)
        assertFalse(lifecycle.acceptUserPlay(service, request, "other"))
        val work = start(service)
        assertTrue(lifecycle.assembled(work, "other", 0, 10).isEmpty())
        assertFalse(lifecycle.isAllowed(work, 1))
    }

    @Test fun `book end is truncated without configurable window`() {
        val service = lifecycle.createService()
        val work = start(service)
        assertEquals(9..9, lifecycle.assembled(work, book, 8, 10))
        val last = lifecycle.capture(service, book)
        assertTrue(lifecycle.assembled(last, book, 9, 10).isEmpty())
    }

    @Test fun `notification offer grants only when consumed once by owning service`() {
        val service = lifecycle.createService()
        val offer = lifecycle.offerResume(service, book)
        assertNull(lifecycle.capture(service, book))
        assertTrue(lifecycle.acceptResume(service, offer, book))
        assertFalse(lifecycle.acceptResume(service, offer, book))
        assertNotNull(lifecycle.capture(service, book))
    }

    @Test fun `notification from destroyed service or switched book cannot grant`() {
        val service = lifecycle.createService()
        val offer = lifecycle.offerResume(service, book)
        lifecycle.destroy(service)
        val replacement = lifecycle.createService()
        assertFalse(lifecycle.acceptResume(replacement, offer, book))
        val anotherOffer = lifecycle.offerResume(replacement, book)
        lifecycle.bookChanged(book, "other")
        assertFalse(lifecycle.acceptResume(replacement, anotherOffer, book))
    }

    @Test fun `global cancellation leaves independent manual session untouched`() {
        val manual = AudioPrefetchSession()
        val manualToken = manual.onUserPlay("manual-book")
        manual.updatePosition(manualToken, "manual-book", 0, 10)
        val service = lifecycle.createService()
        start(service)
        lifecycle.revoke()
        assertTrue(manual.isAllowed(manualToken, "manual-book", 1))
        assertNull(lifecycle.capture(service, book))
    }

    @Test fun `internal continuation retains token and must assemble its own window`() {
        val service = lifecycle.createService()
        val first = start(service)
        lifecycle.assembled(first, book, 0, 20)
        val id = lifecycle.continuation(book)
        assertNotNull(id)
        assertTrue(lifecycle.isAllowed(first, 1))
        val next = lifecycle.resolveContinuation(service, id, book)
        assertNotNull(next)
        assertSame(first.token, next!!.token)
        assertFalse(lifecycle.isAllowed(next, 1))
        assertEquals(5..7, lifecycle.assembled(next, book, 4, 20))
        assertNull(lifecycle.resolveContinuation(service, id, book))
    }

    @Test fun `delayed continuation cannot borrow a newer user session`() {
        val service = lifecycle.createService()
        start(service)
        val old = lifecycle.continuation(book)
        assertNotNull(old)
        val newer = start(service)
        lifecycle.assembled(newer, book, 8, 20)
        assertNull(lifecycle.resolveContinuation(service, old, book))
        assertTrue(lifecycle.isAllowed(newer, 9))
    }

    @Test fun `missing continuation cannot inherit an active authorization`() {
        val service = lifecycle.createService()
        val work = start(service)
        lifecycle.assembled(work, book, 0, 20)
        assertNull(lifecycle.resolveContinuation(service, null, book))
        assertTrue(lifecycle.isAllowed(work, 1))
    }

    @Test fun `continuation is rejected after cancellation replacement and process restart`() {
        val service = lifecycle.createService()
        start(service)
        val id = lifecycle.continuation(book)
        assertNotNull(id)
        assertNull(lifecycle.continuation("other"))
        assertNull(lifecycle.resolveContinuation(service, id, "other"))
        lifecycle.pause(service)
        assertNull(lifecycle.resolveContinuation(service, id, book))
        val replacement = lifecycle.createService()
        assertNull(lifecycle.resolveContinuation(replacement, id, book))
        val restarted = AudioPrefetchLifecycle()
        assertNull(restarted.resolveContinuation(restarted.createService(), id, book))
    }

    @Test fun `failure cancels only its own unconsumed request`() {
        val service = lifecycle.createService()
        val failed = lifecycle.requestUserPlay(book)
        lifecycle.cancelRequest(failed)
        assertFalse(lifecycle.acceptUserPlay(service, failed, book))
    }

    @Test fun `only the current pending request counts as explicit user intent`() {
        val service = lifecycle.createService()
        val old = lifecycle.requestUserPlay(book)
        val current = lifecycle.requestUserPlay(book)
        assertFalse(lifecycle.isPendingUserPlay(old, book))
        assertTrue(lifecycle.isPendingUserPlay(current, book))
        lifecycle.acceptUserPlay(service, current, book)
        assertFalse(lifecycle.isPendingUserPlay(current, book))
    }

    @Test fun `late failure cannot revoke newer or already consumed requests`() {
        val service = lifecycle.createService()
        val old = lifecycle.requestUserPlay(book)
        val newer = lifecycle.requestUserPlay(book)
        lifecycle.cancelRequest(old)
        lifecycle.cancelRequest(null)
        assertTrue(lifecycle.acceptUserPlay(service, newer, book))
        val work = lifecycle.capture(service, book)
        lifecycle.cancelRequest(newer)
        assertEquals(1..3, lifecycle.assembled(work, book, 0, 20))
    }

    @Test fun `cold media gesture is bound before adopting the selected book`() {
        val gesture = lifecycle.beginDeferredPlay()
        val request = lifecycle.bindDeferredPlay(gesture, book)
        assertNotNull(request)
        lifecycle.bookChanged(null, book)
        val service = lifecycle.createService()
        assertTrue(lifecycle.acceptUserPlay(service, request, book))
        assertEquals(1..3, lifecycle.assembled(lifecycle.capture(service, book), book, 0, 10))
        assertNull(lifecycle.bindDeferredPlay(gesture, book))
    }

    @Test fun `cancelled media lookup cannot create authorization on completion`() {
        val gesture = lifecycle.beginDeferredPlay()
        lifecycle.revoke()
        assertNull(lifecycle.bindDeferredPlay(gesture, book))
        val next = lifecycle.beginDeferredPlay()
        lifecycle.cancelRequest(next)
        assertNull(lifecycle.bindDeferredPlay(next, book))
    }

    @Test fun `newer user action replaces pending cold media lookup`() {
        val gesture = lifecycle.beginDeferredPlay()
        val service = lifecycle.createService()
        val newer = start(service)
        assertNull(lifecycle.bindDeferredPlay(gesture, book))
        assertEquals(4..6, lifecycle.assembled(newer, book, 3, 10))
    }

    @Test fun `cold book adoption allows only one expected null to book transition`() {
        val gesture = lifecycle.beginDeferredPlay()
        val request = lifecycle.bindDeferredPlay(gesture, book)
        assertNotNull(request)
        lifecycle.bookChanged(null, "other")
        assertFalse(lifecycle.acceptUserPlay(lifecycle.createService(), request, book))

        val retry = lifecycle.bindDeferredPlay(lifecycle.beginDeferredPlay(), book)
        assertNotNull(retry)
        lifecycle.bookChanged(null, book)
        lifecycle.bookChanged(book, "other")
        lifecycle.bookChanged("other", book)
        assertFalse(lifecycle.acceptUserPlay(lifecycle.createService(), retry, book))
    }

    @Test fun `failed and blank cold book selections cannot authorize`() {
        val service = lifecycle.createService()
        val gesture = lifecycle.beginDeferredPlay()
        assertNull(lifecycle.bindDeferredPlay(gesture, " "))
        assertNull(lifecycle.capture(service, book))
        assertNull(lifecycle.bindDeferredPlay(gesture, book))
    }

    @Test fun `pause cancels chapter loading even without auto authorization`() {
        val service = lifecycle.createService()
        val request = lifecycle.chapterRequests.begin(book, 2, null)
        assertTrue(lifecycle.chapterRequests.bind(request, book, 2, 10))
        lifecycle.pause(service)
        assertNull(lifecycle.chapterRequests.complete(book, 2, 10))
    }

    @Test fun `new user action cancels old chapter waiting request`() {
        val service = lifecycle.createService()
        start(service)
        val request = lifecycle.chapterRequests.begin(book, 2, lifecycle.continuation(book))
        lifecycle.chapterRequests.bind(request, book, 2, 10)
        lifecycle.requestUserPlay(book)
        assertNull(lifecycle.chapterRequests.complete(book, 2, 10))
    }

    @Test fun `old service pause cannot cancel current chapter waiting request`() {
        val old = lifecycle.createService()
        val current = lifecycle.createService()
        start(current)
        val request = lifecycle.chapterRequests.begin(book, 2, lifecycle.continuation(book))
        lifecycle.chapterRequests.bind(request, book, 2, 10)
        lifecycle.pause(old)
        assertSame(request, lifecycle.chapterRequests.complete(book, 2, 10))
    }

    @Test fun `old service user request cannot revoke current authorization`() {
        val old = lifecycle.createService()
        val current = lifecycle.createService()
        val currentRequest = lifecycle.requestUserPlay(book)
        assertTrue(lifecycle.acceptUserPlay(current, currentRequest, book))
        assertNull(lifecycle.requestUserPlay(old, book))
        assertNotNull(lifecycle.capture(current, book))
    }
}
