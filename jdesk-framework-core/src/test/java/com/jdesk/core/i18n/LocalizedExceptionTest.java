package com.jdesk.core.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LocalizedExceptionTest {

    @Test
    void aReasonWithoutUnderlyingFailureIsARejectionEvenWhenWrapped() {
        LocalizedException missing = new LocalizedException("projects.open.fileMissing", Map.of(), "File no longer exists");

        assertSame(missing, LocalizedException.rejectionOf(missing));
        assertSame(missing, LocalizedException.rejectionOf(new CompletionException(missing)));
        assertSame(missing, LocalizedException.rejectionOf(new ExecutionException(new CompletionException(missing))));
    }

    @Test
    void failuresWithAnUnderlyingCauseOrWithoutAReasonAreNotRejections() {
        LocalizedException wrapped = new LocalizedException("records.error.revealFailed", Map.of(), "Reveal failed",
                new IOException("access denied"));

        assertNull(LocalizedException.rejectionOf(wrapped));
        assertNull(LocalizedException.rejectionOf(new CompletionException(wrapped)));
        assertNull(LocalizedException.rejectionOf(new IllegalStateException("boom")));
    }
}
