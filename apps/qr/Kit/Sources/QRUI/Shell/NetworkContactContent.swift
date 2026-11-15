import Foundation

/// SPEC §12, row for row, as sentences that read in a list at any text size.
struct NetworkContactEntry: Equatable {
    let when: String
    let who: String
    let sent: String
}

enum NetworkContactContent {
    static func entries() -> [NetworkContactEntry] {
        [
            NetworkContactEntry(
                when: String(localized: "network.peek.when", defaultValue: "You tap Peek", bundle: .module),
                who: String(localized: "network.peek.who", defaultValue: "The address in the code, and each redirect", bundle: .module),
                sent: String(localized: "network.peek.sent", defaultValue: "A normal page request with no cookies", bundle: .module)),
            NetworkContactEntry(
                when: String(localized: "network.registry.when", defaultValue: "You tap Peek", bundle: .module),
                who: String(localized: "network.registry.who", defaultValue: "The public registry for that domain ending", bundle: .module),
                sent: String(localized: "network.registry.sent", defaultValue: "The domain name", bundle: .module)),
            NetworkContactEntry(
                when: String(localized: "network.open.when", defaultValue: "You tap Open", bundle: .module),
                who: String(localized: "network.open.who", defaultValue: "Safari opens the link", bundle: .module),
                sent: String(localized: "network.open.sent", defaultValue: "Whatever Safari sends", bundle: .module)),
            NetworkContactEntry(
                when: String(localized: "network.store.when", defaultValue: "You buy or restore", bundle: .module),
                who: String(localized: "network.store.who", defaultValue: "Apple, through StoreKit", bundle: .module),
                sent: String(localized: "network.store.sent", defaultValue: "Handled by iOS", bundle: .module)),
            NetworkContactEntry(
                when: String(localized: "network.lists.when", defaultValue: "You tap Check for newer lists", bundle: .module),
                who: String(localized: "network.lists.who", defaultValue: "GitHub, where our public code lives", bundle: .module),
                sent: String(localized: "network.lists.sent", defaultValue: "A request for one file. Nothing about you or what you scanned.", bundle: .module)),
        ]
    }

    static var closing: String {
        String(localized: "network.closing", defaultValue: "Anything not listed here doesn’t happen.", bundle: .module)
    }

    static var privacyLabel: String {
        String(localized: "network.privacy", defaultValue: "App Store privacy label: Data Not Collected.", bundle: .module)
    }
}
