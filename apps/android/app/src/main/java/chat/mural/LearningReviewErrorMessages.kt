package chat.mural

import androidx.annotation.StringRes
import chat.mural.core.FinalAssessmentInvalidResultException
import chat.mural.core.FinalAssessmentTimeoutException

/** Review-specific failures never imply that a provider, key or quota is unavailable. */
@StringRes
internal fun learningReviewErrorMessageRes(error: Throwable): Int = when (error) {
    is FinalAssessmentTimeoutException -> R.string.words_review_timeout
    is FinalAssessmentInvalidResultException -> R.string.words_review_invalid
    else -> 0 // Keep provider failures and their recovery advice in the general mapper.
}
