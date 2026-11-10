package com.nuvio.tv.core.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The avatar set built into the app (assets/party-avatars/<set>/<name>.svg). A box refers to one as "fun:<set>/<name>",
 * so every box with this app shows the same picture without loading it from anywhere.
 *
 * A Nuvio supporter avatar travels as "member:<id>". Its picture is a private download, so only a box whose own account
 * has supporter avatars can show it ([memberImages]); every other box shows the initial.
 */
object PartyAvatars {
    const val PREFIX = "fun:"
    const val MEMBER_PREFIX = "member:"
    const val ASSET_DIR = "party-avatars"
    private val REF = Regex("fun:[a-z0-9-]{1,32}/[a-z0-9-]{1,32}")
    private val MEMBER_REF = Regex("member:[A-Za-z0-9._-]{1,64}")

    /** Sets in the order the picker shows them. */
    val SETS = listOf("fun-emoji", "animals", "adventurer", "lorelei", "notionists", "bottts", "pixel-art", "thumbs")

    /** Round logo pictures stored as PNG files. They are offered in the profile avatar picker only. */
    const val LOGO_SET = "logos"
    private val BUNDLED_SETS = SETS + LOGO_SET

    /** File type of the pictures in a bundled set. */
    fun fileExtension(set: String): String = if (set == LOGO_SET) "png" else "svg"

    private val _memberImages = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Supporter avatar id -> picture on this box; empty unless this box's account has supporter avatars. */
    val memberImages: StateFlow<Map<String, String>> = _memberImages.asStateFlow()

    fun setMemberImages(images: Map<String, String>) {
        _memberImages.value = images
    }

    fun isBundled(ref: String?): Boolean = ref != null && REF.matches(ref) && ref.removePrefix(PREFIX).substringBefore('/') in BUNDLED_SETS

    fun isMember(ref: String?): Boolean = ref != null && MEMBER_REF.matches(ref)

    fun ref(set: String, name: String): String = "$PREFIX$set/$name"

    fun memberRef(id: String): String? = "$MEMBER_PREFIX$id".takeIf(::isMember)

    /** What an image loader can open: the bundled file, a supporter picture this box may show, or the https picture. */
    fun imageUri(ref: String?, members: Map<String, String> = _memberImages.value): String? = when {
        ref == null -> null
        isBundled(ref) -> {
            val path = ref.removePrefix(PREFIX)
            "file:///android_asset/$ASSET_DIR/$path.${fileExtension(path.substringBefore('/'))}"
        }
        isMember(ref) -> members[ref.removePrefix(MEMBER_PREFIX)]
        ref.startsWith("https://") -> ref
        else -> null
    }
}
