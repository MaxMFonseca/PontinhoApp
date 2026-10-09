package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.BuildConfig
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract-only bytes: these tests do not establish model quality. */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExperimentalRecognitionBundleTest {
    private fun json() = BundleFixture.json().apply {
        listOf("holdoutPartitionSha256", "holdoutIdentity", "holdoutManifestSha256",
            "selectionSha256", "sourceAuditReceiptSha256", "normalizedDatasetSha256",
            "sourceReportSha256", "evaluationReceiptSha256", "benchmark").forEach(::remove)
        put("kind", "EXPERIMENTAL_ONE_DECK"); put("releaseApproved", false)
        put("experimentReportSha256", "e".repeat(64))
        put("upstreamSplitSha256", "f".repeat(64))
        put("evidence", JSONObject().put("trainSceneCount", 964).put("validationSceneCount", 193)
            .put("experimentalTestSceneCount", 128).put("visualDeckCount", 1)
            .put("deckDisjoint", false).put("productionHoldout", false))
        fun row(count: Int) = JSONObject().put("sceneCount", count).put("detectorRecall", .4)
            .put("detectorFalsePositiveShare", .3).put("classifierTop1Accuracy", .2)
            .put("exactCardMultisetAccuracy", .1).put("exactHandTotalAccuracy", .15)
        put("metrics", JSONObject().put("validation", row(193)).put("experimentalTest", row(128)))
    }
    private fun verify(document: JSONObject, allow: Boolean) = RecognitionAssetVerifier(allowExperimental = allow)
        .verify(BundleFixture.detector, BundleFixture.classifier, document.toString().encodeToByteArray())

    @Test fun `experiment is rejected by strict verifier even when every model hash matches`() {
        assertTrue(verify(json(), false) is RecognitionAvailability.Unavailable)
    }
    @Test fun `explicit debug experiment accepts measured low quality and preserves warning identity`() {
        val result = verify(json(), true)
        if (BuildConfig.DEBUG) {
            val metadata = (result as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
            assertTrue(metadata.experimental)
            assertTrue(metadata.modelVersion.startsWith("experimental-"))
        } else assertTrue(result is RecognitionAvailability.Unavailable)
    }
    @Test fun `experiment still rejects tampering missing models and false release claims`() {
        for (document in listOf(
            json().put("releaseApproved", true),
            json().apply { getJSONObject("evidence").put("deckDisjoint", true) },
            json().apply { getJSONObject("evidence").put("productionHoldout", true) },
            json().apply { getJSONObject("metrics").getJSONObject("validation").put("sceneCount", 194) },
            json().apply { getJSONObject("classifier").put("modelSha256", "0".repeat(64)) },
            json().apply { getJSONObject("evidence").put("visualDeckCount", 10) },
            json().put("benchmark", JSONObject()),
        )) assertTrue(verify(document, true) is RecognitionAvailability.Unavailable)
        assertTrue(RecognitionAssetVerifier(allowExperimental = true)
            .verify(BundleFixture.detector, null, json().toString().encodeToByteArray()) is RecognitionAvailability.Unavailable)
    }
    @Test fun `provider admits both experiment models only in debug and keeps construction lazy`() = runTest {
        var constructions = 0
        val provider = VerifiedCardRecognitionEngineProvider(
            { Triple(BundleFixture.detector, BundleFixture.classifier, json().toString().encodeToByteArray()) },
            { detector, classifier, metadata ->
                constructions++
                assertArrayEquals(BundleFixture.detector, detector)
                assertArrayEquals(BundleFixture.classifier, classifier)
                assertTrue(metadata.experimental)
                object : CardRecognitionEngine {
                    override val availability = RecognitionAvailability.Available(metadata)
                    override suspend fun recognize(bitmap: Bitmap) = CardRecognitionResult(emptyList(), 0, metadata.modelVersion)
                    override fun close() = Unit
                }
            }, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals(0, constructions)
        if (BuildConfig.DEBUG) {
            assertTrue(provider.availability.value is RecognitionAvailability.Available)
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            provider.get().recognize(bitmap)
            assertEquals(1, constructions)
            bitmap.recycle()
        } else assertTrue(provider.availability.value is RecognitionAvailability.Unavailable)
        provider.close()
    }
}
