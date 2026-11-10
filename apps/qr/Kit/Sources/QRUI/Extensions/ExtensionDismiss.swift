import Foundation

/// The two ways an extension hands control back to the host app.
enum ExtensionDismiss {
    /// Done: finished (even when there was nothing to do).
    static func done(_ context: NSExtensionContext?) {
        context?.completeRequest(returningItems: nil)
    }

    /// Nothing was made or checked: the user backed out.
    static func cancel(_ context: NSExtensionContext?) {
        context?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
}
