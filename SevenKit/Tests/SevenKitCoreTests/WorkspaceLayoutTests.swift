import Testing
@testable import SevenKitCore

@Test func workspaceAdaptsToAvailableSpaceWithoutDeviceAssumptions() {
    for width in [240.0, 320, 402, 600] { #expect(WorkspaceLayout(width: width).arrangement == .stacked) }
    for width in [704.0, 760, 1000, 1400] {
        let layout = WorkspaceLayout(width: width)
        #expect(layout.arrangement == .columns)
        #expect(layout.primaryWidth >= 320 && layout.detailWidth >= 360)
        #expect(layout.primaryWidth + layout.detailWidth + WorkspaceLayout.gap == width)
    }
    #expect(WorkspaceLayout(width: 1000, accessibilityText: true).arrangement == .stacked)
    #expect(WorkspaceLayout(width: .infinity).primaryWidth == 0)
    #expect(WorkspaceLayout(width: -1).primaryWidth == 0)
}
