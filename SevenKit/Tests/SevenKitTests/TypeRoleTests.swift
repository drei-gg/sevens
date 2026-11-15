import SwiftUI
import Testing
@testable import SevenKit

/// The type system (polish guide §4.1): each role maps to one system text style, so Dynamic Type
/// works, and the roles use two weights only.
@Suite struct TypeRoleTests {
    @Test func rolesMapToTheGuideStyles() {
        #expect(TextRole.hero.font == .largeTitle.weight(.bold))
        #expect(TextRole.title.font == .title.weight(.bold))
        #expect(TextRole.section.font == .title3.weight(.bold))
        #expect(TextRole.rowTitle.font == .body)
        #expect(TextRole.body.font == .body)
        #expect(TextRole.secondary.font == .subheadline)
        // Nothing smaller than footnote for content.
        #expect(TextRole.caption.font == .footnote)
    }

    @Test func onlySupportingRolesAreMuted() {
        #expect(TextRole.allCases.filter(\.isMuted) == [.secondary, .caption])
    }
}
