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
 * The "Access" bottom-nav destination's real content — this pass replaces the previous local-demo
 * placeholder (`KeySlotDemoData` counts) with the actual Key Access Request feature, dispatched
 * by the signed-in role: Technician/Vendor request their own access, Regional Admin/Super Admin
 * review and decide on it. Same nav slot, same "Access" label/icon, because both are exactly the
 * "which keys can I access" concept the destination was already named for.
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

        null -> Text("Sign in to view key access.", style = MaterialTheme.typography.bodyMedium)
    }
}
