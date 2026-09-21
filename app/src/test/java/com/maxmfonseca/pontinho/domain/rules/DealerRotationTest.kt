package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.ParticipantId
import org.junit.Assert.assertEquals
import org.junit.Test

class DealerRotationTest {
    private val ana = ParticipantId("ana")
    private val bia = ParticipantId("bia")
    private val caio = ParticipantId("caio")

    @Test
    fun `dealer advances in current eligible roster order`() {
        assertEquals(bia, DealerRotation.next(ana, listOf(ana, bia, caio)))
        assertEquals(caio, DealerRotation.next(bia, listOf(ana, bia, caio)))
    }

    @Test
    fun `dealer wraps from last eligible player to first`() {
        assertEquals(ana, DealerRotation.next(caio, listOf(ana, bia, caio)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rotation rejects a current dealer absent from roster`() {
        DealerRotation.next(ParticipantId("missing"), listOf(ana, bia, caio))
    }
}
