package chat.mural

import chat.mural.core.FinalAssessmentInvalidResultException
import chat.mural.core.FinalAssessmentTimeoutException
import chat.mural.network.APIClient
import chat.mural.network.GeminiException

/** Fixed diagnostic fields only; never inspect exception messages, causes or provider references. */
internal fun reviewFailureDiagnostic(error: Throwable): String = when (error) {
    is FinalAssessmentTimeoutException -> "category=assessment_timeout"
    is FinalAssessmentInvalidResultException -> "category=assessment_invalid"
    is GeminiException.Http -> httpDiagnostic("gemini_http", error.status,
        error.code?.takeIf { it in GEMINI_REVIEW_CODES })
    GeminiException.Unavailable -> "category=gemini_transport"
    GeminiException.InvalidResponse -> "category=gemini_invalid_response"
    GeminiException.Incomplete -> "category=gemini_incomplete"
    GeminiException.Refused -> "category=gemini_refused"
    GeminiException.MissingKey -> "category=gemini_missing_key"
    GeminiException.SearchUnavailable -> "category=gemini_search_unavailable"
    is APIClient.APIException.Http -> httpDiagnostic("openai_http", error.status,
        error.code?.takeIf { it in OPENAI_REVIEW_CODES })
    APIClient.APIException.InvalidResponse -> "category=openai_invalid_response"
    APIClient.APIException.Incomplete -> "category=openai_incomplete"
    APIClient.APIException.Refused -> "category=openai_refused"
    APIClient.APIException.MissingKey -> "category=openai_missing_key"
    else -> "category=unknown"
}

private fun httpDiagnostic(category: String, status: Int, code: String?): String =
    "category=$category status=${status.takeIf { it in 100..599 } ?: "unknown"} code=${code ?: "none"}"

private val GEMINI_REVIEW_CODES = setOf(
    "API_KEY_INVALID", "API_KEY_EXPIRED", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
    "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED", "INVALID_ARGUMENT",
    "FAILED_PRECONDITION", "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED",
)

private val OPENAI_REVIEW_CODES = setOf(
    "invalid_api_key", "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded",
    "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "rate_limit_exceeded", "slow_down",
    "model_not_found", "permission_denied", "server_error",
)
