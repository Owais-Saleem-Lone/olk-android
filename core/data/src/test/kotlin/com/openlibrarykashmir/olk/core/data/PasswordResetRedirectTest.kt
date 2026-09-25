package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.session.passwordResetRedirect
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordResetRedirectTest {

    @Test
    fun `the link is the one the website's forgot-password form sends`() {
        // Production's redirect allow-list has exactly this URL; any other shape is
        // refused and GoTrue falls back to the Site URL.
        assertEquals(
            "https://www.openlibrarykashmir.com/auth/confirm?next=/reset-password",
            passwordResetRedirect("https://www.openlibrarykashmir.com"),
        )
        assertEquals(
            "https://www.openlibrarykashmir.com/auth/confirm?next=/reset-password",
            passwordResetRedirect("https://www.openlibrarykashmir.com/"),
        )
    }
}
