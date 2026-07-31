package com.ekms.mobile.ui.access

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.ui.keyaccess.KeyAccessApprovalScreen
import com.ekms.mobile.ui.keyaccess.KeyAccessRequestScreen
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.domain.UserRole

/**
 * Access tab — Key Access Apply (Technician/Vendor) or approval (Regional Admin/Super Admin).
 */
@Composable
fun AccessScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    onNotice: (String) -> Unit,
) {
    when (profile?.role) {
        UserRole.TECHNICIAN, UserRole.VENDOR ->
            KeyAccessRequestScreen(apiClient = apiClient, profile = profile, onNotice = onNotice)

        UserRole.REGIONAL_ADMIN, UserRole.SUPER_ADMIN ->
            KeyAccessApprovalScreen(apiClient = apiClient, onNotice = onNotice)

        // One-time web-portal bootstrap credential (see UserRole.GOD_ADMIN's doc) — never
        // actually logs into mobileApp; branch exists only for exhaustiveness.
        UserRole.GOD_ADMIN, null -> Text(
            "Sign in to view key access.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
