package chat.mural

import chat.mural.core.FinalAssessmentInvalidResultException
import chat.mural.core.FinalAssessmentTimeoutException
import chat.mural.network.APIClient
import chat.mural.network.GeminiException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningReviewErrorMessagesTest {
    @Test fun timeoutAndUnverifiableReviewUseDistinctRecoveryMessages() {
        assertEquals(R.string.words_review_timeout,
            learningReviewErrorMessageRes(FinalAssessmentTimeoutException()))
        assertEquals(R.string.words_review_invalid,
            learningReviewErrorMessageRes(FinalAssessmentInvalidResultException()))
    }

    @Test fun providerFailuresRetainTheirExistingVendorAndRecoveryAdvice() {
        val failures = listOf(
            GeminiException.Http(401) to R.string.gemini_authentication,
            GeminiException.Http(403) to R.string.gemini_model_access,
            GeminiException.Http(429) to R.string.gemini_quota,
            GeminiException.Http(429, "RATE_LIMIT_EXCEEDED") to R.string.gemini_rate_limit,
            GeminiException.Http(400) to R.string.gemini_request_rejected,
            GeminiException.Http(503) to R.string.gemini_service_busy,
            APIClient.APIException.Http(429, "insufficient_quota") to R.string.error_provider_quota,
        )
        failures.forEach { (failure, expected) ->
            assertEquals(0, learningReviewErrorMessageRes(failure))
            assertEquals(expected, errorMessageRes(failure))
        }
    }

    @Test fun errorsOutsideAssessmentDecodingAreNotDescribedAsInvalidReviews() {
        assertEquals(0, learningReviewErrorMessageRes(SerializationException("unrelated local data")))
        assertEquals(0, learningReviewErrorMessageRes(IllegalStateException("unexpected")))
    }
}
