package com.affinaloyalty.rtlsdk

/**
 * Where the web app wants a destination rendered.
 *
 * All but the last keep the page out of host-controlled code; they differ in
 * whether the app is left, and whether the flow returns a result.
 *
 * [OVERLAY] is an in-app browser for general browsing, any host. Merchant pages
 * belong here - an offer exit opens our redirect endpoint and lands on the
 * merchant, without leaving the app.
 *
 * [AUTH] is an overlay for a flow that comes back with a result, restricted to
 * the configured host. Card linking uses it. Android serves both with a Custom
 * Tab; the difference is the host restriction and callback handling. On iOS
 * the two are genuinely different APIs - an authentication
 * session that ends on the return redirect and runs an isolated cookie jar,
 * versus a plain in-app browser - so keep the values distinct on the wire.
 *
 * [PROVIDER_AUTH] is the same as [AUTH] for a page the provider hosts. Open
 * banking runs at Plaid or Cardlytics, so the destination cannot be ours, but
 * the flow still returns a result through the app scheme. Asking for it is a
 * statement that the destination is a third party, which is why it is a
 * separate value rather than a relaxed [AUTH]: the host restriction is the
 * whole of what [AUTH] protects, and quietly dropping it for some callers
 * would leave nothing to check.
 *
 * [EXTERNAL] hands the URL to the system browser, leaving the app.
 */
internal enum class RTLSurface(val wireValue: String) {
    OVERLAY("overlay"),
    AUTH("auth"),
    PROVIDER_AUTH("providerAuth"),
    EXTERNAL("external");

    companion object {
        /** Unknown or absent values return null for protocol validation. */
        fun from(value: String?): RTLSurface? =
            entries.firstOrNull { it.wireValue == value }
    }
}
