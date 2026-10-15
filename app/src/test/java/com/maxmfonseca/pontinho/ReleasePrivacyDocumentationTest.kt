package com.maxmfonseca.pontinho

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePrivacyDocumentationTest {
    @Test
    fun privacyDraftDisclosesLocallyStoredRecognitionMetadataInBothLanguages() {
        val repositoryRoot = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        val policy = repositoryRoot.resolve("docs/legal/privacy-policy-draft.md").readText()
        val normalized = policy.replace(Regex("\\s+"), " ")

        assertTrue(normalized.contains("cartas reconhecidas, confiança, correções, posições na imagem e versão do modelo"))
        assertTrue(normalized.contains("recognized cards, confidence, corrections, image bounds, and model version"))
        assertFalse(policy.contains("salvar apenas\na pontuação resultante"))
        assertFalse(policy.contains("saving only the\nresulting score"))
    }
}
