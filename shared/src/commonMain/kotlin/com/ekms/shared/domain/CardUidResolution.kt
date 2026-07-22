package com.ekms.shared.domain

/**
 * Resolves a card UID scanned at the public card-swipe reader (protocol doc
 * section 9) against enrolled personnel cards and enrolled key cards.
 *
 * Personnel NFC cards and key NFC cards share the same physical medium and
 * UID space — there is no hardware-level way to tell them apart. Which
 * action a scan means can only be decided by looking the UID up against
 * both registries; it must never be assumed from which screen or flow
 * triggered the scan.
 *
 * This is a pure decision over two already-resolved record IDs, not a UID
 * store: a raw UID is credential-equivalent material and must stay
 * Terminal-local and encrypted (see CLAUDE.md boundary #2), so the actual
 * UID lookup happens in terminalApp's encrypted storage, which passes in
 * only the resulting nullable IDs. Keeping the decision itself here means
 * terminalApp and any future UID-based web/mobile flow apply the exact same
 * rule, including how an ambiguous double-enrollment is handled.
 */
object CardUidResolver {
    fun resolve(matchedUserId: String?, matchedKeyId: String?): CardUidMatch = when {
        matchedUserId != null && matchedKeyId != null -> CardUidMatch.Ambiguous(matchedUserId, matchedKeyId)
        matchedUserId != null -> CardUidMatch.User(matchedUserId)
        matchedKeyId != null -> CardUidMatch.Key(matchedKeyId)
        else -> CardUidMatch.NoMatch
    }
}

sealed interface CardUidMatch {
    /** The scanned card is an enrolled personnel card — proceed as login. */
    data class User(val userId: String) : CardUidMatch

    /** The scanned card is an enrolled key card — proceed as a key-return trigger. */
    data class Key(val keyId: String) : CardUidMatch

    /**
     * The same UID is enrolled to both a person and a key — a data-integrity
     * problem (enrollment should prevent this), not a normal case to silently
     * pick one side of. Callers must surface this as an error, not a guess.
     */
    data class Ambiguous(val userId: String, val keyId: String) : CardUidMatch

    /** Not enrolled to anything — callers must surface this as an unrecognized-card error, never a silent fallback. */
    data object NoMatch : CardUidMatch
}
