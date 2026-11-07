package com.maxmfonseca.pontinho

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePrivacyDocumentationTest {
    @Test
    fun publishedPrivacyPolicyDisclosesLocallyStoredRecognitionMetadataInBothLanguages() {
        val repositoryRoot = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        val policy = repositoryRoot.resolve("docs/privacy-policy/index.md").readText()
        val normalized = policy.replace(Regex("\\s+"), " ")

        assertTrue(normalized.contains("cartas reconhecidas, valores de confiança, correções, posições detectadas na imagem e a versão do modelo"))
        assertTrue(normalized.contains("recognized cards, confidence values, corrections, detected image positions, and model version"))
        assertFalse(policy.contains("salvar apenas\na pontuação resultante"))
        assertFalse(policy.contains("saving only the\nresulting score"))
    }
}
