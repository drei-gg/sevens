import SwiftUI
import UIKit

// Phase 0 placeholder. WP 3.5 owns this file.

public struct MakeExtensionRoot: View {
    let context: NSExtensionContext?

    public init(context: NSExtensionContext?) {
        self.context = context
    }

    public var body: some View {
        Button(String(localized: "common.done", defaultValue: "Done", bundle: .module)) {
            context?.completeRequest(returningItems: nil)
        }
    }
}
