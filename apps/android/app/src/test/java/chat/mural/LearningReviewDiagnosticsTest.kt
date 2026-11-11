package chat.mural

import chat.mural.core.FinalAssessmentInvalidResultException
import chat.mural.core.FinalAssessmentTimeoutException
import chat.mural.network.APIClient
import chat.mural.network.GeminiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningReviewDiagnosticsTest {
    @Test fun reviewAndProviderFailuresHaveDistinctFixedCategories() {
        val cases = listOf(
            FinalAssessmentTimeoutException() to "assessment_timeout",
            FinalAssessmentInvalidResultException() to "assessment_invalid",
            GeminiException.Unavailable to "gemini_transport",
            GeminiException.InvalidResponse to "gemini_invalid_response",
            GeminiException.Incomplete to "gemini_incomplete",
            GeminiException.Refused to "gemini_refused",
            GeminiException.MissingKey to "gemini_missing_key",
            GeminiException.SearchUnavailable to "gemini_search_unavailable",
            APIClient.APIException.InvalidResponse to "openai_invalid_response",
            APIClient.APIException.Incomplete to "openai_incomplete",
            APIClient.APIException.Refused to "openai_refused",
            APIClient.APIException.MissingKey to "openai_missing_key",
        )
        cases.forEach { (failure, category) ->
            assertEquals("category=$category", reviewFailureDiagnostic(failure))
        }
    }

    @Test fun httpDiagnosticsRetainOnlyStatusAndAllowlistedCodes() {
        assertEquals("category=gemini_http status=400 code=INVALID_ARGUMENT",
            reviewFailureDiagnostic(GeminiException.Http(400, "INVALID_ARGUMENT")))
        assertEquals("category=gemini_http status=503 code=UNAVAILABLE",
            reviewFailureDiagnostic(GeminiException.Http(503, "UNAVAILABLE")))
        assertEquals("category=gemini_http status=429 code=none",
            reviewFailureDiagnostic(GeminiException.Http(429)))
        assertEquals("category=openai_http status=429 code=insufficient_quota",
            reviewFailureDiagnostic(APIClient.APIException.Http(429, "insufficient_quota", "private-reference")))
    }

    @Test fun statusesOutsideTheHttpRangeCannotEnterDiagnostics() {
        for (status in listOf(Int.MIN_VALUE, -1, 0, 99, 600, Int.MAX_VALUE)) {
            assertEquals("category=gemini_http status=unknown code=none",
                reviewFailureDiagnostic(GeminiException.Http(status)))
            assertEquals("category=openai_http status=unknown code=none",
                reviewFailureDiagnostic(APIClient.APIException.Http(status)))
        }
        for (status in listOf(100, 599)) {
            assertEquals("category=gemini_http status=$status code=none",
                reviewFailureDiagnostic(GeminiException.Http(status)))
        }
    }

    @Test fun maliciousMessagesCodesReferencesAndCausesAreNeverEchoed() {
        val privateText = "private-transcript https://private.example/?key=secret\nforged-log"
        val failures = listOf(
            IOException(privateText, IllegalStateException(privateText)),
            IllegalStateException(privateText),
            GeminiException.Http(400, privateText),
            APIClient.APIException.Http(400, privateText, "private-reference"),
        )
        assertEquals("category=unknown", reviewFailureDiagnostic(failures[0]))
        assertEquals("category=unknown", reviewFailureDiagnostic(failures[1]))
        assertEquals("category=gemini_http status=400 code=none", reviewFailureDiagnostic(failures[2]))
        assertEquals("category=openai_http status=400 code=none", reviewFailureDiagnostic(failures[3]))
        failures.forEach { failure ->
            val diagnostic = reviewFailureDiagnostic(failure)
            for (privatePart in listOf("private", "transcript", "https", "secret", "forged", "Exception", "\n")) {
                assertFalse(diagnostic.contains(privatePart))
            }
        }
    }
}
