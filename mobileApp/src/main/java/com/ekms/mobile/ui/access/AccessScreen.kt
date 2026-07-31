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
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onNotice: (String) -> Unit,
) {
    when (profile?.role) {
        UserRole.TECHNICIAN, UserRole.VENDOR ->
            KeyAccessRequestScreen(
                apiClient = apiClient,
                profile = profile,
                refreshEpoch = refreshEpoch,
                onLiveStatus = onLiveStatus,
                onNotice = onNotice,
            )

        UserRole.REGIONAL_ADMIN, UserRole.SUPER_ADMIN ->
            KeyAccessApprovalScreen(
                apiClient = apiClient,
                refreshEpoch = refreshEpoch,
                onLiveStatus = onLiveStatus,
                onNotice = onNotice,
            )

        null -> Text(
            "Sign in to view key access.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
